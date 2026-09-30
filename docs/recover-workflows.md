# Recover Workflows

When something goes wrong in a running workflow, there are four different ways to recover. They repeat different things, are triggered by different parties, and have different safety rules, so it pays to name them precisely:

| Recovery | What is repeated | Who triggers it | How |
|----------|------------------|-----------------|-----|
| **Retry** | One failed step, usually with the same arguments | The engine, or a person | `AutoRetry`, a review raised on failure, or a bulk retry |
| **Resend** | An inbound message or event | The caller that sent it | Calling `sendData`, `run`, or a task completion again |
| **Reset** (reprocess) | Every step after a chosen point | An operator | `management:resetWorkflowExecution` |
| **Escalation** | Nothing — a person decides what the failure means | The workflow design | Reviews, human tasks, forward recovery, compensation |

Replay is not on this list on purpose. In this module, replay is the engine rebuilding a workflow's state from its history, and it never repeats a side effect — see [Replay Is Not Reprocessing](#replay-is-not-reprocessing).

## Retry — Run the Failed Step Again

A retry re-executes one activity call that failed. Completed steps are untouched.

**Engine retries.** Pass an `AutoRetry` record as `retryPolicy`. The engine retries with durable backoff, records every attempt in the workflow history, and hands the workflow the error only after the last attempt fails.

```ballerina
string receipt = check ctx->callActivity(chargeCard, {"amount": input.amount},
        retryPolicy = {maxRetries: 3, retryDelay: 2.0, retryBackoff: 1.5});
```

The default is `NoRetry`: the first error is returned to the workflow as a value, and no configuration changes that — a `NoRetry` call always runs once, and an `AutoRetry` record's unset fields take the record's own defaults (`maxRetries` 3, `retryDelay` 1.0, `retryBackoff` 2.0). The `activityRetry*` settings in [Configure the Module](configure-the-module.md) govern something else: the runtime's own built-in activities, through which a workflow's client calls such as `workflow:run` are made durable. They do not apply to `callActivity`.

**Retries decided by a person.** Pass a `ReviewTaskDefinition` as `retryPolicy` to raise a review when the activity fails. The reviewer answers with:

