# Pattern: Human in the Loop

Some workflow steps require a human judgement call — approvals, reviews, compliance checks. The workflow pauses durably and waits for a person to send a decision via external data, then continues based on that decision.

> **Runnable example:** [`examples/human-in-the-loop/`](../../examples/human-in-the-loop/) — high-value orders require manager approval before fulfillment.

## When to Use

- A business rule requires human sign-off before proceeding (purchase approval, content review, compliance gate).
- The decision may take minutes, hours, or days — the workflow must survive process restarts during the wait.
- You want the decision to be part of the durable workflow history.

## Code Pattern

### Declare the Data Type and Workflow Signature

```ballerina
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ApprovalDecision> approval; |} events
) returns OrderResult|error {
```

The third parameter is the events record. Each field name (`approval`) is the data name used when sending data to this workflow.

### Pause for Human Approval

```ballerina
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ApprovalDecision> approval; |} events
) returns OrderResult|error {
    // Validate and prepare
    check ctx->callActivity(validateOrder, {...});

    // Notify the approval team
    string _ = check ctx->callActivity(notifyApprover, {
        "orderId": input.orderId,
        "item": input.item,
        "amount": input.amount
    });

    // Workflow pauses here — fully durable — until a human sends the "approval" data
    ApprovalDecision decision = check wait events.approval;

    if !decision.approved {
        return {orderId: input.orderId, status: "REJECTED",
                message: "Rejected by " + decision.approverId};
    }

    // Approved — fulfill the order
    string fulfillmentId = check ctx->callActivity(fulfillOrder, {...});
    return {orderId: input.orderId, status: "COMPLETED", message: fulfillmentId};
}
```

### Send the Decision from an HTTP Endpoint

```ballerina
service /api on new http:Listener(8090) {
    resource function post orders/[string workflowId]/approve(ApprovalDecision decision) returns json|error {
        check workflow:sendData(processOrder, workflowId, "approval", decision);
        return {status: "decision received"};
    }
}
```

`workflow:sendData` delivers the data immediately. The workflow resumes from the `wait events.approval` point.

## Durability While Paused

While the workflow is waiting at `wait events.approval`:
- Worker process restarts do not lose the paused state — the workflow replays its Event History and returns to the `wait` point.
- The `notifyApprover` activity is not re-executed on replay — its result is already in the history.
- The data can also be sent directly from the workflow engine's Web UI without going through your application's HTTP API (useful during incidents).

## Timeout: Escalate If No Decision Arrives

> **Planned feature:** Racing a data future against a durable timer (`wait f1|f2`) is not yet supported by the workflow runtime. Until this is available, set an external deadline (e.g., a scheduled job or a separate reminder workflow) that sends a timeout to the waiting workflow.

The intended pattern is to race the data future against a durable timer using Ballerina's **alternate wait** (`wait f1|f2`), which returns the result of whichever future completes first:

```ballerina
// Illustrative only — durable timer support is planned.
future<ApprovalDecision> approvalFuture = events.approval;
future<error?> timeoutFuture = start ctx->sleep({hours: 48});

// Alternate wait: returns whichever future completes first
ApprovalDecision|error? raceResult = wait approvalFuture|timeoutFuture;

if raceResult is ApprovalDecision {
    // Decision arrived before timeout
    ApprovalDecision decision = raceResult;
    // ... process decision
} else {
    // Timeout expired — auto-reject the order
    return {orderId: input.orderId, status: "REJECTED", message: "Approval timed out"};
}
```

> **Note:** In the snippet above, `ctx->sleep` uses remote-call syntax (not `ctx.sleep`). The `start` keyword creates a Ballerina strand/future, not a durable workflow timer. True durable timer behavior is planned for a future release.

## What's Next

- [Forward Recovery](forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Handle Data](../handle-data.md) — Full reference for receiving external data
- [Handle Errors](../handle-errors.md) — Error handling patterns overview
