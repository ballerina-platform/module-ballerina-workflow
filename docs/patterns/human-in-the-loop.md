# Pattern: Human in the Loop

Some workflow steps require a human judgement call — approvals, reviews, compliance checks. The workflow creates a task, records who may answer it, and pauses durably until a person submits a decision — then continues on what they decided.

> **Runnable example:** [`examples/human-in-the-loop/`](../../examples/human-in-the-loop/) — high-value orders require manager approval before fulfillment.

## When to Use

- A business rule requires human sign-off before proceeding (purchase approval, content review, compliance gate).
- The decision may take minutes, hours, or days — the workflow must survive process restarts during the wait.
- You want the decision to be part of the durable workflow history.

## Code Pattern

The module has a first-class human task API: `ctx->awaitHumanTask` creates a task, records who
may answer it, and durably pauses the workflow until someone submits a decision. Use it in
preference to hand-rolling an approval over a data channel.

### Declare the Decision Type

The type you bind the result to is the task's result shape — it drives the generated form and is
validated at runtime when the decision is submitted.

```ballerina
type ApprovalDecision record {|
    boolean approved;
    string? reason;
|};
```

### Pause for Human Approval

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // Validate and prepare
    string _ = check ctx->callActivity(validateOrder, {
        "orderId": input.orderId,
        "item": input.item,
        "amount": input.amount
    });

    // Workflow pauses here — fully durable — until a manager submits a decision
    ApprovalDecision decision = check ctx->awaitHumanTask("approveOrder",
            {orderId: input.orderId, item: input.item, amount: input.amount.toString()},
            userRoles = "MANAGER",
            title = string `Approve ${input.item} for $${input.amount}`);

    if !decision.approved {
        return {orderId: input.orderId, status: "REJECTED",
                message: string `Rejected: ${decision.reason ?: "no reason given"}`};
    }

    // Approved — fulfill the order
    string fulfillmentId = check ctx->callActivity(fulfillOrder,
            {"orderId": input.orderId, "item": input.item});
    return {orderId: input.orderId, status: "COMPLETED", message: fulfillmentId};
}
```

`awaitHumanTask` takes:

| Argument | Description |
|----------|-------------|
| `taskName` | Identifies the task type. Must not contain `.` or `|` (`WORKFLOW_128`) |
| `taskInput` | Read-only `map<json>` shown beside the form. Pass `{}` when there is nothing to show; it is checked against the definition's `taskInputType` |
| `userRoles` | Role or roles permitted to answer — from `ReviewTaskDefinition`, passed as a named argument |
| `title`, `description` | How the task reads in the inbox |
| `timeout` | Optional deadline; on expiry the call returns a `HumanTaskTimeoutError` |
| `stepId` | Optional stable step identity, as for `callActivity` |

Internally the task is a durable child workflow, so it survives worker restarts.

### List and Complete Tasks

Pending tasks are read and answered through the `ballerina/workflow.management` module:

```ballerina
import ballerina/workflow.management;

service /api on new http:Listener(8090) {
    // List the pending approval tasks for one workflow
    resource function get orders/[string workflowId]/tasks() returns management:HumanTaskGroup[]|error {
        return management:listPendingHumanTasks(workflowId);
    }

    // Submit the manager's decision
    resource function post tasks/[string taskId]/complete(@http:Payload ApprovalDecision decision)
            returns record {| string status; |}|error {
        check workflow:completeHumanTask(taskId, decision);
        return {status: "accepted"};
    }
}
```

`completeHumanTask` returns once the engine has accepted the decision; the workflow resumes from
`awaitHumanTask` independently after that. If it fails (the engine is unavailable, or the task ID
does not exist), the endpoint returns an error and the caller should retry.

### Handling Rejection and Timeout

`awaitHumanTask` reports failure as a `HumanTaskError`, which tells you *how* the task ended.
An `on fail` clause attaches to a `do` block, so wrap the call in one to handle it:

```ballerina
do {
    ApprovalDecision decision = check ctx->awaitHumanTask("approveOrder", {},
            userRoles = "MANAGER", timeout = {hours: 24});
    return {orderId: input.orderId, status: decision.approved ? "APPROVED" : "REJECTED",
            message: ""};
} on fail workflow:HumanTaskError e {
    if e is workflow:HumanTaskTimeoutError {
        () _ = check ctx->callActivity(notifyEscalation, {"taskName": e.detail().taskName});
    }
    return e;
}
```

- `HumanTaskTimeoutError` — nobody acted before the deadline.
- `HumanTaskRejectedError` — someone rejected the task; the reason and any structured details are
  on the error detail, so the workflow can compensate on what the rejecting user said.
- `HumanTaskFailedError` — the task could not produce a result at all.

## Alternative: Approval over a Data Channel

When the decision arrives from a system rather than a task inbox — a webhook from an external
approval tool, say — model it as external data instead. The workflow declares an events record and
waits on it, and the sender calls `workflow:sendData`:

```ballerina
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ApprovalDecision> approval; |} events
) returns OrderResult|error {
    string _ = check ctx->callActivity(validateOrder, {"orderId": input.orderId});

    // Workflow pauses here until a caller sends the "approval" data
    ApprovalDecision decision = check wait events.approval;

    if !decision.approved {
        return {orderId: input.orderId, status: "REJECTED", message: "Rejected"};
    }

    string fulfillmentId = check ctx->callActivity(fulfillOrder,
            {"orderId": input.orderId, "item": input.item});
    return {orderId: input.orderId, status: "COMPLETED", message: fulfillmentId};
}
```

```ballerina
service /api on new http:Listener(8090) {
    resource function post orders/[string workflowId]/approve(ApprovalDecision decision)
            returns json|error {
        check workflow:sendData(processOrder, workflowId, "approval", decision);
        return {status: "accepted"};
    }
}
```

This route has no inbox, no roles, and no generated form — the workflow simply resumes when the
data arrives. See [Handle Data](../handle-data.md) for the full reference.

## Durability While Paused

While the workflow is paused at `awaitHumanTask` (or at `wait events.approval`):
- Worker process restarts do not lose the paused state — the workflow replays its Event History and returns to the pause point.
- Activities that already ran are not re-executed on replay — their results are in the history.
- A human task is a durable child workflow, so it too survives restarts; the decision can be submitted from the task inbox, the management API, or the workflow engine's Web UI.

## Escalating When No Decision Arrives

`awaitHumanTask` takes a `timeout` directly, and reports expiry as a `HumanTaskTimeoutError` —
see [Handling Rejection and Timeout](#handling-rejection-and-timeout) above.

On the data-channel route, use `ctx->await` with a timeout instead:

```ballerina
[ApprovalDecision] [decision] = check ctx->await([events.approval], timeout = {hours: 24});
```

If the timeout expires first, `ctx->await` returns an error and the workflow can trigger escalation (for example, notify a second approver or support queue).

Both notification and response timestamps are preserved in workflow history through recorded activity executions and data-delivery events.

## What's Next

- [Forward Recovery](forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Handle Data](../handle-data.md) — Full reference for receiving external data
- [Handle Errors](../handle-errors.md) — Error handling patterns overview
