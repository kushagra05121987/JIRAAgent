package com.example.jiraagent.guardrail;

import ai.qa.solutions.metrics.retrieval.FaithfulnessMetric;
import ai.qa.solutions.sample.Sample;
import com.example.jiraagent.exec.ToolRegistry;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanResponse;
import com.example.jiraagent.model.PlanStep;
import com.example.jiraagent.service.PlanningOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private final int groundednessCheckThreshold = 1;

    private static int currentCalls;

    public OutputGuardrail(ToolRegistry registry) {
        this.registry = registry;
    }

    public GuardrailResult check(Plan plan, String userPrompt) {
        List<String> warnings = new ArrayList<>();
        currentCalls++;

        if (plan == null) {
            return GuardrailResult.block("No plans found.");
        }

        if (!plan.feasible()) {
            if (plan.steps() != null && !plan.steps().isEmpty()) {
                warnings.add("Plan marked infeasible.");
            }
            return GuardrailResult.pass(warnings);
        }

        if (plan.steps() == null || plan.steps().isEmpty()) {
            return GuardrailResult.block("Plan is marked feasible but contains no steps.");
        }
        if (plan.steps().size() > MAX_STEPS) {
            return GuardrailResult.block("Plan has too many steps (" + plan.steps().size()
                    + "); maximum allowed is " + MAX_STEPS + ".");
        }

        int paginatedCount = 0;
        int paginatedIdx = -1;
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);

            if (step.tool() == null || step.tool().isBlank()) {
                return GuardrailResult.block("Step " + step.stepNumber() + " has no tool name.");
            }
            if (!registry.has(step.tool())) {
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
            return GuardrailResult.block("Plan has " + paginatedCount
                    + " paginated steps; the executor supports at most one.");
        }

        if (paginatedIdx >= 0 && paginatedIdx == plan.steps().size() - 1) {
            warnings.add("Paginated step is last.");
        }

//        Check threshold can be increased based on the use cases to make it less frequent so that token usage is reduced
        if (currentCalls >= groundednessCheckThreshold && currentCalls % groundednessCheckThreshold == 0) {
            GuardrailResult groundedness = this.checkGroundedness(plan, userPrompt);
            if (!groundedness.allowed()) {
                log.info("Groundedness check blocked plan: {}", groundedness.reason());
                return GuardrailResult.block("Groundedness check blocked plan " + groundedness.reason());
            }
        }

        return GuardrailResult.pass(warnings);
    }

    public GuardrailResult checkGroundedness(Plan plan, String userPrompt) {
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

    /**
     * Builds the context list for the RAGAS NLI template.
     * Passing only the user prompt caused legitimate internal steps (COUNT_IDS,
     * pagination) to score 0 because they are not literally mentioned in the prompt.
     * Adding the tool catalog and planner rules as context documents lets RAGAS
     * infer that those steps are valid orchestration, not hallucinations.
     */
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
        sb.append("Plan: ").append(plan.summary()).append("\n");
        sb.append("Steps:\n");
        for (PlanStep step : plan.steps()) {
            String args = step.args() == null ? "" :
                    step.args().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(", "));
            sb.append(step.stepNumber()).append(". ")
                    .append(step.tool())
                    .append("(").append(args).append(")")
                    .append(" — ").append(step.rationale())
                    .append("\n");
        }
        return sb.toString();
    }
}
