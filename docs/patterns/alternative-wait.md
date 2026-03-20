# Pattern: Alternative Wait (First-Wins)

When a workflow needs a decision from one of several possible sources, use a **single shared data channel** that any sender can write to. The workflow waits once on that channel — the first `sendData` call unblocks the wait, and any subsequent calls are silently ignored because the workflow has already moved past the wait point.

> **Runnable example:** [`examples/alternative-wait/`](../../examples/alternative-wait/) — a purchase request can be approved by either a Manager or a Director — whichever responds first.

## When to Use

- Multiple people or systems can independently satisfy the same workflow step (e.g., any one approver is sufficient).
- You want the workflow to proceed the moment the first response arrives, without waiting for the rest.
- Approval ladder / escalation: a junior approver and a senior approver both receive a notification — whichever acts first unblocks the workflow.

## Code Pattern

### Declare the Data Type and Workflow Signature

```ballerina
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

@workflow:Workflow
function purchaseApproval(
    workflow:Context ctx,
    PurchaseInput input,
    record {|
        future<ApprovalDecision> approval;
    |} events
) returns PurchaseResult|error {
```

A single data channel (`approval`) receives the decision. Multiple senders (Manager, Director) all target the same channel name.

### Wait for the First Response

```ballerina
// Notify both approvers
string _ = check ctx->callActivity(notifyApprovers, {
    "requestId": input.requestId,
    "item": input.item,
    "amount": input.amount
});

// Wait once — the first sendData("approval", ...) unblocks the workflow
ApprovalDecision decision = check wait events.approval;

if !decision.approved {
    return {requestId: input.requestId, status: "REJECTED",
            message: "Rejected by " + decision.approverId};
}

// Approved — process the purchase
string poNumber = check ctx->callActivity(processPurchase, {...});
return {requestId: input.requestId, status: "APPROVED", message: poNumber};
```

If the Manager responds before the Director, `decision` contains the Manager's response. Any later `sendData` call for `"approval"` on the same workflow is silently ignored — the workflow has already moved past the wait point.

### Send Data from an HTTP Endpoint

Both approvers call the same endpoint — only the first response matters:

```ballerina
service /api on new http:Listener(8090) {
    // Both the Manager and the Director call this endpoint;
    // first response unblocks the workflow.
    resource function post purchases/[string workflowId]/approval(
            ApprovalDecision decision) returns json|error {
        check workflow:sendData(purchaseApproval, workflowId, "approval", decision);
        return {status: "received"};
    }
}
```

## Durability While Paused

While the workflow is waiting at `wait events.approval`:
- Worker restarts do not lose the paused state — the workflow replays and returns to the `wait` point.
- If data was already sent before the restart, it is delivered immediately on replay.
- Data sent after the workflow has already resumed is silently ignored.

## How It Differs from Wait for All

| | Alternative Wait | Wait for All |
|---|---|---|
| **Completes when** | First `sendData` call arrives | **All** data channels have been sent to |
| **Data channels** | Single shared channel | Separate channel per source |
| **Use case** | Approval ladder, first-responder | Dual authorization, collect all inputs |
| **Subsequent sends** | Silently ignored | Each consumed by its own `wait` |

## What's Next

- [Wait for All](wait-for-all.md) — Collect data from multiple sources before proceeding
- [Human in the Loop](human-in-the-loop.md) — Single-approver decision gate
- [Handle Data](../handle-data.md) — Full reference for receiving external data
