# Handle Data

Workflows can receive external data while running using future-based data handling. This allows a workflow to pause and wait for data — such as approvals, payments, or user actions — before continuing execution.

## Define Data Types

Create record types for each kind of data your workflow expects:

```ballerina
type ApprovalDecision record {|
    string approverId;
    boolean approved;
|};

type PaymentConfirmation record {|
    decimal amount;
    string transactionRef;
|};
```

Data types must be subtypes of `anydata`.

## Add an Events Parameter

Add a third parameter to your workflow function — a record with `future<T>` fields. Each field name becomes the data name:

```ballerina
@workflow:Workflow
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    record {|
        future<ApprovalDecision> approval;       // Data name: "approval"
        future<PaymentConfirmation> payment;    // Data name: "payment"
    |} events
) returns OrderResult|error {
    // ...
}
```

The runtime automatically manages the futures and delivers data when they arrive.

## Wait for Data

Use Ballerina's `wait` keyword to pause the workflow until data arrives:

```ballerina
@workflow:Workflow
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ApprovalDecision> approval; future<PaymentConfirmation> payment; |} events
) returns OrderResult|error {
    // Check inventory first
    boolean inStock = check ctx->callActivity(checkInventory, {"item": input.item});

    if !inStock {
        return {orderId: input.orderId, status: "OUT_OF_STOCK"};
    }

    // Wait for approval (workflow pauses here)
    ApprovalDecision approvalData = check wait events.approval;

    if !approvalData.approved {
        return {orderId: input.orderId, status: "REJECTED"};
    }

    // Wait for payment
    PaymentConfirmation paymentData = check wait events.payment;

    return {
        orderId: input.orderId,
        status: "COMPLETED",
        message: string `Paid ${paymentData.amount} via ${paymentData.transactionRef}`
    };
}
```

## Send Data to a Running Workflow

Use `workflow:sendData()` to deliver data to a running workflow:

```ballerina
// Start the workflow
string workflowId = check workflow:run(orderProcess, {orderId: "ORD-001", item: "laptop"});

// Send approval data (the dataName must match the field name in the events record)
check workflow:sendData(orderProcess, workflowId, "approval", {
    approverId: "manager-1",
    approved: true
});

// Send payment data
check workflow:sendData(orderProcess, workflowId, "payment", {
    amount: 1999.99,
    transactionRef: "TXN-12345"
});
```

### Parameters

| Parameter | Description |
|-----------|-------------|
| `workflow` | The workflow function reference (must be annotated with `@Workflow`) |
| `workflowId` | The ID of the running workflow instance (returned by `workflow:run()`) |
| `dataName` | The data name — must match a field name in the events record |
| `data` | The data payload — must match the type of the corresponding `future<T>` |

## Expose Data Delivery via HTTP

A common pattern is to expose data delivery through HTTP endpoints:

```ballerina
import ballerina/http;
import ballerina/workflow;

map<string> activeWorkflows = {};

service /orders on new http:Listener(9090) {
    // Start a workflow
    resource function post .(OrderInput request) returns json|error {
        string workflowId = check workflow:run(orderProcess, request);
        activeWorkflows[request.orderId] = workflowId;
        return {status: "started", workflowId: workflowId};
    }

    // Send payment data to a running workflow
    resource function post [string orderId]/payment(PaymentConfirmation payment) returns json|error {
        string? workflowId = activeWorkflows[orderId];
        if workflowId is () {
            return error("No active workflow for order: " + orderId);
        }
        check workflow:sendData(orderProcess, workflowId, "payment", payment);
        return {status: "payment received"};
    }
}
```

## Conditional Data Waiting

You can wait for data conditionally. Data that is never waited on is simply ignored:

```ballerina
@workflow:Workflow
function conditionalProcess(
    workflow:Context ctx,
    Input input,
    record {| future<ApprovalDecision> approval; future<PaymentConfirmation> payment; |} events
) returns Output|error {
    ApprovalDecision decision = check wait events.approval;

    if decision.approved {
        // Only wait for payment if approved
        PaymentConfirmation pay = check wait events.payment;
        return {status: "PAID", amount: pay.amount};
    }

    return {status: "REJECTED"};
}
```

