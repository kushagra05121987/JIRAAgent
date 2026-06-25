# Jira Agent — Design Overview

## What Problem Does This Solve?

Managing Jira issues in bulk is painful. If you want to reassign 500 tickets from one person to another and change their status, you're either doing it by hand one-by-one, writing a custom script, or navigating a clunky bulk-edit UI.

This agent lets you describe what you want in plain English — *"find all issues assigned to user1, reassign them to user2, and set them to In Progress"* — and it figures out the rest. It reads your request, creates a safe, reviewable action plan, and executes it against the database while streaming live progress back to you.

It's not a chatbot. It's an action agent — it takes natural language and turns it into real changes on real data.

---

## How It Works — The Big Picture

There are four phases every request goes through.

### Phase 1 — Safety Check

Before anything else, the request is checked for obvious problems: is it empty, is it suspiciously long, does it look like someone trying to manipulate the AI's instructions? If any of these checks fail, the request is rejected immediately with a clear reason. No AI call is made, no tokens spent.

### Phase 2 — Planning

The request goes to the AI (GPT-4o). The AI has been given a description of every available tool — what it does, what inputs it needs, and what it returns. It uses this to write a step-by-step action plan.

The plan is concrete. It's not "find the issues and update them." It's:

- Step 1: Count total issues assigned to user1
- Step 2: Fetch them in batches (100 at a time)
- Step 3: For each issue — reassign to user2
- Step 4: For each issue — set status to In Progress

Crucially, the AI writes the plan once and hands it over. It does not make decisions during execution. This matters because AI decisions are unpredictable — if the AI was involved for every single record, you'd have no way to guarantee it wouldn't do something unexpected on record number 347.

Once the plan is written, it goes through two automated checks:

**Does it make structural sense?** Are all the steps using tools that actually exist? Is the step count reasonable? Is the plan ordered correctly?

**Does it match what the user actually asked for?** This uses a technique called RAGAS faithfulness evaluation — a second AI call that reads the plan and the original request and scores how well they match. If the plan includes actions the user never asked for (say, deleting records), this catches it.

If the plan fails either check, the AI gets to try again — up to two attempts — before the request is rejected.

### Phase 3 — Human Review

Before a single record is touched, the agent pauses and sends the plan back to the user in plain English — just the action descriptions, no technical details. For example:

> **Plan summary:** Fetch issues assigned to user1, reassign to user2, set status to In Progress.
>
> Actions:
> 1. Fetch Jira issues assigned to user1 for processing.
> 2. Assign each fetched issue to user2.
> 3. Set the status of each issue to In Progress.
>
> Reply with `{ "approved": true }` to proceed, or `{ "approved": false, "feedback": "your notes" }` to revise.

The user reviews and makes a call. If something looks wrong, they can send feedback — *"also change priority to High"* — and the agent replans using that input and sends a new plan for review. This loop repeats up to three times before the agent gives up.

Nothing is written to the database until the user explicitly approves. This is the most important safety gate in the entire system.

### Phase 4 — Execution

Once the plan is approved, the system works through it mechanically. No more AI involvement — just code following the plan.

It first counts how many records match, so it knows when it's done. Then it fetches records in pages and applies every action in the plan to every record. Records in the same page are processed in parallel to keep things fast. If a batch fails (database hiccup, network blip), it automatically retries up to twice before giving up and reporting the error.

As each record is processed, a live progress event is sent to the caller — *"Processing PROJ-123"*, *"Processing PROJ-124"*, and so on. You see results as they happen, not at the end.

---

## Key Decisions and Why

### Nothing is executed without human approval

The plan is never executed automatically. After the AI creates a plan and the automated guardrails pass it, the agent pauses and sends the plan to the user in plain English. Only after explicit approval does execution start. If the user isn't happy with the plan, they can send feedback and get a revised version — up to three times. This makes the agent safe to run against production data, because the user always has full visibility and control over what will happen before it happens.

### The AI plans, the code executes — never both at once

