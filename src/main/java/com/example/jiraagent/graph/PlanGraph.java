package com.example.jiraagent.graph;

import com.example.jiraagent.guardrail.OutputGuardrail;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.service.PlannerService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class PlanGraph {

    private final PlannerService planner;
    private final OutputGuardrail guardrail;
    private final int maxAttempts;

    public PlanGraph(PlannerService planner, OutputGuardrail guardrail, @Value("${agent.planner.max-attempts:2}") int maxAttempts) {
        this.planner = planner;
        this.guardrail = guardrail;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public record Result(Plan plan, boolean valid, String reason, List<String> warnings, int attempts) {
    }

    public Result run(String prompt) {
        try {
            CompiledGraph<PlanGraphState> graph = buildGraph();

            Map<String, Object> init = new HashMap<>();
            init.put(PlanGraphState.PROMPT, prompt);
            init.put(PlanGraphState.ATTEMPTS, 0);

            RunnableConfig config = RunnableConfig.builder().threadId("Thread-1").build();
            PlanGraphState finalState = graph.invoke(init, config).orElseThrow(() -> new IllegalStateException("Plan graph produced no final state"));

            return new Result(finalState.plan(), finalState.valid(), finalState.reason(), finalState.warnings(), finalState.attempts());

        } catch (GraphStateException e) {
            return new Result(null, false, "Plan graph build failed: " + e.getMessage(), List.of(), 0);
        } catch (Exception e) {
            return new Result(null, false, "Plan graph execution failed: " + e.getMessage(), List.of(), 0);
        }
    }

    private CompiledGraph<PlanGraphState> buildGraph() throws GraphStateException {
        PlanNode planNode = new PlanNode(planner);
        ValidateNode validateNode = new ValidateNode(guardrail);

        StateGraph<PlanGraphState> g = new StateGraph<>(PlanGraphState.SCHEMA, PlanGraphState::new)
                .addNode("plan", node_async(planNode))
                .addNode("validate", node_async(validateNode))
                .addEdge(START, "plan")
                .addEdge("plan", "validate")
                .addConditionalEdges("validate", edge_async(state -> {
                    if (state.valid()) {
                        return "ok";
                    }
                    return state.attempts() < maxAttempts ? "retry" : "failed";
                }), Map.of("ok", END, "retry", "plan", "failed", END));

        return g.compile();
    }
}