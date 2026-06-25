package com.example.jiraagent.graph;

import com.example.jiraagent.service.SsePublisher;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.FluxSink;

import java.util.Map;

public class ErrorNodePlanGraph implements NodeAction<PlanGraphState> {

    private final FluxSink<ServerSentEvent<Object>> sink;
    private final SsePublisher ssePublisher;
    private static final Logger log = LoggerFactory.getLogger(ErrorNodePlanGraph.class);

    ErrorNodePlanGraph(FluxSink<ServerSentEvent<Object>> sink, SsePublisher ssePublisher) {
        this.sink = sink;
        this.ssePublisher = ssePublisher;
    }

    @Override
    public Map<String, Object> apply(PlanGraphState state) {
        ssePublisher.publishEvent(sink, "All attempts exhausted. Could not execute request.");
        return Map.of();
    }
}