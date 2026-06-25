# Jira Agent — Technical Design Document

## What This Is

A natural-language agent that accepts free-form user requests (e.g. *"find all issues assigned to user1, reassign them to user2 and set status to In Progress"*) and executes them against a Jira-backed SQLite database. The goal was to build something that generalises — the agent doesn't hardcode "Jira logic". it works for any domain where you register tools and the rest of the pipeline handles orchestration automatically.

---

## Architecture Overview

```
 User types a request                           PlanningController.execute()
 in natural language
        │
        ▼
 ┌──────────────────────┐
 │   Safety Check       │                       InputGuardrail.check()
 │                      │
 │  Is the request      │  blocks: injections, empty input,
 │  safe and valid?     │          oversized prompts
 └──────────┬───────────┘
            │ safe
            ▼
 ┌──────────────────────┐
 │   Planning           │                       PlanGraph (LangGraph4j)
 │                      │
 │  AI understands the  │  ┌─ AI reads request + tool schemas (what each tool does,
 │  request and creates │  │  what arguments it needs, what it returns)
 │  a step-by-step plan │  │  Produces a plan: ordered list of tool calls with
 │  with exact tool     │  │  exact tool names, arguments, and how outputs from
 │  calls to execute    │  │  one step feed into the next (e.g. $item.issue_key)
 │                      │  │                    PlanNode / PlannerService.plan()
 │                      │  ├─ Plan reviewed for safety and accuracy
 │                      │  │                    ValidateNode / OutputGuardrail.check()
 │                      │  │
 │                      │  └─ Retry if rejected (up to 2 times)
 │                      │     Error event if still failing
 │                      │                       ErrorNodePlanGraph
 └──────────┬───────────┘
            │ plan passes automated checks
            ▼
 ┌──────────────────────┐
 │   Human Review       │                       PlanningOrchestrator.startPlanForReview()
 │                      │                       ExecutionSessionStore
 │  Plain-English plan  │  ┌─ SSE event: plan summary + step rationales sent to user
 │  sent to user before │  │  (no technical details, count step excluded)
 │  any data is touched │  │
 │                      │  ├─ SSE stream stays open, execution paused
 │                      │  │
 │                      │  ├─ User approves → execution proceeds
 │                      │  │                    POST /api/execute/review/{threadId}
 │                      │  │                    PlanningOrchestrator.processReview()
 │                      │  │
 │                      │  └─ User rejects with feedback → replan and re-send
 │                      │     Up to 3 total attempts before giving up
 └──────────┬───────────┘
            │ user approved
            ▼
 ┌──────────────────────┐
 │   Execution          │                       GraphPlanExecutor (LangGraph4j)
 │                      │
 │  Works through every │  ┌─ Count total matching records
 │  matching record,    │  │                    JiraTools.countIds()
 │  one page at a time  │  │
 │                      │  ├─ Fetch a page of records from the database
 │                      │  │                    FetchPageNode / JiraTools.fetchIds()
 │                      │  │
 │                      │  ├─ Apply each action to every record (in parallel)
 │                      │  │                    ProcessItemsNode
 │                      │  │                    JiraTools.assign() / setStatus()
 │                      │  │
 │                      │  ├─ Retry the page if anything failed
 │                      │  │
 │                      │  └─ Error event if retries exhausted
 │                      │                       ErrorNodeExecutionGraph
 └──────────┬───────────┘
            │
            ▼
 Progress streamed live                         SsePublisher.publishEvent()
 to the caller as each                          Sinks.Many / PlanningController
 record is processed
```

There are two separate LangGraph4j graphs — one for planning with retry, one for execution with pagination. Keeping them separate was intentional: planning and execution have different control structures, different failure modes, and different state shapes. Merging them into one graph would have made the retry/pagination logic tangled and harder to reason about.

Beyond structure, there is a deliberate cost decision here — the planner produces a complete, tool-resolved execution plan in a single LLM call. The executor then follows that plan mechanically, with no model involvement per item. This avoids the alternative pattern where the LLM decides which tool to call on every record, which would multiply token costs by the number of items processed.

