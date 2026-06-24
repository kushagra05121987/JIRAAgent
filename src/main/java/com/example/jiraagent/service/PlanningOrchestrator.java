package com.example.jiraagent.service;

import com.example.jiraagent.exec.ExecutionReport;
import com.example.jiraagent.exec.PlanExecutor;
import com.example.jiraagent.graph.GraphPlanExecutor;
import com.example.jiraagent.graph.PlanGraph;
import com.example.jiraagent.guardrail.GuardrailResult;
import com.example.jiraagent.guardrail.InputGuardrail;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanRequest;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.model.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;

@Service
public class PlanningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanningOrchestrator.class);

    private final InputGuardrail inputGuardrail;
    private final PlanExecutor executor;
    private final GraphPlanExecutor graphExecutor;
    private final boolean useGraph;
    private final PlanGraph planGraph;
    private final SsePublisher ssePublisher;

    public PlanningOrchestrator(InputGuardrail inputGuardrail, SsePublisher ssePublisher, PlanExecutor executor, GraphPlanExecutor graphExecutor, PlanGraph planGraph, @Value("${agent.executor.use-graph:true}") boolean useGraph) {
        this.inputGuardrail = inputGuardrail;
        this.executor = executor;
        this.graphExecutor = graphExecutor;
        this.useGraph = useGraph;
        this.planGraph = planGraph;
        this.ssePublisher = ssePublisher;
    }

    public PlanResponse handle(PlanRequest request) {
        return plan(request).response();
    }

    public ExecutionOutcome handleAndExecute(PlanRequest request, FluxSink<ServerSentEvent<Object>> sink) {
        ssePublisher.publishEvent(sink, "Planning Your execution ...");
        PlanOutcome outcome = plan(request);
        if (outcome.response().rejected()) {
            return new ExecutionOutcome(outcome.response(), null);
        }
        Plan p = outcome.plan();
        boolean paginated = p.steps() != null && p.steps().stream().anyMatch(PlanStep::paginated);

        ExecutionReport report = null;
        if (paginated) report = graphExecutor.execute(p, sink);

        return new ExecutionOutcome(outcome.response(), report);
    }

    private PlanOutcome plan(PlanRequest request) {
        GuardrailResult inputCheck = inputGuardrail.check(request);
        if (!inputCheck.allowed()) {
            log.info("Input guardrail blocked request: {}", inputCheck.reason());
            return new PlanOutcome(PlanResponse.rejected("Input rejected: " + inputCheck.reason()), null);
        }

        PlanGraph.Result result;
        try {
            result = planGraph.run(request.prompt());
        } catch (Exception e) {
            log.error("Plan graph failed", e);
            return new PlanOutcome(PlanResponse.rejected("Planning failed: " + e.getMessage()), null);
        }

        if (!result.valid()) {
            log.info("Planning failed after {} attempt(s): {}", result.attempts(), result.reason());
            return new PlanOutcome(PlanResponse.rejected("Generated plan rejected after " + result.attempts() + " attempt(s): " + result.reason()), null);
        }

        return new PlanOutcome(PlanResponse.ok(result.plan(), result.warnings()), result.plan());
    }

    private record PlanOutcome(PlanResponse response, Plan plan) {
    }

    public record ExecutionOutcome(PlanResponse planResponse, ExecutionReport report) {
    }
}