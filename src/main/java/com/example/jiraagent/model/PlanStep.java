package com.example.jiraagent.model;

import java.io.Serializable;
import java.util.Map;

public record PlanStep(
        int stepNumber,
        String tool,
        Map<String, Object> args,
        boolean paginated,
        boolean isCount,
        String rationale
) implements Serializable {
}