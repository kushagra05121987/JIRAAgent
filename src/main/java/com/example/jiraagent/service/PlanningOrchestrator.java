package com.example.jiraagent.service;

import com.example.jiraagent.exec.ExecutionReport;
import com.example.jiraagent.exec.PlanExecutor;
import com.example.jiraagent.graph.GraphPlanExecutor;
import com.example.jiraagent.graph.PlanGraph;
import com.example.jiraagent.guardrail.GuardrailResult;
import com.example.jiraagent.guardrail.InputGuardrail;
import com.example.jiraagent.guardrail.OutputGuardrail;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanRequest;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.model.PlanStep;
import com.example.jiraagent.model.ReviewRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

@Service
public class PlanningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanningOrchestrator.class);

    private final InputGuardrail inputGuardrail;
    private final OutputGuardrail outputGuardrail;
    private final PlanExecutor executor;
    private final GraphPlanExecutor graphExecutor;
    private final boolean useGraph;
    private final PlanGraph planGraph;
    private final SsePublisher ssePublisher;
    private final ExecutionSessionStore sessionStore;
    private final int maxReviewAttempts;

    public PlanningOrchestrator(InputGuardrail inputGuardrail,
                                OutputGuardrail outputGuardrail,
                                SsePublisher ssePublisher,
                                PlanExecutor executor,
                                GraphPlanExecutor graphExecutor,
                                PlanGraph planGraph,
                                ExecutionSessionStore sessionStore,
                                @Value("${agent.executor.use-graph:true}") boolean useGraph,
                                @Value("${agent.planner.max-review-attempts:3}") int maxReviewAttempts) {
        this.inputGuardrail = inputGuardrail;
        this.outputGuardrail = outputGuardrail;
        this.executor = executor;
        this.graphExecutor = graphExecutor;
        this.useGraph = useGraph;
        this.planGraph = planGraph;
        this.ssePublisher = ssePublisher;
        this.sessionStore = sessionStore;
        this.maxReviewAttempts = maxReviewAttempts;
    }

    public PlanResponse handle(PlanRequest request, FluxSink<ServerSentEvent<Object>> bridge) {
        return plan(request, bridge).response();
    }

    public void startPlanForReview(PlanRequest request,
                                   Sinks.Many<ServerSentEvent<Object>> many,
                                   FluxSink<ServerSentEvent<Object>> bridge,
                                   String threadId) {
        try {
            ssePublisher.publishEvent(bridge, "Planning your execution ...");

            GuardrailResult inputCheck = inputGuardrail.check(request, bridge);
            if (!inputCheck.allowed()) {
                ssePublisher.publishEvent(bridge, Map.of("error", "Input rejected: " + inputCheck.reason()));
                many.tryEmitComplete();
                return;
            }

            PlanGraph.Result result = planGraph.run(request.prompt(), bridge);
            if (!result.valid()) {
                ssePublisher.publishEvent(bridge, Map.of("error", "Planning failed: " + result.reason()));
                many.tryEmitComplete();
                return;
            }

            Plan plan = result.plan();
            sessionStore.put(threadId, new PendingExecution(many, bridge, request, plan, 0));
            publishPlanForReview(bridge, plan, threadId, 0);

        } catch (Exception e) {
            log.error("Unexpected error during planning", e);
            ssePublisher.publishEvent(bridge, Map.of("error", "Unexpected error: " + e.getMessage()));
            many.tryEmitComplete();
            sessionStore.remove(threadId);
        }
    }

    public void processReview(String threadId, ReviewRequest review) {
        PendingExecution session = sessionStore.get(threadId).orElse(null);
        if (session == null) {
            log.warn("No pending session found for threadId={}", threadId);
            ssePublisher.publishEvent(session.bridge(), "No pending session found for threadId=" + threadId);
            return;
        }

        if (review.approved()) {
            sessionStore.remove(threadId);
            try {
                ssePublisher.publishEvent(session.bridge(), "Plan approved. Executing your request ...");
                boolean paginated = session.plan().steps() != null &&
                        session.plan().steps().stream().anyMatch(PlanStep::paginated);
                if (paginated) {
                    graphExecutor.execute(session.plan(), session.bridge());
                }
            } finally {
                session.many().tryEmitComplete();
            }
            return;
        }

        int nextAttempt = session.replanAttempts() + 1;
        if (nextAttempt >= maxReviewAttempts) {
            ssePublisher.publishEvent(session.bridge(), Map.of(
                    "error", "Maximum replan attempts (" + maxReviewAttempts + ") reached. Please start a new request."));
            session.many().tryEmitComplete();
            sessionStore.remove(threadId);
            return;
        }

        try {
            String previousPlanSummary = buildPreviousPlanSummary(session.plan());

            String feedbackPrompt = session.request().prompt()
                    + "\n\nYou previously produced this plan which the user rejected:\n"
                    + previousPlanSummary
                    + "\n\nThe user's feedback on why it was rejected: "
                    + (review.feedback() != null && !review.feedback().isBlank()
                    ? review.feedback()
                    : "No specific feedback provided. Please try a different approach.")
                    + "\nRevise the plan to address the feedback while still satisfying the original request.";

            ssePublisher.publishEvent(session.bridge(), "Replanning based on your feedback ...");

            PlanGraph.Result result = planGraph.run(feedbackPrompt, session.bridge());
            if (!result.valid()) {
                ssePublisher.publishEvent(session.bridge(), Map.of(
                        "error", "Replanning failed: " + result.reason()));
                session.many().tryEmitComplete();
                sessionStore.remove(threadId);
                return;
            }

            Plan newPlan = result.plan();
            sessionStore.put(threadId, new PendingExecution(
                    session.many(), session.bridge(), session.request(), newPlan, nextAttempt));
            publishPlanForReview(session.bridge(), newPlan, threadId, nextAttempt);

        } catch (Exception e) {
            log.error("Unexpected error during replanning", e);
            ssePublisher.publishEvent(session.bridge(), Map.of("error", "Replanning error: " + e.getMessage()));
            session.many().tryEmitComplete();
            sessionStore.remove(threadId);
        }
    }

    private String buildPreviousPlanSummary(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summary: ").append(plan.summary()).append("\n");
        sb.append("Steps:\n");
        plan.steps().stream()
                .filter(s -> !s.isCount())
                .forEach(s -> sb.append("  - ").append(s.rationale()).append("\n"));
        return sb.toString();
    }

    private void publishPlanForReview(FluxSink<ServerSentEvent<Object>> bridge,
                                      Plan plan, String threadId, int attempt) {
        List<Map<String, String>> steps = plan.steps().stream()
                .filter(s -> !s.isCount())
                .map(s -> Map.of("step", String.valueOf(s.stepNumber()), "action", s.rationale()))
                .toList();

        ssePublisher.publishEvent(bridge, Map.of(
                "type", "PLAN_REVIEW",
                "threadId", threadId,
                "summary", plan.summary(),
                "steps", steps,
                "attemptsUsed", attempt,
                "attemptsRemaining", maxReviewAttempts - attempt - 1,
                "instructions", "POST /api/execute/review/" + threadId
                        + " with {\"approved\": true} to execute"
                        + " or {\"approved\": false, \"feedback\": \"your feedback\"} to replan."
        ));
    }

    private PlanOutcome plan(PlanRequest request, FluxSink<ServerSentEvent<Object>> sink) {
        GuardrailResult inputCheck = inputGuardrail.check(request, sink);
        if (!inputCheck.allowed()) {
            log.info("Input guardrail blocked request: {}", inputCheck.reason());
            return new PlanOutcome(PlanResponse.rejected("Input rejected: " + inputCheck.reason()), null);
        }

        PlanGraph.Result result;
        try {
            result = planGraph.run(request.prompt(), sink);
        } catch (Exception e) {
            log.error("Plan graph failed", e);
            return new PlanOutcome(PlanResponse.rejected("Planning failed: " + e.getMessage()), null);
        }

        if (!result.valid()) {
            log.info("Planning failed after {} attempt(s): {}", result.attempts(), result.reason());
            return new PlanOutcome(PlanResponse.rejected(
                    "Generated plan rejected after " + result.attempts() + " attempt(s): " + result.reason()), null);
        }

        return new PlanOutcome(PlanResponse.ok(result.plan(), result.warnings()), result.plan());
    }

    private record PlanOutcome(PlanResponse response, Plan plan) {
    }

    public record ExecutionOutcome(PlanResponse planResponse, ExecutionReport report) {
    }
}
