package com.example.jiraagent.exec;

import java.util.List;

public record ExecutionReport(
        boolean success,
        String message,
        List<StepResult> results
) {
}