# Jira Planning Agent — Phase 1 (planning + guardrails)

A Spring Boot + Spring AI service that takes a natural-language prompt and uses a
small OpenAI model to turn it into a **structured, validated plan** of tool-execution
requests. This is the first feature only: it **plans**, it does not execute.

## Pipeline

```
POST /api/v1/plan
        │
        ▼
  InputGuardrail        (reject empty/oversized, injection attempts, off-topic)
        │
        ▼
  PlannerService        (OpenAI, structured output -> Plan record)
        │
        ▼
  OutputGuardrail       (plan well-formed, known tools, valid args, starts with FETCH_IDS)
        │
        ▼
  PlanResponse
```

The LLM only resolves the tool for each step at **plan time** and returns the
ordered steps. Execution is deliberately a separate, later concern.

## Tools the planner knows about

| Tool        | args                       | notes                                  |
|-------------|----------------------------|----------------------------------------|
| FETCH_IDS   | `{ "user": "..." }`        | must be step 1; reads ids from the DB  |
| SET_STATUS  | `{ "status": "..." }`      | single id; statuses validated          |
| ASSIGN      | `{ "assignee": "..." }`    | single id                              |

Each mutation describes a **single-id** operation. Iteration over the fetched ids
is the executor's job (next phase) — the plan does not batch.

## Run

```bash
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

## Example

```bash
curl -s -X POST http://localhost:8080/api/v1/plan \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Find the jira ids assigned to user1, set them to In Progress and assign them to user2"}' | jq
```

Expected (shape):

```json
{
  "plan": {
    "sourceUser": "user1",
    "summary": "Fetch user1's issues, set each to In Progress, reassign to user2.",
    "steps": [
      { "stepNumber": 1, "tool": "FETCH_IDS",  "args": { "user": "user1" },        "rationale": "..." },
      { "stepNumber": 2, "tool": "SET_STATUS", "args": { "status": "In Progress" }, "rationale": "..." },
      { "stepNumber": 3, "tool": "ASSIGN",     "args": { "assignee": "user2" },     "rationale": "..." }
    ]
  },
  "rejected": false,
  "reason": "ok",
  "warnings": []
}
```

Guardrail rejection returns HTTP 422 with `rejected: true` and a reason.

## What's intentionally NOT here yet

- The actual MCP tools / DB + Jira clients (the planner references tools by name).
- The executor and its per-id loop with pagination and idempotency.
- A human-confirmation gate before mutations.

These slot in after the plan is produced and validated.

## Notes on Spring AI version

Built against Spring AI `1.1.1` using `spring-ai-starter-model-openai` and
`ChatClient.entity(...)` for structured output. Pin the version in `pom.xml`;
Spring AI's API has changed across recent milestones.
```