---

## Key Design Decisions

### 1. One LLM call. Zero LLM calls during execution.

The LLM is invoked exactly once per request — to produce a plan. After that, execution is entirely Java code and LangGraph. The alternative (letting the LLM drive tool calls in a loop) would have meant N×M model round-trips for N items and M operations each, which is both expensive and non-deterministic. You cannot audit or guarantee what a model-driven execution loop will do to production data.

### 2. Plan-then-Execute over ReAct

ReAct agents interleave reasoning and tool calls. That works well for exploratory tasks but poorly for bulk mutations — one bad LLM decision mid-execution can leave records in a partial state. The plan-then-execute pattern separates concerns cleanly: the LLM reasons once upfront, a guardrail validates the plan before anything is touched, and the executor applies it deterministically.

### 3. `$item` and `$prev` references

The LLM cannot know at plan time which specific issue keys will be fetched — the database hasn't been queried yet. Rather than re-invoking the LLM per item (expensive) or hardcoding values (impossible), the plan uses deferred references from tool specifications: `$item.issue_key` is resolved by the executor at runtime for each record. This keeps the plan static and auditable while remaining data-driven.

### 4. Paginated step + COUNT_IDS pattern

The executor owns pagination — the LLM is not allowed to set offset or limit. Instead, it marks a step as `paginated: true` and emits a `COUNT_IDS` step before it. The executor uses the total count to know when to stop iterating. This prevents the model from under-fetching (stopping early) or infinite-looping (never terminating), both of which are real failure modes when an LLM controls a pagination loop.

### 5. Per-item parallelism with retry, not batch mutations

Items within a page are processed in parallel. Each item is an independent of others. One failure does not abort the rest. If any item in a page fails, the entire page is retried up to `maxRetryProcessItems` times. If retries are exhausted, the `ErrorNodeExecutionGraph` node emits an SSE error event and the graph terminates cleanly rather than silently dropping failures.

### 6. Human review gate before execution — with replan loop

Automated guardrails catch structural and faithfulness problems, but they can't catch intent mismatches that look technically valid — e.g. the user said "archive" but the LLM planned "delete". To guard against this, the agent pauses after planning and sends the user a plain-English summary of what it intends to do (step rationales only — no tool names, no args, count step excluded). No records are touched until the user explicitly approves.

If the user disagrees, they send feedback and the agent replans with that feedback appended to the original prompt. This loop runs up to `max-review-attempts=3` times. The key implementation detail: the SSE stream from the original `/api/execute` request stays open throughout — the `Sinks.Many` and `FluxSink` bridge are stored in `ExecutionSessionStore` keyed by `threadId`, and the review endpoint (`POST /api/execute/review/{threadId}`) writes back into that same open stream. The user's Postman connection from the first call sees the replanned result without opening a new connection.

---

## Components

### `PlannerService`
Sends the user's prompt to GPT-4o with the tool catalog attached as native OpenAI function definitions via `.toolCallbacks()`. `toolChoice: "none"` is set so the model sees the function schemas for awareness but cannot call them, planning is the LLM's only job. Returns a structured `Plan` object via Spring AI's `.entity(Plan.class)`.

Post-processes the plan to strip OpenAI internal tool prefixes: `functions.` is removed from all tool names, and steps referencing `multi_tool_use.parallel` (an internal OpenAI batching pseudo-tool) are filtered out entirely. Both normalisations run every time because the model doesn't always follow formatting instructions precisely.

### `PlanGraph` (LangGraph4j)
Three-node graph: `PlanNode` → `ValidateNode` → conditional. A retry edge loops back to `PlanNode` up to `max-attempts=2` times, passing the rejection reason so the model can self-correct. If attempts are exhausted, the graph routes to `ErrorNodePlanGraph` which emits an SSE event before terminating.

