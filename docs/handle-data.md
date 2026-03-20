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

## What's Next

- [Human in the Loop](patterns/human-in-the-loop.md) — Pause for a human decision (approve or reject)
- [Forward Recovery](patterns/forward-recovery.md) — Pause for corrected data and retry a failed activity
- [Handle Errors](handle-errors.md) — Error handling patterns