- `proceed` — rerun the activity with its original arguments (a retry).
- `proceed-with-input` — rerun it with corrected arguments (an [escalation](#escalation--let-a-person-decide) that ends in a retry).
- `reject` — let the failure stand; the workflow receives a `workflow:ReviewRejectedError` whose cause is the original failure.

To try automatic retries first and involve a person only when they are spent, use `RetryBeforeReview` — an `AutoRetry` and a review audience in one literal:

```ballerina
string receipt = check ctx->callActivity(chargeCard, {"amount": input.amount},
        retryPolicy = {maxRetries: 2, userRoles: "OPS", title: "Card charge failed"});
```

**Bulk retries.** After an outage, many workflows can be waiting on failed-activity reviews at once. `POST /workflow/review-activities/bulk-retry` (command `reviewActivities.bulkRetry`) applies one decision to all of them: `retry` (the single-task `proceed`) or `fail` (the single-task `reject`). It reports an outcome per task — `APPLIED`, `SKIPPED`, or `FAILED` — so a task another operator already decided does not stop the rest. A bulk decision cannot change arguments; editing input is a single-task `proceed-with-input`.

**Retries in workflow code.** A retry can also be ordinary control flow: catch the error, `ctx.sleep`, and call the activity again. Use this when the retry needs business logic, such as a different delay per error type.

> **Retries repeat side effects.** An activity can run more than once for the same call even without a retry policy — for example, when a worker crashes after the activity finishes but before its result is recorded. Make activities idempotent. See [Idempotency](write-activity-functions.md#idempotency).

## Resend — Deliver an Inbound Message Again

A resend is the caller repeating a request because it does not know whether the first one landed. The engine does **not** deduplicate inbound requests, so whether a resend is safe depends on the request:

| Request | Effect of sending it twice |
|---------|----------------------------|
| `workflow:run` | Starts **two** workflow instances. |
| `workflow:sendData`, `management:sendDataToWorkflow` | Delivers the event twice. Safe only when the workflow waits on the channel once and later deliveries are ignored — the [Alternative Wait](patterns/alternative-wait.md) pattern. A workflow that reads the same channel more than once, or advances step by step on events, can move forward twice. |
| `completeHumanTask`, `failHumanTask`, review decisions | Safe: the second call returns an error because the task is already closed. |
| `resetWorkflowExecution` | Safe when the request carries an idempotency key, so a repeated reset is a no-op. Without one, only an identical repeat is a no-op: a retry that resolves `latest` to a different run resets again. |

To make a data event safe to resend, put an idempotency key in the payload and have the workflow ignore a key it has already recorded. Checking the instance first with `management:getWorkflowInfo` is not enough: a request that timed out may still be in flight, so the check can pass and both deliveries still land. A key in `run`'s input does not help either, because each call starts a new instance that sees the key once; deduplicate before calling `run`.

A durable agent returns a correlation token for each `sendData` turn. After a crash, call `workflow:getPendingAgentEvents` to find the turns that were accepted but not yet answered, instead of resending them.

## Reset — Reprocess From a Chosen Point

A reset moves a run back to an earlier point and re-executes everything after it, as a new run with the same workflow ID. Use it when a run failed, or went wrong, for a reason that has since been fixed — a bug in the workflow code, a bad deployment, a downstream outage that outlasted the retries.

1. List the points the run can be reset to: `GET /workflow/workflows/{workflowId}/reset-points` (`management:listResetPoints`). Each point names the steps that re-run from there, and the point that re-runs the run's first failed step is marked `isFirstFailure`.
2. Reset: `POST /workflow/workflows/{workflowId}/reset` (`management:resetWorkflowExecution`), with `resetType` set to `first-workflow-task`, `last-workflow-task`, or `workflow-task-id` plus an `eventId`.

Before you reset, know what it does:

- **Every step after the point runs again** — including error handling and compensation the run already performed. It is a reprocess, not a retry of one step.
- **It runs on the current code.** That is how a fix is applied to a run that already failed, and also why the new code must still accept the history before the point.
- **The point is a workflow task, not an activity.** Activities scheduled together come back together.
- **Signals received after the point are re-delivered by default** (`reapply.type = "signal"`). Updates are not, and durable agent turns arrive as updates, so a reset agent loses the conversation after the point unless you pass `"all-eligible"`. Pass `"none"` to re-deliver nothing.

### Replay Is Not Reprocessing

Replay is how the engine recovers a workflow after a worker restart: it re-runs the workflow function from its recorded history to rebuild local state. Completed activities are not executed again — their recorded results are returned — so replay has no side effects outside the workflow. You never trigger it, and `ctx.isReplaying()` tells code that it is running under replay.

If you come from a message-store background, "replay a message" means processing it again. The equivalent here is a reset.

## Escalation — Let a Person Decide

Some failures are not technical. A declined card, an address that does not exist, or a document that fails validation cannot be fixed by running the same step again. Escalation stops the automated path and hands the decision to a person:

- **Review on failure** — `proceed-with-input` lets a reviewer correct the arguments and continue, and `reject` lets the failure stand.
- **Human tasks** — `ctx->awaitHumanTask` waits for a decision and reports `HumanTaskTimeoutError`, `HumanTaskRejectedError`, or `HumanTaskFailedError`. See [Human in the Loop](patterns/human-in-the-loop.md).
- **[Forward Recovery](patterns/forward-recovery.md)** — pause for corrected data, then retry the failed activity with it.
- **[Compensation](patterns/error-compensation.md)** — undo the steps that already completed.

Task administrators can reassign a task or move its deadline when the original audience is unavailable.

Model an expected business outcome, such as "payment declined", as an ordinary value the workflow branches on, not as an error. See [Technical Errors vs Business Outcomes](handle-errors.md#technical-errors-vs-business-outcomes).

## Message Stores or Workflows

Store-and-forward integration — a message store with a message processor — also offers retry, resend, and replay, but the unit of recovery is different:

| | Message store and processor | Workflow |
|---|---|---|
| Unit of recovery | The message | The step |
| Progress kept on failure | None — processing starts again from the message | Every completed step |
| Retry | The processor redelivers the whole message | The engine retries the failed activity |
| Resend / replay | Redeliver a stored or dead-lettered message | Reset from a chosen point |
| Human decision | Outside the flow | Part of the workflow history |

Use a message store when the job is **delivery**: getting one message to a backend that may be down, throttling or ordering traffic, or fire-and-forget mediation with no state between steps.

Use a workflow when the job is **a process**: several steps whose progress must survive failures, human decisions, compensation, or waits of hours or days.

The two combine at the workflow's edges:

- **Inbound.** `run` and `sendData` are direct calls, not a queue. When senders need guaranteed delivery into a workflow, put the store-and-forward in front of it — the processor calls `run` or `sendData` — and make that call safe to resend, because the processor will resend it.
- **Outbound.** An activity's `AutoRetry` does the job of processor redelivery. Do not retry at both layers: the attempts multiply.

## What's Next

- [Handle Errors](handle-errors.md) — Error propagation, fallback, and compensation patterns
- [Write Activity Functions](write-activity-functions.md) — Retry options and idempotency
- [Handle Data](handle-data.md) — Sending data to running workflows
