package com.example.jiraagent.exec;

import java.io.Serializable;
import java.util.Map;

public record StepResult(
        int stepNumber,
        String tool,
        Map<String, Object> args,
        boolean success,
        Object output,
        String error
) implements Serializable {
    static StepResult ok(int stepNumber, String tool, Map<String, Object> args, Object output) {
        return new StepResult(stepNumber, tool, args, true, output, null);
    }

    static StepResult fail(int stepNumber, String tool, Map<String, Object> args, String error) {
        return new StepResult(stepNumber, tool, args, false, null, error);
    }
}