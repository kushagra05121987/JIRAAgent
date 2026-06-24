package com.example.jiraagent.controller;

import com.example.jiraagent.model.PlanRequest;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.service.PlanningOrchestrator;
import com.example.jiraagent.service.PlanningOrchestrator.ExecutionOutcome;
import com.example.jiraagent.service.SsePublisher;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlanningController {

    private final PlanningOrchestrator orchestrator;
    private final SsePublisher ssePublisher;
    private final Map<String, Object> multiCastSink;

    public PlanningController(PlanningOrchestrator orchestrator, SsePublisher ssePublisher, Map<String, Object> multiCastSink) {
        this.orchestrator = orchestrator;
        this.ssePublisher = ssePublisher;
        this.multiCastSink = multiCastSink;
    }

    @PostMapping("/plan")
    public ResponseEntity<PlanResponse> plan(@Valid @RequestBody PlanRequest request) {
        PlanResponse response = orchestrator.handle(request);
        if (response.rejected()) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SuppressWarnings("unchecked")
    public Flux<ServerSentEvent<Object>> execute(@Valid @RequestBody PlanRequest request) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                ExecutionOutcome outcome = orchestrator.handleAndExecute(request, ((FluxSink<ServerSentEvent<Object>>) multiCastSink.get("sink")));
                if (outcome.planResponse().rejected()) {
                    ssePublisher.publishEvent(((FluxSink<ServerSentEvent<Object>>) multiCastSink.get("sink")), outcome);
                }
            } finally {
                ((Sinks.Many<ServerSentEvent<Object>>) multiCastSink.get("many")).tryEmitComplete();
            }
        });

        return ((Sinks.Many<ServerSentEvent<Object>>) multiCastSink.get("many")).asFlux();
    }
}
