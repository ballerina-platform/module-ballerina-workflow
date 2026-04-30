# Handle Errors

In Ballerina, errors are first-class values — they are returned, inspected, and acted on like any other value. This is a deliberate design principle: **error handling is part of the business logic, not an afterthought**.

Workflows are long-running business processes, and deviations from the happy path are expected. Some are technical failures (for example, an API timeout or a DB connectivity issue). Others are valid business outcomes (for example, payment declined or out of stock). Both should be modeled explicitly in workflow logic.

## Technical Errors vs Business Outcomes

- **Technical errors**: infrastructure or execution failures (`error` values), such as network timeouts, unavailable downstream systems, serialization failures, or transaction aborts.
- **Business outcomes**: domain-level decisions represented as normal data values, such as rejected approvals, declined payments, or insufficient inventory.

This guide focuses on technical error handling patterns (`T|error`) while keeping business outcomes explicit in normal control flow.

## Where Errors Are Handled

An activity failure can be addressed in two places:

- **Inside the activity** — the activity handles the failure internally and returns a success result to the workflow. This is appropriate for low-level infrastructure retries that the workflow does not need to know about.
- **In the workflow** — the activity returns `T|error`, and the workflow decides what happens next: retry with different parameters, try an alternative path, compensate earlier steps, or wait for a human decision. This is the preferred pattern for business-significant failures, because the decision itself becomes part of the durable workflow history.

## Errors and Workflow State

When an activity returns an `error`, the `ballerina/workflow` module records an `ActivityTaskFailed` event in the workflow's history. This happens regardless of whether the workflow handles the error or propagates it — the failure is always visible in the workflow engine's event history and audit log before the workflow code sees the return value.

What happens next is determined entirely by the workflow code:

```ballerina
// The workflow receives the error as a plain value — what happens next is your choice
string|error result = ctx->callActivity(processPayment, {"amount": input.amount});
```

If the activity is configured with retries (`retryOnError = true`), the workflow engine retries the activity transparently. Each attempt is recorded in history as Temporal task events (for example `ActivityTaskScheduled` / `ActivityTaskStarted` / `ActivityTaskCompleted` or `ActivityTaskFailed`). Only after all retries are exhausted does the final error reach the workflow as a value.

Because failures and outcomes are durably recorded in an append-only history, workflows can recover from worker-process restarts mid-execution and resume from the last recorded workflow state — giving durability and observability without extra instrumentation.

If the workflow engine itself is temporarily unavailable, API calls such as `sendData` may fail fast and should be retried by callers. Once the engine and workers recover, execution resumes from recorded history.