This is the most important decision in the whole design. A common pattern for AI agents is to let the AI call tools directly, decide what to do next based on the result, call more tools, and so on. That works well for research tasks or conversations.

It does not work well for bulk operations on production data. If the AI is making 500 decisions in a loop, there's no way to audit what it decided or why. If it gets confused at record 300, you may have partially applied changes with no clean rollback.

Here, the AI is involved for exactly one moment: writing the plan. Everything after that is deterministic. You can read the plan, understand exactly what will happen, and the execution will follow it precisely.

### Live progress instead of a wait-and-see response

Processing 500 records takes time. Without streaming, the HTTP connection would either timeout or leave the user staring at a spinner for minutes with no feedback. The agent streams a progress event for every record it touches, so you know it's working and roughly how far along it is.

### Automatic retry on failure

Networks and databases have bad moments. Rather than failing an entire job because one batch had a transient error, the agent retries failed batches automatically. Only if a batch keeps failing after multiple attempts does it stop and report the problem.

### The system is not Jira-specific

The agent doesn't know what Jira is. It knows about tools — operations it can call. Right now those tools happen to talk to a Jira database. If you wanted to point this at GitHub pull requests, Salesforce records, or a custom internal system, you'd write a new set of tools and everything else — the planning, the validation, the execution loop, the streaming — stays exactly the same.

---

## What Guardrails Are in Place?

Two types of guardrails protect against the agent doing something it shouldn't.

**Before the AI sees anything:** The request is checked for injection attempts — phrases that try to override the AI's instructions, like "ignore everything above" or "pretend you are a different AI." These are blocked before any tokens are spent.

**After the AI produces a plan:** Two checks run before execution starts. First, a structural check confirms the plan is well-formed — every step refers to a real, registered tool, the order makes sense, and the scope is reasonable. Second, a faithfulness check uses a separate AI evaluation to confirm the plan actually reflects what the user asked for. A score below 0.8 (out of 1.0) causes the plan to be rejected and regenerated.

---

## Resuming and Auditing Past Runs

Every execution run is saved against a thread ID. This means:

- A user can come back later and resume a run that was interrupted midway, without reprocessing records that were already handled.
- The full history of a run — what was processed, what failed, how many retries happened — is stored and can be inspected.
- The human review step is built into this model: the agent parks at the review stage with the thread ID, the SSE stream stays open, and execution only resumes when the user sends their approval against that thread ID.

---

## AI Models Used

| Purpose | Model | Why |
|---|---|---|
| Writing the action plan | GPT-4o | Planning requires precise structured output and careful rule-following. GPT-4o is more reliable here than cheaper models, and since it's used only once per request, the cost is reasonable. |
| Checking the plan is grounded | GPT-4o-mini | This is a simpler yes/no evaluation task. GPT-4o-mini handles it well at a fraction of the cost. |

Both run at zero temperature — meaning no randomness in the output. This is not a creative writing tool. Every output needs to be precise and repeatable.

---

## What Happens When Things Go Wrong?

| Situation | What the agent does |
|---|---|
| Request is empty or looks malicious | Rejected immediately before the AI sees it |
| AI produces a plan with made-up tools | Plan rejected, AI tries again (up to 2 times) |
| AI plan doesn't match what the user asked | RAGAS evaluation fails, plan rejected and regenerated |
| AI can't produce a valid plan after retries | Request rejected with a clear explanation of why |
| User rejects the plan with feedback | Agent replans using the feedback, sends a new plan for review |
| User rejects the plan 3 times in a row | Agent gives up and asks the user to start a new request |
| A batch of records fails during execution | Retried automatically up to 2 times |
| Retries exhausted on a batch | Error event streamed to the user, agent stops cleanly |

---

## Summary

The agent is built around one core idea: use AI for what it's good at (understanding intent and making a plan) and use code for what it's good at (reliably executing that plan at scale). The AI is in the loop for one call. Everything else is deterministic, retryable, auditable, and streamed live to the user.

For setup instructions, API usage, and configuration options, see [HOW_TO_RUN.md](HOW_TO_RUN.md).
For the full technical deep-dive, see [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md).
