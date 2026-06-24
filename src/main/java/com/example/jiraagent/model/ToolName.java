package com.example.jiraagent.model;

/**
 * The set of tools the planner is allowed to reference. Keeping this as a
 * closed enum means the LLM cannot invent tool names — anything outside this
 * set is rejected by the output guardrail.
 */
public enum ToolName {
    /** Read Jira ids assigned to a user from the database (supports offset/limit). */
    FETCH_IDS,
    /** Change the status of a single Jira id. */
    SET_STATUS,
    /** Assign a single Jira id to a user. */
    ASSIGN
}
