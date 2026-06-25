package com.example.jiraagent.graph;

import com.example.jiraagent.exec.ExecutionReport;
import com.example.jiraagent.exec.StepResult;
import com.example.jiraagent.exec.ToolInvoker;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanStep;
import com.example.jiraagent.service.SsePublisher;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class GraphPlanExecutor {

    private final ToolInvoker invoker;
    private final int pageSize;
    private final SsePublisher ssePublisher;
    private final int maxIterations;
    private final int maxRetryProcessItems;

    public GraphPlanExecutor(ToolInvoker invoker, @Value("${agent.executor.page-size:50}") int pageSize, SsePublisher ssePublisher, @Value("${agent.executor.maxIterations:10000}") int maxIterations, @Value("${agent.executor.maxRetryProcessItems:2}") int maxRetryProcessItems) {
        this.invoker = invoker;
        this.pageSize = pageSize;
        this.ssePublisher = ssePublisher;
        this.maxIterations = maxIterations;
        this.maxRetryProcessItems = maxRetryProcessItems;
    }

    public ExecutionReport execute(Plan plan, FluxSink<ServerSentEvent<Object>> sink) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return new ExecutionReport(false, "Empty plan; nothing to execute.", List.of());
        }

        int pageIdx = IntStream.range(0, plan.steps().size()).filter(i -> plan.steps().get(i).paginated()).findFirst().getAsInt();
        if (pageIdx < 0) {
            return new ExecutionReport(false, "No paginated step; cannot execute.", List.of());
        }

        ssePublisher.publishEvent(sink, "Executing your request ...");

        PlanStep pageStep = plan.steps().get(pageIdx);
        List<PlanStep> body = IntStream.range(0, plan.steps().size()).filter(i -> i != pageIdx && !plan.steps().get(i).isCount()).mapToObj(plan.steps()::get).toList();
        PlanStep countStep = null;
        if (pageStep != null) {
            countStep = plan.steps().stream().filter(PlanStep::isCount).findFirst().orElseThrow();
        }
        try {
            Map<String, Object> init = new HashMap<>();
            init.put(JiraAgentState.PAGE_STEP, pageStep);
            init.put(JiraAgentState.BODY_STEPS, body);
            init.put(JiraAgentState.OFFSET, 0);
            init.put(JiraAgentState.PAGE_SIZE, pageSize);
            init.put(JiraAgentState.PROCESS_ITEM_ATTEMPTS, 0);

            int totalCount = 0;
            if (countStep != null) {
                Map<String, Object> args = new HashMap<>(invoker.resolveArgs(pageStep.args(), null, null));
                totalCount = (int) invoker.invokeResolved(countStep, args).output();
                if (totalCount == 0) {
                    ssePublisher.publishEvent(sink, "No records found. Stopping execution.");
                }
                init.put(JiraAgentState.TOTAL_COUNT, totalCount);
            }

            if (countStep != null && totalCount == 0) {
                return new ExecutionReport(true, "Plan executed.", List.of());
            }
            CompiledGraph<JiraAgentState> graph = buildGraph(sink);
            RunnableConfig config = RunnableConfig.builder().threadId("Thread-1").build();
            JiraAgentState finalState = graph.invoke(init, config).orElseThrow(() -> new IllegalStateException("Graph produced no final state"));

            List<StepResult> results = finalState.results();
            return new ExecutionReport(true, "Plan executed.", results);

        } catch (GraphStateException e) {
            return new ExecutionReport(false, "Graph build/compile failed: " + e.getMessage(), List.of());
        } catch (Exception e) {
            return new ExecutionReport(false, "Graph execution failed: " + e.getMessage(), List.of());
        }
    }

    private CompiledGraph<JiraAgentState> buildGraph(FluxSink<ServerSentEvent<Object>> sink) throws GraphStateException {
        FetchPageNode fetch = new FetchPageNode(invoker);
        ProcessItemsNode process = new ProcessItemsNode(invoker, sink, ssePublisher);
        ErrorNodeExecutionGraph errorNodeExecutionGraph = new ErrorNodeExecutionGraph(sink, ssePublisher);

        StateGraph<JiraAgentState> g = new StateGraph<>(JiraAgentState.SCHEMA, JiraAgentState::new)
                .addNode("fetch_page", node_async(fetch))
                .addNode("process_items", node_async(process))
                .addNode("error", node_async(errorNodeExecutionGraph))
                .addEdge(START, "fetch_page")
                .addConditionalEdges("process_items",
                        edge_async(state -> state.processItemsRetry() ?
                                state.processItemAttempts() < maxRetryProcessItems ?
                                        "retry_process_items" : "error" : state.offset() >= state.totalCount() ? "exhausted" : "more")
                        , Map.of("retry_process_items", "process_items", "error", "error", "more", "fetch_page", "exhausted", END))
                .addEdge("fetch_page", "process_items")
                .addEdge("error", END);

        return g.compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).recursionLimit(maxIterations).build());
    }
}