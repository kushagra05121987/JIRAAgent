package com.example.jiraagent.graph;

import com.example.jiraagent.exec.StepResult;
import com.example.jiraagent.exec.ToolInvoker;
import com.example.jiraagent.guardrail.GuardrailResult;
import com.example.jiraagent.guardrail.OutputGuardrail;
import com.example.jiraagent.model.PlanStep;
import com.example.jiraagent.service.SsePublisher;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

public class RetryFailedOpNode implements NodeAction<JiraAgentState> {

    private static final Logger log = LoggerFactory.getLogger(RetryFailedOpNode.class);

    private final ToolInvoker invoker;
    private final FluxSink<ServerSentEvent<Object>> sink;
    private final SsePublisher ssePublisher;

    public RetryFailedOpNode(ToolInvoker invoker, FluxSink<ServerSentEvent<Object>> sink, SsePublisher ssePublisher) {
        this.invoker = invoker;
        this.sink = sink;
        this.ssePublisher = ssePublisher;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(JiraAgentState state) {
        ConcurrentLinkedQueue<StepResult> pageResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Object[]> failedSteps = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        state.failedSteps().forEach(step -> {
            futures.add(CompletableFuture.runAsync(() -> {
                ssePublisher.publishEvent(sink, "Processing " + ((Map<String, Object>) step[0]).get("issue_key"));
                StepResult r = invoker.invoke((PlanStep) step[1], step[0], step[0]);
                pageResults.add(r);
                if (!r.success() || r.error() != null) {
                    log.warn(r.error());
                    ssePublisher.publishEvent(sink, "Step : " + ((PlanStep) step[1]).rationale() + " failed for item: "+ step[0] + ". Retrying ...");
                    failedSteps.add(new Object[]{step[0], step[1]});
                }
            }));
        });

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        Map<String, Object> update = new HashMap<>();
        if (pageResults.stream().anyMatch(r -> !r.success())) {
            update.put(JiraAgentState.PROCESS_ITEMS_RETRY, true);
            update.put(JiraAgentState.PROCESS_ITEM_ATTEMPTS, state.processItemAttempts() + 1);
            update.put(JiraAgentState.FAILED_STEPS, failedSteps);
        } else {
            log.info("Item Processed Successfully");
            update.put(JiraAgentState.PROCESS_ITEM_ATTEMPTS, 0);
            update.put(JiraAgentState.PROCESS_ITEMS_RETRY, false);
        }
        return update;
    }
}