## Alternative Wait — First Wins

When a workflow step can be satisfied by **any one** of several senders, use a single shared data channel. Multiple senders all target the same channel name — the first `sendData` call unblocks the wait, and any subsequent calls are silently ignored.

A common use case is **any-approver**: multiple approvers are notified simultaneously, and whichever responds first unblocks the workflow.

```ballerina
@workflow:Workflow
function purchaseApproval(
    workflow:Context ctx,
    PurchaseInput input,
    record {|
        future<ApprovalDecision> approval;
    |} events
) returns PurchaseResult|error {
    // Notify both approvers
    string _ = check ctx->callActivity(notifyApprovers, {...});

    // Wait once — first sendData("approval", ...) wins, rest ignored
    ApprovalDecision decision = check wait events.approval;

    if !decision.approved {
        return {requestId: input.requestId, status: "REJECTED",
                message: "Rejected by " + decision.approverId};
    }

    string poNumber = check ctx->callActivity(processPurchase, {...});
    return {requestId: input.requestId, status: "APPROVED", message: poNumber};
}
```

Both the Manager and the Director send to the same data channel — only the first response matters:

```ballerina
// Manager responds
check workflow:sendData(purchaseApproval, workflowId, "approval", decision);

// Or director responds — first call wins, second is ignored
check workflow:sendData(purchaseApproval, workflowId, "approval", decision);
```

> **Pattern guide:** [patterns/alternative-wait.md](patterns/alternative-wait.md) &nbsp;|&nbsp; **Example:** [examples/alternative-wait/](../examples/alternative-wait/)

## Approval Ladder — Sequential Escalation

An **approval ladder** is a sequential escalation pattern: the workflow tries each approver one at a time, in order. If the first approver does not respond within a deadline, the workflow escalates to the next level. This is distinct from the first-wins pattern above, where all approvers are notified simultaneously and the workflow takes whoever responds first.

The key difference:

| | Alternative Wait (First Wins) | Approval Ladder |
|---|---|---|
| **Approvers notified** | All at once | One at a time, in sequence |
| **Escalation trigger** | N/A — first response wins | Timeout or explicit rejection at each level |
| **Use case** | Any approver is sufficient | Prefer junior approval; escalate only if unresponsive |

### How to Implement

Use a separate data channel per approver level combined with `ctx->await` timeouts:

1. Notify the first-level approver and call `ctx->await` with a deadline (e.g. 24 hours).
2. If `ctx->await` returns an error (timeout), notify the next-level approver and repeat.
3. Continue up the ladder until an approver responds or all levels are exhausted.

Each level targets its own named channel in the events record (e.g. `managerApproval`, `directorApproval`), so responses from different levels are never mixed. A response that arrives after the workflow has already escalated is delivered to its own channel and can be handled (or ignored) by the workflow logic.

When a workflow step requires data from **every** source before it can proceed, use `ctx->await` with the full array of futures. The workflow resumes only after all expected data has arrived.

A common use case is **dual authorization**: both the Operations team and the Compliance team must approve a fund transfer.

```ballerina
@workflow:Workflow
function transferApproval(
    workflow:Context ctx,
    TransferInput input,
    record {|
        future<ApprovalDecision> operationsApproval;
        future<ApprovalDecision> complianceApproval;
    |} events
) returns TransferResult|error {
    string _ = check ctx->callActivity(notifyApprovalTeams, {...});

    // Wait for both — order of arrival doesn't matter
    [ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] =
        check ctx->await([events.operationsApproval, events.complianceApproval]);

    if !opsDecision.approved || !compDecision.approved {
        return {transferId: input.transferId, status: "REJECTED", message: "..."};
    }

    string txnRef = check ctx->callActivity(executeTransfer, {...});
    return {transferId: input.transferId, status: "COMPLETED", message: txnRef};
}
```

`ctx->await` takes an array of futures and, by default, waits for all of them. The return type is a typed tuple whose element types match the corresponding `future<T>` types, so no casting is needed.

