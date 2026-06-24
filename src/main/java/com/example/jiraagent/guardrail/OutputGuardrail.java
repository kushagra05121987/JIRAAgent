package com.example.jiraagent.guardrail;

import com.example.jiraagent.exec.ToolRegistry;
import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OutputGuardrail {

    private static final int MAX_STEPS = 5;

    private final ToolRegistry registry;

    public OutputGuardrail(ToolRegistry registry) {
        this.registry = registry;
    }

    public GuardrailResult check(Plan plan) {
        List<String> warnings = new ArrayList<>();

        if (plan == null) {
            return GuardrailResult.block("No plans found .");
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

        return GuardrailResult.pass(warnings);
    }
}