> **Retry safety for `sendData`:** retries are safe in the [Alternative Wait — First Wins](handle-data.md#alternative-wait--first-wins) pattern, where multiple senders post to a single shared channel and only the first delivery unblocks the workflow — later duplicates on the same channel are effectively ignored. For sequential workflows or multi-channel scenarios (for example, distinct channels per step or per approver, or a workflow that consumes the same channel more than once), duplicates are **not** auto-suppressed: a blind retry can deliver the same signal twice and advance the workflow further than intended. In those cases, make the send idempotent before retrying — for example, check the workflow status with `workflow:getWorkflowInfo()` to confirm the prior send was not already accepted, or include an idempotency key in the payload that the workflow (or the receiving activity) deduplicates against its recorded state.

---

## Controlling Retries

By default (`retryOnError = false`), errors are returned immediately as values and no automatic retries are attempted. This is a safe baseline that keeps retry intent explicit per call.

Business-specific retry behavior (including delayed retries) can still be implemented in workflow logic or enabled via activity retry options when appropriate.

Enable retries when the failure is transient (e.g., network timeouts, intermittent downstream outages):

```ballerina
// Default: error returned immediately as a value, no retries
string|error result = ctx->callActivity(chargeCard, {"amount": input.amount});

// Opt-in retries: the workflow engine retries the activity up to 3 times before returning the error
string|error retried = ctx->callActivity(chargeCard, {"amount": input.amount},
        retryOnError = true, maxRetries = 3, retryDelay = 2.0, retryBackoff = 1.5);
```

Regardless of whether retries are enabled, the outcome is the same: the error arrives at the workflow as a `T|error` value. Retries never hide the error from the workflow code.

### Retry Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `retryOnError` | `boolean` | `false` | Enable workflow-engine-level retries |
| `maxRetries` | `int` | `0` | Maximum number of retry attempts (e.g., `3` means up to 3 retries after the initial attempt, for 4 total attempts) |
| `retryDelay` | `decimal` | `1.0` | Initial delay in seconds between retries |
| `retryBackoff` | `decimal` | `2.0` | Exponential backoff multiplier |
| `maxRetryDelay`| `decimal?`  | *(none)*| Cap on the delay between retries in seconds (optional) |

## State Transition Summary

The following table summarizes common state transitions during error handling.

| Scope | Trigger | Resulting state |
|-------|---------|-----------------|
| Activity attempt | `ctx->callActivity()` scheduled | Scheduled -> Started |
| Activity attempt | Successful completion | Completed |
| Activity attempt | Failure with retries enabled | Failed -> (retry) Scheduled |
| Activity attempt | Failure with retries exhausted | Failed (error returned to workflow) |
| Workflow run | `check` propagates unhandled error | Failed |
| Workflow run | Error captured and handled in workflow code | Running (continues) |
| Workflow run | Compensations complete | Completed (with rollback outcome) |

State names shown here are conceptual workflow/API-level states; engine event history may use more granular event types.

---

## Error Handling Patterns

### Propagate — Fail the Workflow

Use `check` to propagate the error. The workflow transitions to **Failed**, all subsequent steps are skipped, and the caller of `workflow:getWorkflowResult()` receives the error. Use this pattern when the failure means the workflow cannot meaningfully continue — the full error, including any detail fields, is visible in the workflow engine's UI under the workflow's **Event History**.

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // If checkInventory returns an error, `check` propagates it immediately.
    // The workflow is marked Failed. confirmOrder is never called.
    boolean _ = check ctx->callActivity(checkInventory, {"item": input.item});
    string _ = check ctx->callActivity(confirmOrder, {"orderId": input.orderId});
    return {orderId: input.orderId, status: "COMPLETED"};
}
```

> **Pattern guide:** [patterns/error-propagation.md](patterns/error-propagation.md)

---

### Handle Inline — Inspect the Error Value

Capture the `T|error` return and branch on it. The workflow stays **Running** and continues executing. Use `result.message()` for the error message and pattern-match on specific error types for fine-grained handling.

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns string|error {
    // Capture as T|error — the workflow stays Running regardless of the outcome
    string|error result = ctx->callActivity(checkStock, {"item": input.item});
    if result is error {
        // Handle the failure explicitly — workflow continues
        return "Out of stock: " + result.message();
    }
    return result;
}
```

---

### Fallback — Try an Alternative

When the primary activity exhausts its retries, fall back to a secondary path. The workflow completes successfully via the fallback.

```ballerina
@workflow:Workflow
function sendNotification(workflow:Context ctx, NotificationInput input) returns string|error {
    // Try primary with retries; capture the error rather than propagating
    string|error emailResult = ctx->callActivity(sendEmail,
            {"to": input.email, "message": input.message},
            retryOnError = true, maxRetries = 2, retryDelay = 1.0, retryBackoff = 2.0);

    if emailResult is error {
        // Primary exhausted — fall back to SMS. `check` means: fail if SMS also fails.
        return check ctx->callActivity(sendSms,
                {"phone": input.phone, "message": input.message});
    }

    return emailResult;
}
```

> **Pattern guide:** [patterns/error-fallback.md](patterns/error-fallback.md)

---

