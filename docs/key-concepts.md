# Key Concepts

Workflows are **durable** and **long-running** — they can span minutes, days, or even months. Unlike a regular program that loses its state on a crash or restart, a workflow is **resilient to failures**: the workflow engine recovers it automatically and continues from exactly where it stopped, without re-running completed steps.

This resilience is built on **replay**. The workflow engine records the outcome of every step — every activity result, every external event, every timer completion. After a crash, a program restart, or a long sleep, the engine replays that recorded history to restore the workflow to its last known state. Your workflow code runs as if it never stopped.

In Ballerina, workflows and activities are ordinary functions:

- [**Workflows**](#workflows) — A durable function annotated with `@workflow:Workflow` that orchestrates a business process by calling activities and reacting to events.
- [**Activities**](#activities) — Functions annotated with `@workflow:Activity` that perform the real work: API calls, database queries, sending emails.
- [**External Data**](#external-data) — Data sent into a running workflow from outside — approvals, payment notifications, user decisions.
- [**Timer Events**](#timer-events) — Durable pauses that survive program restarts and continue counting down from the right point.

Workflows can be started from any integration point — HTTP services, message consumers, or scheduled jobs — using `workflow:run()`. External data can similarly be sent into a running workflow from any integration point using `workflow:sendData()`.

## Workflows

A **workflow** is a durable function that orchestrates a business process. The runtime automatically checkpoints workflow state so it can recover from failures and continue where it left off.

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    boolean inStock = check ctx->callActivity(checkInventory, {"item": request.item});
    if !inStock {
        return {orderId: request.orderId, status: "OUT_OF_STOCK"};
    }
    check ctx->callActivity(reserveStock, {"orderId": request.orderId, "item": request.item});
    return {orderId: request.orderId, status: "COMPLETED"};
}
```

Workflows must be **deterministic** — given the same inputs and history, they must produce the same sequence of operations. This is what makes replay and recovery possible. All non-deterministic work (I/O, API calls, random values) belongs in activities.

Learn more: [Write Workflow Functions](write-workflow-functions.md)

## How Workflows Are Triggered

Workflow logic is independent of the protocol that triggers it. You start a workflow by calling `workflow:run()` — this can happen from any entry point:

**HTTP endpoint:**
```ballerina
service /orders on new http:Listener(9090) {
    resource function post .(OrderRequest request) returns json|error {
        string workflowId = check workflow:run(processOrder, request);
        return {workflowId: workflowId};
    }
}
```

**Main function:**
```ballerina
public function main() returns error? {
    string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop"});
}
```

**Multiple entry points for the same workflow:**

The same workflow can be triggered from different protocols simultaneously. For example, an order workflow could be started from an HTTP API, a Kafka consumer, or a scheduled job — the workflow logic remains the same.

## Activities

An **activity** is a function that performs a single, non-deterministic operation — such as calling an API, querying a database, or sending an email. Activities are the building blocks that workflows orchestrate.

```ballerina
@workflow:Activity
function checkInventory(string item) returns boolean|error {
    // Call external inventory API
    return true;
}
```

Activities are:

- **Executed exactly once** — Even if the workflow replays, a completed activity is not re-executed. The runtime returns the recorded result instead.
- **Errors returned as values by default** — If an activity fails, the error is returned to the workflow as a normal return value so the workflow can handle it with its own logic. No automatic retries occur unless you explicitly opt in with `retryOnError = true`.
- **Optionally retried** — Pass `retryOnError = true, maxRetries = 3` to `callActivity` to enable automatic retries with configurable backoff. See [Write Activity Functions](write-activity-functions.md) for details.
- **Called via `ctx->callActivity()`** — Activities cannot be called directly from a workflow. The `callActivity` remote method ensures the runtime can track and replay activity executions.

```ballerina
string result = check ctx->callActivity(sendEmail, {
    "to": "user@example.com",
    "subject": "Order Confirmed"
});
```

Learn more: [Write Activity Functions](write-activity-functions.md)

## External Data

Workflows can receive **external data** while running. A workflow pauses and waits for data — such as approvals, payments, or user actions — using Ballerina's `wait` keyword with future-based records.

Define data types and declare them in the workflow's events parameter:

```ballerina
type ApprovalDecision record {|
    boolean approved;
    string approverName;
|};

@workflow:Workflow
function orderWithApproval(
    workflow:Context ctx,
    OrderRequest request,
    record {| future<ApprovalDecision> approval; |} events
) returns string|error {
    check ctx->callActivity(notifyApprover, {"orderId": request.orderId});

    ApprovalDecision decision = check wait events.approval;

    if decision.approved {
        return "Order approved by " + decision.approverName;
    }
    return "Order rejected";
}
```

Send data to a running workflow from outside:

```ballerina
check workflow:sendData(orderWithApproval, workflowId, "approval", {
    approved: true,
    approverName: "Alice"
});
```

The field name in the events record (`approval`) maps directly to the data name used in `sendData()`.

Learn more: [Handle Data](handle-data.md)

## How Events Are Sent

Like workflow triggers, external data can be delivered to a running workflow from any integration point using `workflow:sendData()`:

**HTTP endpoint:**
```ballerina
service /approvals on new http:Listener(9090) {
    resource function post [string workflowId](ApprovalDecision decision) returns json|error {
        check workflow:sendData(orderWithApproval, workflowId, "approval", decision);
        return {status: "sent"};
    }
}
```

The data name (`"approval"`, `"payment"`) must match the field name declared in the workflow's events record. The workflow resumes automatically once the expected data arrives.

## Timer Events

A **timer event** pauses the workflow for a specified duration. Unlike a regular sleep, a workflow timer is durable — it survives program restarts and continues counting down.

```ballerina
import ballerina/time;

@workflow:Workflow
function reminderWorkflow(workflow:Context ctx, ReminderInput input) returns error? {
    check ctx->callActivity(sendNotification, {"message": input.message});

    check ctx.sleep({hours: 24});

    check ctx->callActivity(sendNotification, {"message": "Reminder: " + input.message});
}
```

Timer events are useful for:
- Scheduled follow-ups and reminders
- Timeout logic (e.g., cancel an order if not paid within 30 minutes)
- Periodic polling patterns

## What's Next

- [Write Workflow Functions](write-workflow-functions.md) — Signatures, determinism rules, and durable sleep
- [Write Activity Functions](write-activity-functions.md) — Activity patterns and retry configuration
- [Handle Data](handle-data.md) — Receiving external data and data-driven patterns
- [Configure the Module](configure-the-module.md) — Deployment modes and server configuration
