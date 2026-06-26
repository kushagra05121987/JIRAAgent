# Jira Agent

A natural-language agent that takes a plain-English request — *"find all issues assigned to user1, reassign them to user2 and set status to In Progress"* — and executes it against a Jira-backed SQLite database with real-time streaming progress.

The agent plans, validates, asks for human approval, then executes. Nothing is written to the database until the user explicitly reviews and approves the plan.

---

## Documentation

There are three documents depending on what you need:

| Document | What it covers | Best for                                           |
|---|---|----------------------------------------------------|
| [DESIGN_OVERVIEW.md](DESIGN_OVERVIEW.md) | What the agent does, why it was built this way, and what happens when things go wrong | For anyone getting a first look                    |
| [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md) | Architecture diagrams, component breakdown, design decisions, model choices, thresholds, and the full request flow with class/method references | For engineers working on or extending the codebase |
| [HOW_TO_RUN.md](HOW_TO_RUN.md) | Step-by-step setup, database seeding, all API endpoints with example requests and responses, configuration reference, and production tuning | Anyone running or deploying the agent              |

---

## Quick Start

```bash
export OPENAI_API_KEY=sk-...
export JIRA_DB_PATH=/path/to/jira.db

mvn spring-boot:run
```

---

## How a Request Works

```
POST /api/execute  →  SSE stream opens
        │
        ├─ Safety check (input guardrail)
        ├─ AI creates action plan (GPT-4o)
        ├─ Plan validated (structure + RAGAS faithfulness check)
        ├─ Plan sent to user for review via SSE ← stream pauses here
        │
        │  POST /api/execute/review/{threadId}  {"approved": true}
        │
        └─ Execution: fetch pages → process each record in parallel → stream progress
```

If the user rejects the plan, they send feedback and the agent replans. Up to 3 review cycles before the agent gives up. See [HOW_TO_RUN.md](HOW_TO_RUN.md) for the full walkthrough.

---

## Tech Stack

- **Java 21** / Spring Boot 3.4
- **Spring AI 1.1.7** — LLM integration, structured output, tool callbacks
- **LangGraph4j 1.8.19** — planning graph (with retry) and execution graph (with pagination)
- **OpenAI GPT-4o** — plan generation
- **OpenAI GPT-4o-mini** — RAGAS faithfulness evaluation
- **SQLite** — Jira issue database
- **Reactor / SSE** — real-time progress streaming
