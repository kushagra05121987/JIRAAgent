package com.example.jiraagent.guardrail;

import ai.qa.solutions.metrics.retrieval.FaithfulnessMetric;
import ai.qa.solutions.sample.Sample;
import com.example.jiraagent.exec.ToolRegistry;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.model.PlanStep;
import com.example.jiraagent.service.PlanningOrchestrator;
import com.example.jiraagent.service.SsePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class OutputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardrail.class);
    private static final int MAX_STEPS = 5;

    private final ToolRegistry registry;

    @Autowired(required = false)
    private FaithfulnessMetric faithfulnessMetric;

    @Value("${agent.guardrail.groundedness.enabled:true}")
    private boolean groundednessEnabled;

    @Value("${agent.guardrail.groundedness.threshold:0.8}")
    private double groundednessThresholdScore;

    @Value("${agent.guardrail.groundedness.check.threshold:2}")
    private final int groundednessCheckThreshold = 3;

    private static int currentCalls;

    private final SsePublisher ssePublisher;

    public OutputGuardrail(ToolRegistry registry, SsePublisher ssePublisher) {
        this.registry = registry;
        this.ssePublisher = ssePublisher;
    }

    public GuardrailResult check(Plan plan, String userPrompt, FluxSink<ServerSentEvent<Object>> sink) {
        List<String> warnings = new ArrayList<>();
        currentCalls++;

        if (plan == null) {
            ssePublisher.publishEvent(sink, "No plans found");
            return GuardrailResult.block("No plans found.");
        }

        if (!plan.feasible()) {
            if (plan.steps() != null && !plan.steps().isEmpty()) {
                warnings.add("Plan marked infeasible.");
            }
            return GuardrailResult.pass(warnings);
        }

        if (plan.steps() == null || plan.steps().isEmpty()) {
            ssePublisher.publishEvent(sink, "Plan is marked feasible but contains no steps");
            return GuardrailResult.block("Plan is marked feasible but contains no steps.");
        }
        if (plan.steps().size() > MAX_STEPS) {
            ssePublisher.publishEvent(sink, "Plan has too many steps (" + plan.steps().size()
                    + "); maximum allowed is " + MAX_STEPS + ".");
            return GuardrailResult.block("Plan has too many steps (" + plan.steps().size()
                    + "); maximum allowed is " + MAX_STEPS + ".");
        }

        int paginatedCount = 0;
        int paginatedIdx = -1;
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);

            if (step.tool() == null || step.tool().isBlank()) {
                ssePublisher.publishEvent(sink, "Step " + step.stepNumber() + " has no tool name.");
                return GuardrailResult.block("Step " + step.stepNumber() + " has no tool name.");
            }
            if (!registry.has(step.tool())) {
                ssePublisher.publishEvent(sink, "Step " + step.stepNumber()
                        + " references unknown tool '" + step.tool() + "'. Known tools: "
                        + registry.names() + ".");
                return GuardrailResult.block("Step " + step.stepNumber()
                        + " references unknown tool '" + step.tool() + "'. Known tools: "
                        + registry.names() + ".");
            }
            if (step.paginated()) {
                paginatedCount++;
                paginatedIdx = i;
            }
        }

        if (paginatedCount > 1) {
            ssePublisher.publishEvent(sink, "Plan has " + paginatedCount
                    + " paginated steps; the executor supports at most one.");
            return GuardrailResult.block("Plan has " + paginatedCount
                    + " paginated steps; the executor supports at most one.");
        }

        if (paginatedIdx >= 0 && paginatedIdx == plan.steps().size() - 1) {
            warnings.add("Paginated step is last.");
        }

//        Check threshold can be increased based on the use cases to make it less frequent so that token usage is reduced
        if (currentCalls >= groundednessCheckThreshold && currentCalls % groundednessCheckThreshold == 0) {
            GuardrailResult groundedness = this.checkGroundedness(plan, userPrompt, sink);
            if (!groundedness.allowed()) {
                log.info("Groundedness check blocked plan: {}", groundedness.reason());
                ssePublisher.publishEvent(sink, "Groundedness check blocked plan " + groundedness.reason());
                return GuardrailResult.block("Groundedness check blocked plan " + groundedness.reason());
            }
        }

        return GuardrailResult.pass(warnings);
    }

    public GuardrailResult checkGroundedness(Plan plan, String userPrompt, FluxSink<ServerSentEvent<Object>> sink) {
        if (!groundednessEnabled) {
            return GuardrailResult.pass();
        }
        if (faithfulnessMetric == null) {
            log.warn("FaithfulnessMetric bean not available — skipping groundedness check");
            return GuardrailResult.pass(List.of("Groundedness check skipped: RAGAS metric not configured."));
        }
        if (plan == null || !plan.feasible() || plan.steps() == null || plan.steps().isEmpty()) {
            return GuardrailResult.pass();
        }

        try {
            Sample sample = Sample.builder()
                    .userInput(userPrompt)
                    .response(serializePlan(plan))
                    .retrievedContexts(buildGroundednessContexts(userPrompt))
                    .build();

            FaithfulnessMetric.FaithfulnessConfig config =
                    FaithfulnessMetric.FaithfulnessConfig.builder().build();

            Double score = faithfulnessMetric.singleTurnScore(config, sample);
            log.info("Groundedness faithfulness score: {}", score);

            if (score == null || score < groundednessThresholdScore) {
                ssePublisher.publishEvent(sink, String.format("Plan failed groundedness check (faithfulness=%.2f, threshold=%.2f). "
                        + "The generated steps may not align with your request.", score, groundednessThresholdScore));
                return GuardrailResult.block(
                        String.format("Plan failed groundedness check (faithfulness=%.2f, threshold=%.2f). "
                                + "The generated steps may not align with your request.", score, groundednessThresholdScore));
            }

            return GuardrailResult.pass(List.of(
                    String.format("Groundedness check passed (faithfulness=%.2f).", score)));

        } catch (Exception e) {
            log.warn("Groundedness check failed with exception: {}", e.getMessage());
            return GuardrailResult.pass(List.of("Groundedness check error: " + e.getMessage()));
        }
    }

    private List<String> buildGroundednessContexts(String userPrompt) {
        String toolContext = "The planner has access to the following tools: "
                + registry.names() + ". "
                + "It may add a COUNT_IDS step before a paginated fetch step to determine "
                + "the total number of records, enabling efficient pagination. "
                + "It may mark a fetch step as paginated to process large result sets in pages. "
                + "These are internal orchestration steps — they are legitimate even if not "
                + "explicitly mentioned in the user's request.";

        return List.of(userPrompt, toolContext);
    }

    private String serializePlan(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.summary()).append("\n");
        sb.append("Actions to be performed:\n");
        for (PlanStep step : plan.steps()) {
            if (step.isCount()) continue;
            sb.append("- ").append(step.rationale()).append("\n");
        }
        return sb.toString();
    }
}
