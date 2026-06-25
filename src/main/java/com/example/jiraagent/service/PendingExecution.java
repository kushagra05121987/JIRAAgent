package com.example.jiraagent.service;

import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanRequest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

public record PendingExecution(
        Sinks.Many<ServerSentEvent<Object>> many,
        FluxSink<ServerSentEvent<Object>> bridge,
        PlanRequest request,
        Plan plan,
        int replanAttempts
) {}