### `InputGuardrail`
Runs before the LLM. Rejects empty prompts, prompts over 2000 characters, and common prompt-injection patterns. This is intentionally rule-based and cheap — no LLM call. The goal is to fail fast on obvious misuse before spending tokens.

### `OutputGuardrail`
Runs after the LLM produces a plan. Two layers:
1. **Structural** — verifies step count (≤5), all tool names exist in the registry, exactly one paginated step, paginated step isn't last.
2. **Groundedness** (RAGAS `FaithfulnessMetric`) — checks that the plan's actions are grounded in the user's original request. Runs every N calls (configurable via `groundednessCheckThreshold`) to reduce token spend. Context passed to RAGAS includes both the user prompt and a tool-catalog description so the NLI model can correctly judge that pagination steps are legitimate.

### `GraphPlanExecutor` (LangGraph4j)
Four-node graph: `fetch_page` → `process_items` → conditional → back to `fetch_page` or `error`. The `recursionLimit` is set at compile time. The limit needs to account for the fact that LangGraph4j counts both node executions and edge traversals, meaning each item consumes roughly 4–6 counts. For 500 items the minimum is ~3000; `maxIterations=10000` gives safe headroom.

### `FetchPageNode`
Calls `FETCH_IDS` with executor-injected `offset` and `limit`. The LLM is not allowed to set these — they would be stale at plan time and would lock the executor into a fixed page position. The node writes the fetched items into `currentItems` in graph state.

### `ProcessItemsNode`
Processes all items in the current page in parallel using threads. Inspects `StepResult` objects to detect failures. Failure detection drives the retry flag in state. Attempt count is tracked in state (not as a static field) so concurrent requests don't corrupt each other's retry counts.

### `ErrorNodeExecutionGraph` / `ErrorNodePlanGraph`
Terminal nodes reached when retries are exhausted. Each emits a descriptive SSE error event to the client and returns empty map, so LangGraph4j can cleanly merge the (empty) state update. Both are separate classes because they operate on different state types (`JiraAgentState` vs `PlanGraphState`).

### `ExecutionSessionStore`
A `ConcurrentHashMap` keyed by `threadId` that holds `PendingExecution` records — each containing the `Sinks.Many`, the `FluxSink` bridge, the original request, the current plan, and the replan attempt count. This is what keeps the SSE stream alive between the planning step and the user's review response. Sessions are removed when the user approves and execution completes, or when retries are exhausted.

### `PlanningOrchestrator` — human review methods
Two new methods sit alongside the existing planning logic:
- `startPlanForReview()` — runs input guardrail and plan graph, then publishes a review event (plan summary + step rationales only, count step excluded) and parks the session in `ExecutionSessionStore`. The SSE stream stays open.
- `processReview()` — called when the review endpoint receives a response. If approved, runs execution and closes the stream. If rejected and attempts remain, replans with the user's feedback appended to the original prompt and sends a new review event. If attempts exhausted, sends an error event and closes the stream.

### `SsePublisher` + `PlanningController`
Events stream over SSE. Each request creates a fresh `Sinks.Many` and a `FluxSink` bridge built inline — both referencing the same instance, no Spring bean scope involved. Work starts inside `doOnSubscribe` so execution only begins after the client has subscribed.

A second endpoint, `POST /api/execute/review/{threadId}`, accepts `{ "approved": true }` or `{ "approved": false, "feedback": "..." }`. It returns a `200` immediately and schedules `processReview()` on a `boundedElastic` thread, which writes back into the still-open SSE stream of the original `/api/execute` call.

### `ToolRegistry`
Auto-discovers all Spring beans implementing `ToolProvider` and builds `ToolCallback` objects from `@Tool`-annotated methods via `MethodToolCallbackProvider`. Exposes `callbacks()` for the planner (tool spec awareness) and `find(name)` for the executor (tool invocation). Adding a new domain means writing a new `ToolProvider` — nothing else changes.

---

## Model Choices

