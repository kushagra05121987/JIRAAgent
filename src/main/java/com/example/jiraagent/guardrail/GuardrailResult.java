package com.example.jiraagent.guardrail;

import java.util.List;

/**
 * Outcome of a guardrail check.
 *
 * @param allowed  whether processing may continue
 * @param reason   why it was blocked (when not allowed)
 * @param warnings non-fatal observations to surface to the caller
 */
public record GuardrailResult(boolean allowed, String reason, List<String> warnings) {

    public static GuardrailResult pass() {
        return new GuardrailResult(true, null, List.of());
    }

    public static GuardrailResult pass(List<String> warnings) {
        return new GuardrailResult(true, null, warnings);
    }

    public static GuardrailResult block(String reason) {
        return new GuardrailResult(false, reason, List.of());
    }
}
