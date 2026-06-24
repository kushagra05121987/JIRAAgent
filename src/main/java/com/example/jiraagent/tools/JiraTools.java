package com.example.jiraagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Real SQLite-backed tools over the jira_issues table.
 *
 * All methods are stateless (they only use the thread-safe JdbcTemplate),
 * so they are safe to call concurrently from the executor's worker threads.
 *
 * Implementing ToolProvider causes auto-registration in the ToolRegistry.
 */
@Component
public class JiraTools implements ToolProvider {

    private final JdbcTemplate jdbc;

    public JiraTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(name = "COUNT_IDS",
            description = "Count the total Jira issues assigned to a user. Used to bound "
                    + "pagination before fetching. Takes the same 'user' argument as FETCH_IDS.")
    public long countIds(
            @ToolParam(description = "username whose issues to count") String user) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jira_issues WHERE assignee = ?",
                Long.class, user);
        return total == null ? 0L : total;
    }

    @Tool(name = "FETCH_IDS",
            description = "Fetch Jira issues assigned to a user, one page at a time. "
                    + "Returns a list of objects, each with issue_key, status and description. "
                    + "Pagination is controlled by offset and limit.")
    public List<Map<String, Object>> fetchIds(
            @ToolParam(description = "username whose issues to fetch") String user,
            @ToolParam(description = "page offset (injected by executor)") int offset,
            @ToolParam(description = "page size (injected by executor)") int limit) {
        return jdbc.queryForList(
                "SELECT issue_key, status, description FROM jira_issues "
                        + "WHERE assignee = ? ORDER BY id LIMIT ? OFFSET ?",
                user, limit, offset);
    }

    @Tool(name = "SET_STATUS",
            description = "Set the status of a single Jira issue by its issue_key.")
    public String setStatus(
            @ToolParam(description = "the issue key, e.g. PROJ-123") String id,
            @ToolParam(description = "target status") String status) {
        int updated = jdbc.update(
                "UPDATE jira_issues SET status = ? WHERE issue_key = ? AND status <> ?",
                status, id, status);
        boolean changed = updated > 0;
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"changed\":" + changed + "}";
    }

    @Tool(name = "ASSIGN",
            description = "Assign a single Jira issue to a user by its issue_key.")
    public String assign(
            @ToolParam(description = "the issue key, e.g. PROJ-123") String id,
            @ToolParam(description = "username to assign to") String assignee) {
        int updated = jdbc.update(
                "UPDATE jira_issues SET assignee = ? WHERE issue_key = ? AND assignee <> ?",
                assignee, id, assignee);
        boolean changed = updated > 0;
        return "{\"id\":\"" + id + "\",\"assignee\":\"" + assignee + "\",\"changed\":" + changed + "}";
    }
}