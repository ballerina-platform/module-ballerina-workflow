# Key Concepts

This page introduces the core concepts of the Ballerina Workflow module. Understanding these concepts will help you design and build durable workflows effectively.

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
- **Automatically retried** — If an activity fails, the runtime retries it based on a configurable retry policy (number of attempts, backoff intervals, etc.).
- **Called via `ctx->callActivity()`** — Activities cannot be called directly from a workflow. The `callActivity` remote method ensures the runtime can track and replay activity executions.

```ballerina
string result = check ctx->callActivity(sendEmail, {
    "to": "user@example.com",
    "subject": "Order Confirmed"
});
```

Learn more: [Write Activity Functions](write-activity-functions.md)

## Data Events

A **data event** delivers external data to a running workflow. Workflows can pause and wait for data events using Ballerina's `wait` keyword with future-based event records.

Define event types and declare them in the workflow's events parameter:

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

Send a data event to a running workflow from outside:

```ballerina
check workflow:sendData(orderWithApproval, workflowId, "approval", {
    approved: true,
    approverName: "Alice"
});
```

The field name in the events record (`approval`) maps directly to the data event name used in `sendData()`.

Learn more: [Handle Events](handle-events.md)

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

**Message consumer:**
```ballerina
service on kafkaListener {
    remote function onConsumerRecord(kafka:ConsumerRecord[] records) returns error? {
        foreach var record in records {
            OrderRequest request = check record.value.fromJsonStringWithType();
            _ = check workflow:run(processOrder, request);
        }
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

## What's Next

- [Write Workflow Functions](write-workflow-functions.md) — Signatures, determinism rules, and durable sleep
- [Write Activity Functions](write-activity-functions.md) — Activity patterns and retry configuration
- [Handle Events](handle-events.md) — Data events and event-driven patterns
- [Configure the Module](configure-the-module.md) — Deployment modes and server configuration
