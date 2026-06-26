package com.example.jiraagent.controller;

import com.example.jiraagent.model.PlanRequest;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.model.ReviewRequest;
import com.example.jiraagent.service.PlanningOrchestrator;
import com.example.jiraagent.service.SsePublisher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PlanningController {

    private final PlanningOrchestrator orchestrator;
    private final SsePublisher ssePublisher;
    private final BeanFactory beanFactory;

    public PlanningController(PlanningOrchestrator orchestrator, SsePublisher ssePublisher, BeanFactory beanFactory) {
        this.orchestrator = orchestrator;
        this.ssePublisher = ssePublisher;
        this.beanFactory = beanFactory;
    }

    @PostMapping("/plan")
    @SuppressWarnings("unchecked")
    public ResponseEntity<PlanResponse> plan(@Valid @RequestBody PlanRequest request) {
        Map<String, Object> bridgeMap = (Map<String, Object>) beanFactory.getBean("bridge");
        FluxSink<ServerSentEvent<Object>> bridge = (FluxSink<ServerSentEvent<Object>>) bridgeMap.get("sink");
        PlanResponse response = orchestrator.handle(request, bridge);
        if (response.rejected()) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SuppressWarnings("unchecked")
    public Flux<ServerSentEvent<Object>> execute(@Valid @RequestBody PlanRequest request) {
        Map<String, Object> bridgeMap = (Map<String, Object>) beanFactory.getBean("bridge");
        FluxSink<ServerSentEvent<Object>> bridge = (FluxSink<ServerSentEvent<Object>>) bridgeMap.get("sink");
        Sinks.Many<ServerSentEvent<Object>> manyUnicast = (Sinks.Many<ServerSentEvent<Object>>) bridgeMap.get("manyUniCast");
        String threadId = UUID.randomUUID().toString();

        return manyUnicast.asFlux()
                .timeout(Duration.ofMinutes(2))
                .doOnSubscribe(ignored -> Schedulers.boundedElastic().schedule(() ->
                        orchestrator.startPlanForReview(request, manyUnicast, bridge, threadId)));
    }

    @PostMapping("/execute/review/{threadId}")
    public ResponseEntity<Map<String, String>> review(@PathVariable String threadId,
                                                      @RequestBody ReviewRequest review) {
        Schedulers.boundedElastic().schedule(() -> orchestrator.processReview(threadId, review));
        String message = review.approved()
                ? "Approval received. Execution started — watch the SSE stream for progress."
                : "Feedback received. Replanning in progress — watch the SSE stream for the updated plan.";
        return ResponseEntity.ok(Map.of("threadId", threadId, "message", message));
    }
}