| Model | Used For | Reason |
|---|---|---|
| `gpt-4o` | Planning | More reliable structured output adherence and multi-rule system prompt following than `gpt-4o-mini`. Reduces retry rate. One LLM call per request means the quality premium is worth it. |
| `gpt-4o-mini` | RAGAS evaluation | Faithfulness evaluation is binary NLI classification — high reasoning capability is unnecessary. Significantly cheaper and fast enough for statement verdicts. |

Temperature `0.0` everywhere. This is an agent which produces factual and structured data where there is no room for deviation and output variance.

---

## Thresholds

| Parameter | Value | Reason |
|---|---|---|
| `groundedness.threshold` | `0.8` | Valid plans grounded in the user's request consistently score 0.9–1.0. Threshold at 0.8 catches hallucinated steps (e.g. DELETE not in the prompt) while absorbing minor LLM evaluator variance. |
| `groundednessCheckThreshold` | `3` | Run RAGAS check every 3rd validation. Reduces evaluation token cost on repeated or rapid requests. Set to 1 to check every request. |
| `planner.max-attempts` | `2` | One retry self-corrects most structural failures (unknown tool name, missing arg, `multi_tool_use.parallel` contamination). More than 2 adds latency without meaningfully different outcomes. |
| `maxRetryProcessItems` | `2` | Page-level retries for transient tool failures. Each retry re-runs the entire page in parallel; 2 attempts covers transient DB or network errors without masking persistent failures. |
| `executor.page-size` | `1` | Set to 1 for demonstration — each record is fetched and processed individually, making SSE progress visible per item. |
| `maxIterations` | `10000` | LangGraph4j counts both node executions and edge traversals per graph step, so each processed item consumes ~4–6 counts. 500 items × 6 = 3000 minimum; 10000 gives 3× headroom for retries and overhead. |
| `MAX_STEPS` | `5` | Current tool set supports at most: COUNT_IDS + FETCH_IDS + 3 mutations. Caps blast radius of an overly creative LLM plan. |
| `planner.max-review-attempts` | `3` | How many times the user can reject and request a replan before the agent gives up. Each rejection triggers a full replan with the user's feedback appended to the original prompt. |

---

## Tool Choice: LangGraph4j

LangGraph4j was chosen over a plain `while` loop or Spring Batch for three reasons.

**Explicit state and conditional routing.** The planning graph with retry and error handling requires state that survives across node executions and routing decisions that depend on that state. A plain loop can technically do this but requires ad-hoc variables and nested conditionals — it gets messy fast. LangGraph4j makes the control flow a first-class declaration.

**Persistent state, conversation history, and thread resumption.** Every graph run is backed by a `MemorySaver` checkpoint store keyed by a `threadId`. Every node execution is checkpointed — the full state at that point, including which records were processed, retry counts, and accumulated results, is saved. A user can pass the same `threadId` in a future request and resume execution exactly where it left off, without reprocessing records already handled. This also means the entire interaction history for a given thread is stored and retrievable, useful for auditing, debugging, or building a UI that shows past runs. None of this is practical with a raw loop.

**Mid-execution interruption and human approval.** LangGraph4j supports pausing a graph at a defined node and waiting for external input before continuing. In this agent, that means you can interrupt after the plan is created but before any records are touched, surface the plan to the user for review, and only proceed once they confirm. The graph resumes from the checkpoint with no state loss.



---

## Streaming

SSE was chosen over polling or webhooks because the caller needs incremental feedback — processing 500 records at 1 per second takes several minutes. Without streaming, the HTTP connection either times out or the user has no idea if anything is happening.

The implementation uses `Sinks.Many` (hot publisher) rather than `Flux.create`. `Flux.create` uses a pull model — Netty polls for events and batches them until the blocking graph execution finishes. `Sinks.Many.tryEmitNext()` is a push signal that reaches Netty immediately.

Work starts inside `doOnSubscribe` is returned. `doOnSubscribe` guarantees the subscriber is attached before the first event is emitted.

---

## Request Flow (End to End)

