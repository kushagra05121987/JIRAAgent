package com.example.jiraagent.graph;

import com.example.jiraagent.exec.StepResult;
import com.example.jiraagent.exec.ToolInvoker;
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

public class ProcessItemsNode implements NodeAction<JiraAgentState> {

    private static final Logger log = LoggerFactory.getLogger(ProcessItemsNode.class);

    private final ToolInvoker invoker;
    private final FluxSink<ServerSentEvent<Object>> sink;
    private final SsePublisher ssePublisher;

    public ProcessItemsNode(ToolInvoker invoker, FluxSink<ServerSentEvent<Object>> sink, SsePublisher ssePublisher) {
        this.invoker = invoker;
        this.sink = sink;
        this.ssePublisher = ssePublisher;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(JiraAgentState state) {
        List<Object> items = state.currentItems();
        List<PlanStep> body = state.bodySteps();
        int offset = state.offset();
        int pageSize = state.pageSize();

        ConcurrentLinkedQueue<StepResult> pageResults = new ConcurrentLinkedQueue<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());
        for (Object item : items) {
            futures.add(CompletableFuture.runAsync(() -> {
                ssePublisher.publishEvent(sink, "Processing " + ((Map<String,Object>) item).get("issue_key"));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                runBodyForItem(body, item, pageResults);
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        log.debug("process_items offset={} processed {} items, {} calls",
                offset, items.size(), pageResults.size());

        Map<String, Object> update = new HashMap<>();
        update.put(JiraAgentState.OFFSET, offset + pageSize);
        update.put(JiraAgentState.RESULTS, new ArrayList<>(pageResults));
        return update;
    }

    private void runBodyForItem(List<PlanStep> body, Object item,
                                ConcurrentLinkedQueue<StepResult> sink) {
        for (PlanStep step : body) {
            StepResult r = invoker.invoke(step, item, item);
            sink.add(r);
            if (!r.success()) {
                log.warn("Step {} ({}) failed for item {}: {}",
                        step.stepNumber(), step.tool(), item, r.error());
                return;
            }
        }
    }
}