**Data arrival order does not matter.** If Compliance sends their decision before Operations, the data is stored by the runtime. `ctx->await` resolves as soon as the last outstanding future completes.

> **Pattern guide:** [patterns/wait-for-all.md](patterns/wait-for-all.md) &nbsp;|&nbsp; **Example:** [examples/wait-for-all/](../examples/wait-for-all/)

## `ctx->await` — Full API Reference

`ctx->await` is the single entry point for all multi-future wait patterns. It replaces the use of Ballerina's `wait` keyword for event futures and adds timeout and quorum support.

### Wait for All (default)

Pass an array of futures — `ctx->await` blocks until every future completes:

```ballerina
[ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] =
    check ctx->await([events.operationsApproval, events.complianceApproval]);
```

`minCount` defaults to the length of the array. The return value is a typed tuple.

### Wait for Any (first wins)

Pass `1` as `minCount` — `ctx->await` returns as soon as the first future completes:

```ballerina
[ApprovalDecision?, ApprovalDecision?] result =
    check ctx->await([events.approverA, events.approverB], 1);
```

When `minCount < futures.length()`, the result is a **positional sparse tuple** of the same length as the input array. Each index corresponds to the original future at that position:

- Completed futures carry their future value.
- Incomplete futures carry `()` (nil).

This ensures callers always know **which** futures completed, regardless of completion order:

```ballerina
ApprovalDecision? a = result[0]; // () if approverA has not responded yet
ApprovalDecision? b = result[1]; // () if approverB has not responded yet
```

This is equivalent to the shared-channel first-wins pattern described above, but lets each approver send to their own named channel. If you prefer the simpler single-channel model (several senders posting to the same name), the `wait events.approval` pattern still works unchanged.

### Quorum (N of M)

Pass any `minCount` between 1 and the array length:

```ballerina
// 2-of-3 quorum — proceed when any two of three validators agree
[ValidationResult?, ValidationResult?, ValidationResult?] results =
    check ctx->await([events.validatorA, events.validatorB, events.validatorC], 2);
```

The result is a 3-element sparse tuple aligned to the input positions. The two completed slots carry `ValidationResult` values; the remaining slot carries `()`. Iterate or check each position to determine which validators responded:

```ballerina
int approvedCount = 0;
foreach ValidationResult? r in results {
    if r is ValidationResult && r.valid {
        approvedCount += 1;
    }
}
```

### With a Timeout

Add a `timeout` named argument using a `time:Duration` record. If the required number of futures have not completed within the given duration, `ctx->await` returns an error:

```ballerina
[ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] =
    check ctx->await(
        [events.operationsApproval, events.complianceApproval],
        timeout = {hours: 48}
    );
```

The timeout participates in Temporal's durable timer infrastructure — if the worker restarts while waiting, the remaining time is preserved across replay.

### Summary

| Pattern | Call | Completes when | Result shape |
|---------|------|----------------|---------------|
| Wait for all | `ctx->await([f1, f2])` | Every future resolves | `[T1, T2]` — all populated |
| Wait for any | `ctx->await([f1, f2], 1)` | First future resolves | `[T1?, T2?]` — nil for incomplete |
| Quorum | `ctx->await([f1, f2, f3], 2)` | N futures resolve | `[T1?, T2?, T3?]` — nil for incomplete |
| With deadline | `ctx->await([f1, f2], timeout = {hours: 48})` | All resolve or timeout | `[T1, T2]` — error on timeout |

### Relationship to the `wait` Keyword

`ctx->await` complements — rather than replaces — the `wait` keyword. For single-future waits (e.g. `check wait events.approval`), the plain `wait` syntax remains idiomatic. `ctx->await` is the right choice when you need to coordinate multiple futures, set a quorum count, or apply a deadline.

## What's Next

- [Alternative Wait](patterns/alternative-wait.md) — First-wins pattern (any-approver, first response wins)
- [Wait for All](patterns/wait-for-all.md) — Collect data from multiple sources before proceeding
- [Human in the Loop](patterns/human-in-the-loop.md) — Pause for a human decision (approve or reject)
- [Forward Recovery](patterns/forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Handle Errors](handle-errors.md) — Error handling patterns

