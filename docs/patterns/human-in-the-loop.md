# Pattern: Human in the Loop (Forward Recovery)

When automated retries cannot resolve a failure — a payment dispute, a fraud alert, a compliance hold — pause the workflow and wait for a human decision before continuing. This is **forward recovery**: instead of rolling back or failing, the workflow holds its durable state and resumes the moment a reviewer sends a decision.

> **Runnable example:** [`examples/human-in-the-loop/`](../../examples/human-in-the-loop/)

## When to Use

- The failure requires a judgement call that code alone cannot make.
- Rolling back is undesirable (e.g., inventory is already reserved, a physical action has occurred).
- You want the decision itself to be part of the durable workflow history.
- The resolution may take minutes, hours, or days — and the workflow must survive process restarts during that time.

## Code Pattern

### Declare the Data Type and Workflow Signature

```ballerina
type ReviewDecision record {|
    string reviewerId;
    boolean approved;   // true = continue; false = cancel
    string? note;
|};

@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ReviewDecision> review; |} events   // declares the "review" data name
) returns OrderResult|error {
```

The third parameter is the events record. Each field name (`review`) is the data name used when sending data to this workflow.

### Attempt Automation — Escalate on Failure

```ballerina
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ReviewDecision> review; |} events
) returns OrderResult|error {
    // Attempt automated payment with retries
    string|error paymentResult = ctx->callActivity(chargeCard, {
        "cardToken": input.cardToken,
        "amount": input.amount
    }, retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    if paymentResult is error {
        // Notify the review team and pause
        string _ = check ctx->callActivity(notifyReviewTeam, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow pauses here — fully durable — until a human sends the "review" data
        ReviewDecision decision = check wait events.review;

        if !decision.approved {
            return {orderId: input.orderId, status: "CANCELLED",
                    message: "Cancelled by " + decision.reviewerId};
        }

        // Reviewer approved — attempt one final manual retry
        string _ = check ctx->callActivity(chargeCardManualRetry, {
            "cardToken": input.cardToken,
            "amount": input.amount
        });
        return {orderId: input.orderId, status: "COMPLETED", message: "Manual retry succeeded"};
    }

    return {orderId: input.orderId, status: "COMPLETED", message: paymentResult};
}
```

### Send the Decision from an HTTP Endpoint

```ballerina
service /orders on new http:Listener(9090) {
    resource function post [string orderId]/review(ReviewDecision decision) returns json|error {
        string workflowId = getWorkflowId(orderId);   // look up from your store
        check workflow:sendData(processOrder, workflowId, "review", decision);
        return {status: "decision received"};
    }
}
```

`workflow:sendData` delivers the data immediately. The workflow resumes from the `wait events.review` point, carrying the `ReviewDecision` value.

## Durability While Paused

While the workflow is waiting at `wait events.review`:
- Worker process restarts do not lose the paused state — the workflow replays its Event History and returns to the `wait` point.
- The `notifyReviewTeam` activity is not re-executed on replay — its result is already in the history.
- The data can also be sent directly from the workflow engine's Web UI without going through your application's HTTP API (useful during incidents).

## Timeout: Escalate If No Decision Arrives

> **Planned feature:** Racing a data future against a durable timer (`wait f1|f2`) is not yet supported by the workflow runtime. Until this is available, set an external deadline (e.g., a scheduled job or a separate reminder workflow) that sends a timeout to the waiting workflow.

The intended pattern is to race the data future against a durable timer using Ballerina's **alternate wait** (`wait f1|f2`), which returns the result of whichever future completes first:

```ballerina
// Illustrative only — durable timer support is planned.
// Race the review data against a 48-hour timeout
future<ReviewDecision> reviewFuture = events.review;
future<error?> timeoutFuture = start ctx->sleep({hours: 48});

// Alternate wait: returns whichever future completes first
ReviewDecision|error? raceResult = wait reviewFuture|timeoutFuture;

if raceResult is ReviewDecision {
    // Data arrived before timeout
    ReviewDecision decision = raceResult;
    // ... process decision
} else {
    // Timeout expired (raceResult is () or error) — auto-cancel the order
    return {orderId: input.orderId, status: "CANCELLED", message: "Review timed out"};
}
```

> **Note:** In the snippet above, `ctx->sleep` uses remote-call syntax (not `ctx.sleep`). The `start` keyword creates a Ballerina strand/future, not a durable workflow timer. True durable timer behavior is planned for a future release.

## What's Next

- [Handle Errors](../handle-errors.md) — Pattern overview and comparison
- [Compensation Pattern](error-compensation.md) — Roll back instead of escalating
- [Handle Data](../handle-data.md) — Full reference for receiving external data
