# How to Run — Jira Agent

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java | 21+ | Uses virtual threads and records |
| Maven | 3.8+ | Or use the included `./mvnw` wrapper |
| OpenAI API Key | — | Needs access to `gpt-4o` and `gpt-4o-mini` |
| SQLite database | — | A `jira.db` file with a `jira_issues` table (see below) |

---

## 1. Set Environment Variables

```bash
export OPENAI_API_KEY=sk-...
export JIRA_DB_PATH=/absolute/path/to/jira.db   # omit to use ./jira.db in the working directory
```

---

## 2. Database Setup

The agent expects a SQLite database with the following table:

```sql
CREATE TABLE jira_issues (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    issue_key   TEXT NOT NULL UNIQUE,
    assignee    TEXT NOT NULL,
    status      TEXT NOT NULL,
    description TEXT
);
```

A seeded database with 10,000 records (500 assigned to `user1`) can be generated with:

```bash
python3 - <<'EOF'
import sqlite3, random

db = sqlite3.connect("jira.db")
cur = db.cursor()
cur.executescript("""
  DROP TABLE IF EXISTS jira_issues;
  CREATE TABLE jira_issues (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      issue_key TEXT NOT NULL UNIQUE,
      assignee TEXT NOT NULL,
      status TEXT NOT NULL,
      description TEXT
  );
""")

users    = ["user2", "user3", "user4", "user5"]
statuses = ["To Do", "In Progress", "In Review", "Done", "Blocked"]
projects = ["PROJ", "INFRA", "DATA", "API", "FE"]
user1_idx = set(random.sample(range(10000), 500))

rows = []
for i in range(10000):
    proj     = projects[i % len(projects)]
    key      = f"{proj}-{i+1}"
    assignee = "user1" if i in user1_idx else random.choice(users)
    status   = random.choice(statuses)
    rows.append((key, assignee, status, f"Issue {key}: automated task #{i+1}"))

cur.executemany(
    "INSERT INTO jira_issues (issue_key, assignee, status, description) VALUES (?,?,?,?)",
    rows
)
db.commit()
print(f"Created {cur.execute('SELECT COUNT(*) FROM jira_issues').fetchone()[0]} rows")
print(f"user1 rows: {cur.execute('SELECT COUNT(*) FROM jira_issues WHERE assignee=?', ('user1',)).fetchone()[0]}")
db.close()
EOF
```

---

## 3. Start the Application

```bash
mvn spring-boot:run
```

The server starts on port `8080`. You should see:

```
Started JiraAgentApplication in X.XXX seconds
```

---

## 4. API Endpoints

### Plan only — see what the agent would do without executing

Returns the structured action plan the AI created from your prompt. Nothing is read from or written to the database.

```bash
curl -X POST http://localhost:8080/api/plan \
  -H "Content-Type: application/json" \
  -d '{"prompt": "find issues assigned to user1 and set status to Done"}'
```

**Example response:**
```json
{
  "plan": {
    "summary": "Count and fetch issues assigned to user1, then set each to Done.",
    "feasible": true,
    "steps": [
      { "stepNumber": 1, "tool": "COUNT_IDS", "args": { "user": "user1" }, "isCount": true },
      { "stepNumber": 2, "tool": "FETCH_IDS", "args": { "user": "user1" }, "paginated": true },
      { "stepNumber": 3, "tool": "SET_STATUS", "args": { "id": "$item.issue_key", "status": "Done" } }
    ]
  },
  "rejected": false,
  "warnings": []
}
```

---

### Execute — plan, review, then run with real-time progress streaming

The execute flow is a two-step process: the agent first plans and asks for your approval, then executes only after you confirm. The SSE connection stays open throughout both steps.

**Step 1 — Open the SSE stream and receive the plan for review**

In Postman (or curl with `--no-buffer`), open the SSE connection:

```bash
curl -X POST http://localhost:8080/api/execute \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  --no-buffer \
  -d '{"prompt": "find issues assigned to user1, assign to user2, set In Progress"}'
```

The stream opens immediately and you receive a planning event followed by a review request:

```
data: {"message": "Planning your execution ..."}

data: {
  "type": "PLAN_REVIEW",
  "threadId": "a3f8c2d1-...",
  "summary": "Fetch issues assigned to user1, reassign to user2, set status to In Progress.",
  "steps": [
    { "step": "2", "action": "Fetch Jira issues assigned to user1 for processing." },
    { "step": "3", "action": "Assign each fetched issue to user2." },
    { "step": "4", "action": "Set the status of each issue to In Progress." }
  ],
  "attemptsUsed": 0,
  "attemptsRemaining": 2,
  "instructions": "POST /api/execute/review/a3f8c2d1-... with {\"approved\": true} to execute or {\"approved\": false, \"feedback\": \"your feedback\"} to replan."
}
```

