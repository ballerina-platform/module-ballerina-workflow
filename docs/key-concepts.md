# Key Concepts

## What Is a Workflow?

A **workflow** is a process made up of individual steps — called **activities** — that the system coordinates as a single unit of work. Each activity performs a concrete task: calling an API, database manipulation, sending a notification. The workflow ties these activities together, deciding what to run, in what order, and what to do when something fails.

Many real-world processes are **long-running**. An order might wait hours for payment confirmation. A loan application might wait days for a human approval. A subscription renewal might sleep for an entire month before waking up to charge the next cycle. Between these steps, the workflow may need to **wait for external input** — an approval decision, a payment callback, a user action — or **pause on a timer** before continuing.

Because these processes span such long durations, failures are inevitable — servers restart, deployments roll out, networks drop. A workflow engine makes these processes **durable**: it guarantees that a workflow survives failures and continues from where it left off, without re-running steps that already completed successfully.

## Workflows in Ballerina

The `workflow` module brings durable execution capabilities to Ballerina, powered by [Temporal.io](https://temporal.io) as the underlying workflow engine. Rather than introducing an entirely new programming model, it maps workflow concepts to familiar Ballerina constructs — functions, annotations, records, and futures — so you can write workflows using syntax you already know.

The core building blocks are:

- [**Workflows**](#workflows) — A durable function annotated with `@workflow:Workflow` that orchestrates steps of a business process by calling activities and reacting to events.
- [**Activities**](#activities) — Functions annotated with `@workflow:Activity` that perform the real work: API calls, database manipulations, sending emails.
- [**External Data**](#external-data) — Data sent into a running workflow from outside — approvals, payment notifications, user decisions.
- [**Timer Events**](#timer-events) — Durable pauses that survive program restarts and continue counting down from the right point.

Workflows can be started from any integration point — HTTP services, message consumers, or scheduled jobs — using `workflow:run()`. External data can similarly be sent into a running workflow from any integration point using `workflow:sendData()`.

## How Durability Works

The workflow engine sits between your code and all external interactions. Every operation — starting an activity, receiving external data, completing a timer — goes through the engine first. The engine persists each operation's outcome into a durable log called the **event history** before dispatching it for execution. After a crash, a program restart, or a long sleep, the engine uses this event history to restore the workflow's state. Completed steps are **not re-executed**; the engine simply returns their previously recorded results. From your workflow code's perspective, execution continues as if nothing happened.

Because the engine mediates all interactions, it can safely handle events that arrive while a workflow is recovering. External data sent during recovery is stored in the event history and delivered to the workflow once recovery completes. Similarly, if a timer expires during recovery, the engine recognizes it immediately and the workflow continues without additional delay.

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

Workflows must be **deterministic** — given the same inputs and event history, they must produce the same order of operations. This is what makes recovery possible. Determinism is achievable because all non-deterministic work (I/O, API calls, random values, time) is performed through engine-tracked APIs — activities and context methods — rather than directly in workflow code.

> **Why not call HTTP clients or databases directly in a workflow?**
>
> During recovery, the engine re-runs your workflow code to rebuild its state, but it skips completed activities by returning their recorded results. Code that is *not* wrapped in an activity — such as a direct `http->get()` or `db->execute()` call — has no recorded result, so **it will execute again every time the workflow recovers**. This can lead to duplicate API calls, repeated payment charges, or other unintended side-effects. Always wrap external calls in an `@workflow:Activity` function so the engine can track and skip them on recovery.

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

The same workflow model can be triggered from different protocols. For example, an order workflow could be started from an HTTP API, a Kafka consumer, or a scheduled job — the workflow logic remains the same.

## Activities

An **activity** is a function that performs a single unit of work — such as calling an API, querying a database, or sending an email. Activities are the building blocks that workflows orchestrate.

```ballerina
@workflow:Activity
function checkInventory(string item) returns boolean|error {
    // Call external inventory API
    return true;
}
```

Activities are:

- **Results are recorded per call** — Each time a workflow calls an activity via `ctx->callActivity()`, the engine records its result. If the workflow recovers after a crash, the engine returns the recorded result for that call without re-executing the activity. If the same activity is called multiple times (e.g., inside a loop), each call is tracked independently. In rare edge cases an activity may execute more than once for a single call — see [Write Activity Functions](write-activity-functions.md) for guidance on handling this safely.
- **No automatic retries** — If an activity fails, the error is returned to the workflow so it can decide what to do. Automatic retries only happen if you explicitly opt in. See [Write Activity Functions](write-activity-functions.md) for details.
- **Called via `ctx->callActivity()`** — Activities cannot be called directly from a workflow. The `callActivity` remote method ensures the runtime can track activity executions and restore their results on recovery.

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

## How Data Is Sent

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

> **What if data arrives while the workflow is recovering?** The engine stores all incoming data in the event history regardless of the workflow's current state. If data arrives during recovery, it is persisted and delivered to the workflow as soon as recovery completes.

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

> **What if a timer expires during recovery?** The engine tracks timer deadlines in the event history. If a timer's duration elapses while the workflow is recovering, the engine recognizes the expiry immediately upon recovery — the workflow does not wait again.

## What's Next

- [Write Workflow Functions](write-workflow-functions.md) — Signatures, determinism rules, and durable sleep
- [Write Activity Functions](write-activity-functions.md) — Activity patterns and retry configuration
- [Handle Data](handle-data.md) — Receiving external data and data-driven patterns
- [Configure the Module](configure-the-module.md) — Deployment modes and server configuration
