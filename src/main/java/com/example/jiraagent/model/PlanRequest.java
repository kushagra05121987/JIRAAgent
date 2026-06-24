package com.example.jiraagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming API request carrying the natural-language prompt.
 */
public record PlanRequest(
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 2000, message = "prompt must be at most 2000 characters")
        String prompt
) {
}