**The stream stays open here — nothing has been written to the database yet.**

---

**Step 2a — Approve the plan (in a second Postman tab)**

```bash
curl -X POST http://localhost:8080/api/execute/review/a3f8c2d1-... \
  -H "Content-Type: application/json" \
  -d '{"approved": true}'
```

Response:
```json
{ "threadId": "a3f8c2d1-...", "message": "Approval received. Execution started — watch the SSE stream for progress." }
```

Back in the original SSE stream:
```
data: {"message": "Plan approved. Executing your request ..."}

data: {"message": "Processing PROJ-15"}

data: {"message": "Processing INFRA-132"}

data: {"message": "Processing API-164"}

... (one event per record, stream closes when done)
```

---

**Step 2b — Reject and request a revised plan**

If the plan doesn't look right, send feedback instead:

```bash
curl -X POST http://localhost:8080/api/execute/review/a3f8c2d1-... \
  -H "Content-Type: application/json" \
  -d '{"approved": false, "feedback": "also set priority to High"}'
```

Back in the SSE stream, the agent replans using your feedback alongside the previous rejected plan as context, then sends a new `PLAN_REVIEW` event. You can reject up to **3 times total** before the agent stops and asks you to start a new request.

---

**Step 2c — Retries exhausted**

If you reject the plan 3 times:
```
data: {"error": "Maximum replan attempts (3) reached. Please start a new request."}
```

The stream closes. Start a new `/api/execute` request with a clearer prompt.

---

## 5. Configuration Reference

All settings live in `src/main/resources/application.properties`.

| Property | Default | What it controls |
|---|---|---|
| `spring.ai.openai.chat.options.model` | `gpt-4o` | Model used for planning. Higher-capability models produce fewer invalid plans and fewer retries. |
| `agent.planner.max-attempts` | `2` | How many times the AI retries if its plan is rejected by the automated guardrail. |
| `agent.planner.max-review-attempts` | `3` | How many times the user can reject the plan and request a replan before the agent gives up. |
| `agent.executor.page-size` | `1` | Records fetched per page. Set to `1` for demo visibility. Raise to `50–100` in production. |
| `agent.executor.maxIterations` | `10000` | LangGraph4j graph step limit. Formula: `(max_records / page_size) × 6 + buffer`. |
| `agent.executor.maxRetryProcessItems` | `2` | How many times a failed page of records is retried before the agent gives up. |
| `agent.guardrail.groundedness.enabled` | `true` | Toggle the RAGAS faithfulness check on the generated plan. |
| `agent.guardrail.groundedness.threshold` | `0.8` | Minimum faithfulness score for the plan to be accepted. |
| `agent.guardrail.groundedness.check.threshold` | `3` | Run the RAGAS check every Nth validation to control token cost. |
| `spring.ai.ragas.providers.openai-compatible[0].chat-models[0].id` | `gpt-4o-mini` | Model used by RAGAS for plan evaluation. Can be cheaper than the planning model. |

---

## 6. Adding a New Domain

The agent is not Jira-specific. To point it at a different system (GitHub issues, Slack, a CRM, etc.):

1. Create a class implementing `ToolProvider`
2. Annotate each operation with `@Tool` and each parameter with `@ToolParam`
3. Register it as a Spring `@Component`

```java
@Component
public class GitHubTools implements ToolProvider {

    @Tool(name = "FETCH_IDS", description = "Fetch open pull requests assigned to a user.")
    public List<Map<String, Object>> fetchIds(
            @ToolParam(description = "GitHub username") String user,
            @ToolParam(description = "page offset") int offset,
            @ToolParam(description = "page size") int limit) {
        // call GitHub API
    }

    @Tool(name = "ASSIGN", description = "Assign a pull request to a user.")
    public String assign(
            @ToolParam(description = "pull request number") String id,
            @ToolParam(description = "GitHub username to assign to") String assignee) {
        // call GitHub API
    }
}
```

The planner, executor, output guardrail, and tool registry all pick up the new tools automatically. No other code changes are needed.

---

## 7. Production Tuning

| Concern | Setting | Recommended value |
|---|---|---|
| Throughput | `agent.executor.page-size` | `50–100` |
| Token cost (RAGAS) | `agent.guardrail.groundedness.check.threshold` | `5–10` |
| Large datasets | `agent.executor.maxIterations` | `(total_records / page_size) × 6 + 1000` |
| Stability | `agent.executor.maxRetryProcessItems` | `3` for flaky external APIs |