### Compensation (Saga Pattern) — Undo Completed Steps

When a later step fails, run compensating activities to reverse earlier completed work. This is the standard pattern for distributed workflows without global ACID rollback. Each compensating activity is itself a durable activity call — if compensation fails, it can also be retried or escalated.

```ballerina
@workflow:Workflow
function transferFunds(workflow:Context ctx, TransferInput input) returns string|error {
    // Step 1: complete the debit. `check` — if this fails there is nothing to compensate.
    string _ = check ctx->callActivity(debitAccount,
            {"accountId": input.sourceAccount, "amount": input.amount});

    // Step 2: capture as T|error so we can compensate on failure.
    string|error creditResult = ctx->callActivity(creditAccount,
            {"accountId": input.destAccount, "amount": input.amount},
            retryOnError = true, maxRetries = 2, retryDelay = 1.0);

    if creditResult is error {
        // Step 2 exhausted retries — reverse the debit to restore consistency
        string _ = check ctx->callActivity(reverseDebit,
                {"accountId": input.sourceAccount, "amount": input.amount});
        return string `Transfer ${input.transferId} ROLLED_BACK`;
    }

    return string `Transfer ${input.transferId} COMPLETED`;
}
```

Compensation is not the same as ACID rollback. A compensating action may have side effects (fees, notifications, partial reversals) even when it restores business consistency.

> **Pattern guide:** [patterns/error-compensation.md](patterns/error-compensation.md)

---

### Graceful Completion — Tolerate Non-Critical Failures

