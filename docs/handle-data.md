# Handle Data

Workflows can receive external data while running using future-based data handling. This allows a workflow to pause and wait for data — such as approvals, payments, or user actions — before continuing execution.

## Overview

1. Define data types as records
2. Add an events parameter to the workflow function with `future<T>` fields
3. Use Ballerina's `wait` keyword to pause until data arrives
4. Send data to running workflows using `workflow:sendData()`

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

A common use case is the **approval ladder**: multiple approvers are notified, and whichever responds first unblocks the workflow.

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

## Wait for All — Collect Multiple Data

When a workflow step requires data from **every** source before it can proceed, wait for each future sequentially. The workflow resumes only after all expected data has arrived.

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
    ApprovalDecision opsDecision = check wait events.operationsApproval;
    ApprovalDecision compDecision = check wait events.complianceApproval;

    if !opsDecision.approved || !compDecision.approved {
        return {transferId: input.transferId, status: "REJECTED", message: "..."};
    }

    string txnRef = check ctx->callActivity(executeTransfer, {...});
    return {transferId: input.transferId, status: "COMPLETED", message: txnRef};
}
```

**Data arrival order does not matter.** If Compliance sends their decision before Operations, the data is stored by the runtime. When the Operations wait completes, the Compliance wait resolves immediately because the data is already available.

> **Pattern guide:** [patterns/wait-for-all.md](patterns/wait-for-all.md) &nbsp;|&nbsp; **Example:** [examples/wait-for-all/](../examples/wait-for-all/)

## Timeout for Waiting Data

> **Planned feature.** Support for timing out data waits — for example, auto-rejecting an approval that has not arrived within 48 hours — will be added in a future release. The intended approach is to race a data future against a durable timer using Ballerina's alternate wait (`wait dataFuture|timerFuture`). Until this is available, use an external deadline (e.g., a scheduled job) that sends a timeout to the waiting workflow via `workflow:sendData()`.

## What's Next

- [Alternative Wait](patterns/alternative-wait.md) — First-wins pattern (approval ladder)
- [Wait for All](patterns/wait-for-all.md) — Collect data from multiple sources before proceeding
- [Human in the Loop](patterns/human-in-the-loop.md) — Pause for a human decision (approve or reject)
- [Forward Recovery](patterns/forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Handle Errors](handle-errors.md) — Error handling patterns

