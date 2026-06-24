package com.example.jiraagent.model;

import java.io.Serializable;
import java.util.List;

public record Plan(
        String summary,
        boolean feasible,
        List<PlanStep> steps
) implements Serializable {
}