```
User sends:
"find all issues assigned to user1,
 reassign to user2, set status to In Progress"
    │
    │  PlanningController.execute()
    │  Connection opened immediately, events start flowing
    ▼
┌─────────────────────────────────────────────────┐
│  Is this request safe to process?               │
│                                                 │  InputGuardrail.check()
│  Checks for empty input, suspicious phrasing,  │
│  or attempts to override the agent's behaviour  │
└────────────────────────┬────────────────────────┘
                         │ safe to continue
                         ▼
┌─────────────────────────────────────────────────┐
│  What needs to be done?                         │
│                                                 │  PlanGraph.run()
│  AI reads the request and the available tools,  │  └─ PlanNode / PlannerService.plan()
│  then writes a step-by-step action plan.        │
│  Each step contains the exact tool to call,     │
│  the arguments to pass, and how data flows      │
│  from one step to the next.                     │
│                                                 │
│  e.g. Step 1: COUNT_IDS(user=user1)             │
│       Step 2: FETCH_IDS(user=user1) [paginated] │
│       Step 3: ASSIGN(id=$item.issue_key,        │
│                       assignee=user2)           │
│       Step 4: SET_STATUS(id=$item.issue_key,    │
│                          status=In Progress)    │
│                                                 │
│  Plan reviewed before anything is touched:      │  ValidateNode / OutputGuardrail.check()
│  • Are all steps using real, registered tools?  │
│  • Does the plan match what the user asked for? │  (RAGAS faithfulness check)
│                                                 │
│  If the plan is rejected, AI tries again.       │  PlanNode retry
│  After too many failures → error sent to user   │  ErrorNodePlanGraph
└────────────────────────┬────────────────────────┘
                         │ automated checks passed
                         ▼
┌─────────────────────────────────────────────────┐
│  Human Review — does this look right to you?    │
│                                                 │  PlanningOrchestrator.startPlanForReview()
│  Plain-English plan sent via SSE:               │  ExecutionSessionStore.put(threadId, session)
│    Summary: fetch user1 issues, assign to       │
│             user2, set In Progress              │
│    Step 1: Fetch issues assigned to user1       │
│    Step 2: Assign each issue to user2           │
│    Step 3: Set status to In Progress            │
│                                                 │
│  SSE stream stays open. Nothing written yet.    │
│                                                 │
│  ── User calls POST /api/execute/review/{id} ── │  PlanningOrchestrator.processReview()
│                                                 │
│  If approved → proceed to execution             │
│  If rejected → replan with feedback, re-send    │
│  After 3 rejections → error, stream closes      │
└────────────────────────┬────────────────────────┘
                         │ user approved
                         ▼
┌─────────────────────────────────────────────────┐
│  How many records are we working with?          │
│                                                 │  JiraTools.countIds()
│  Counts user1's issues in the database          │
│  so the agent knows when it's done              │  → 500 records found
└────────────────────────┬────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────┐
│  Work through the records, one page at a time   │
│                                                 │  GraphPlanExecutor / FetchPageNode
│  Fetch a page of records from the database      │  └─ JiraTools.fetchIds()
│                                                 │
│  For every record in the page (in parallel):    │  ProcessItemsNode
│    • Notify user: "Processing PROJ-123"         │  └─ SsePublisher.publishEvent()
│    • Reassign the record to user2               │  └─ JiraTools.assign()
│    • Update its status to In Progress           │  └─ JiraTools.setStatus()
│                                                 │
│  If a page fails → retry the page              │  ProcessItemsNode retry logic
│  Too many failures → error sent to user         │  ErrorNodeExecutionGraph
│                                                 │
│  Move to next page, repeat until all done       │  FetchPageNode (next offset)
└────────────────────────┬────────────────────────┘
                         │ all pages complete
                         ▼
              Stream closes, user sees
              full results in their client
              many.tryEmitComplete()
```

---

## How to Run

See [HOW_TO_RUN.md](HOW_TO_RUN.md) for full setup instructions, API examples, configuration reference, and production tuning guidance.
