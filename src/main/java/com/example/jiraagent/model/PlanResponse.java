package com.example.jiraagent.model;

import java.util.List;

/**
 * DTO for holding plan response from LLM
 * @param plan      the produced plan, or null if rejected
 * @param rejected  true if an input or output guardrail blocked processing
 * @param reason    explanation when rejected, or a success note
 * @param warnings  non-fatal notes from the output guardrail (e.g. normalisations)
 */
public record PlanResponse(
        Plan plan,
        boolean rejected,
        String reason,
        List<String> warnings
) {
    public static PlanResponse ok(Plan plan, List<String> warnings) {
        return new PlanResponse(plan, false, "ok", warnings);
    }

    public static PlanResponse rejected(String reason) {
        return new PlanResponse(null, true, reason, List.of());
    }
}