When the failed activity is not required for the core business outcome (e.g., an audit log, a notification), skip it and let the workflow complete successfully.

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns string|error {
    // CRITICAL — must succeed. Propagate failure with `check`.
    string reservationId = check ctx->callActivity(reserveInventory,
            {"orderId": input.orderId, "item": input.item});

    // NON-CRITICAL — tolerate failure. Retry once; skip if still failing.
    string|error emailResult = ctx->callActivity(sendConfirmationEmail,
            {"email": input.customerEmail, "orderId": input.orderId},
            retryOnError = true, maxRetries = 1, retryDelay = 1.0);
    if emailResult is error {
        return reservationId + " (email skipped: " + emailResult.message() + ")";
    }

    return reservationId + "; email: " + emailResult;
}
```

> **Pattern guide:** [patterns/graceful-completion.md](patterns/graceful-completion.md)

---

## Forward Recovery

When an activity fails due to a condition that code alone cannot resolve (e.g., a payment dispute, a compliance hold, an ambiguous data state), you can pause the workflow and wait for a human to supply **corrected data**, then retry the failed activity with the updated values. This guide uses **forward recovery** in that practical sense: retain state and move forward once corrected.

### Define the Correction Type

```ballerina
type PaymentCorrection record {|
    string cardToken;     // new or corrected card token
    decimal? amount;      // nil = keep original amount
|};
```

### Pause and Wait for Corrected Data

```ballerina
@workflow:Workflow
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentCorrection> paymentRetry; |} events
) returns OrderResult|error {
    // Attempt payment
    string|error paymentResult = ctx->callActivity(processPayment,
            {"cardToken": input.cardToken, "amount": input.amount});

    if paymentResult is error {
        // Payment failed — notify the user and pause for corrected data
        check ctx->callActivity(notifyPaymentFailure, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow pauses here until the user sends corrected payment details
        PaymentCorrection correction = check wait events.paymentRetry;

        // Retry payment with corrected values
        string txnId = check ctx->callActivity(processPayment,
                {"cardToken": correction.cardToken,
                 "amount": correction.amount ?: input.amount});
        return {orderId: input.orderId, status: "COMPLETED", message: txnId};
    }

    return {orderId: input.orderId, status: "COMPLETED", message: paymentResult};
}
```

### Send Corrected Data from an HTTP Endpoint

```ballerina
import ballerina/http;
import ballerina/workflow;

map<string> activeWorkflows = {};

service /orders on new http:Listener(9090) {
    resource function post .(OrderInput request) returns json|error {
        string workflowId = check workflow:run(orderProcess, request);
        activeWorkflows[request.orderId] = workflowId;
        return {status: "started", workflowId};
    }

    // Called by the user to supply corrected payment details
    resource function post [string orderId]/retryPayment(PaymentCorrection correction) returns json|error {
        string? workflowId = activeWorkflows[orderId];
        if workflowId is () {
            return error("No active workflow for order: " + orderId);
        }
        check workflow:sendData(orderProcess, workflowId, "paymentRetry", correction);
        return {status: "correction received"};
    }
}
```

> **Pattern guide:** [patterns/forward-recovery.md](patterns/forward-recovery.md)

---

## Recovering Workflows via Workflow Engine UI

The workflow engine's Web UI gives operators direct visibility into workflow failures and provides several tools for manual recovery without requiring code changes or redeployments.

### Inspect a Failed Workflow

1. Open the workflow engine's Web UI (default: `http://localhost:8233`)
2. Navigate to **Workflows** and filter by status **Failed** or **Timed Out**
3. Click a workflow to see its **Event History** — the `ActivityTaskFailed` event shows the error message, error type, and any detail fields your activity returned
4. The exact Ballerina error message and cause chain are serialized into the failure payload and displayed here

### Reset to a Previous Point

A **workflow reset** replays the workflow from a chosen event, discarding history after that point. Use this when:
- The activity failed due to a bug that has since been fixed
- A transient infrastructure issue caused repeated failures and you want to retry from a known-good state

Steps:
1. In the workflow detail view, click **Reset Workflow**
2. Select the event to reset to — typically the last `WorkflowTaskCompleted` event before the failed activity was scheduled
3. Confirm the reset — the workflow engine creates a new run (a new run ID under the same workflow) and rebuilds workflow state by replaying the recorded history up to the reset event; events after that point are discarded and execution continues from the reset point onwards

After resetting, the workflow worker will pick up the new run and execute the activity again with the fixed code.

### Terminate and Restart

If the workflow state is corrupt or the reset point is unclear, terminate the failed workflow and start a new one with corrected input:

1. In the workflow detail view, click **Terminate Workflow** and provide a reason
2. Start a fresh workflow run via your application API or by calling `workflow:run()` directly with the corrected input

Termination is permanent — it marks the workflow **Terminated** and stops all execution. Use it as a last resort when reset is not appropriate.

At present, this guide does not cover module-level suspend/resume APIs.

### Send Data to a Paused Workflow

If your workflow is waiting for external data (as shown above), you can send the data directly from the workflow engine's UI without going through your HTTP API:

1. Open the running workflow's detail view
2. Click **Send Data**
3. Enter the data name (must match the field name in the events record, e.g., `"paymentRetry"`)
4. Paste the JSON payload matching the data type (e.g., `{"cardToken": "tok_new_card", "amount": null}`)
5. Click **Send** — the workflow resumes immediately

This is useful during incidents when the normal data delivery path is unavailable.

---

## What's Next

- [Propagate Pattern](patterns/error-propagation.md) — Fail immediately when a critical activity fails
- [Fallback Pattern](patterns/error-fallback.md) — Try an alternative when the primary exhausts retries
- [Compensation Pattern](patterns/error-compensation.md) — Undo committed steps with the Saga pattern
- [Graceful Completion](patterns/graceful-completion.md) — Tolerate non-critical failures and complete successfully
- [Forward Recovery](patterns/forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Human in the Loop](patterns/human-in-the-loop.md) — Pause for a human decision (approve or reject)
- [Handle Data](handle-data.md) — Receiving external data and human-in-the-loop patterns
- [Write Workflow Functions](write-workflow-functions.md) — Workflow function details
- [Write Activity Functions](write-activity-functions.md) — Activity options and retry configuration
