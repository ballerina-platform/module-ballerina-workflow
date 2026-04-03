# Write Workflow Functions

A workflow function defines the orchestration logic for coordinating activities, handling external data, and reacting to timer events. If you are new to workflows, start with [Key Concepts](key-concepts.md) first.

## Define a Workflow

Annotate a function with `@workflow:Workflow` to mark it as a workflow:

```ballerina
import ballerina/workflow;

@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest input) returns OrderResult|error {
    // Orchestration logic here
}
```

## Function Signature

A workflow function follows this signature pattern:

```ballerina
@workflow:Workflow
function <name>(
    workflow:Context ctx,        // Optional — required for activities, sleep, currentTime, etc.
    <InputType> input,           // Optional — workflow input (anydata subtype)
    record {| future<T>... |} events  // Optional — for receiving external events
) returns <ReturnType>|error { }
```

All three parameters are optional. When present, they must appear in this order: Context, Input, Events. A workflow can have at most 3 parameters.

### Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `workflow:Context ctx` | No | Provides `callActivity`, `sleep`, `currentTime`, `isReplaying`, `getWorkflowId`, and `getWorkflowType` |
| Input | No | Workflow input data. Must be a subtype of `anydata` |
| Events record | No | Record with `future<T>` fields for receiving external data. See [Handle Data](handle-data.md) |

### Return Type

The return type must be a subtype of `anydata` or `error`.

## Call Activities

Activities must be called through `ctx->callActivity()`. The engine uses this to track each call and record its result. Pass arguments as a `map<anydata>` with keys matching the activity's parameter names:

```ballerina
// ✅ Correct
boolean inStock = check ctx->callActivity(checkInventory, {"item": request.item});
```

Calling the activity function directly is a compile error — the engine cannot track or record it:

```ballerina
// ❌ Compile error (WORKFLOW_108)
boolean inStock = check checkInventory(request.item);
```

## Determinism Rules

During recovery, the engine re-executes the workflow function and replays the recorded event history — feeding back the same results for every engine-tracked operation (`callActivity`, `ctx.sleep()`, `ctx.currentTime()`). For recovery to work correctly, the workflow must derive all decision-influencing values through these APIs rather than reading them directly from the system. This is what "determinism" means here: not that the workflow logic is static, but that every value it acts on comes from a recorded, reproducible source.

For example, branching on time is perfectly fine — as long as you use `ctx.currentTime()` (whose value is recorded). Using `time:utcNow()` would return a different value on recovery, causing the workflow to take a different branch.

**Do:**
- Call activities for any external interaction (HTTP, databases, file I/O, external processes)
- Use `ctx.sleep()` for durable delays
- Use `ctx.currentTime()` to read the current time
- Use standard control flow (`if`, `match`, `foreach`)
- Use `wait` on data futures

**Don't:**
- Make direct external calls — HTTP requests, database mutations, file I/O, or invoking external processes — outside an activity
- Use `runtime:sleep()` (use `ctx.sleep()` instead)
- Read system time directly — e.g. `time:utcNow()` (use `ctx.currentTime()` instead)
- Generate random values directly (use an activity so the value is recorded)
- Access mutable global state
- Use `worker`, `fork`, or `start` — see [Unsupported Language Features](#unsupported-language-features)

> **What does the compiler enforce?** Structural violations — direct activity calls, `worker`, `fork`, and `start` inside a workflow — are caught at compile time. Other violations (e.g. a direct HTTP call in workflow code) are not detectable by the compiler; they produce incorrect behavior at runtime during recovery.

### Why External Calls Must Be in Activities

During recovery, the engine re-runs your workflow function to rebuild its state. For every `callActivity` the engine finds in the event history, it returns the previously recorded result *without* calling the activity again. But any code that runs directly in the workflow — outside an activity — has no recorded result and **will execute again on every recovery**.

Consider this example:

```ballerina
// ❌ Dangerous — the HTTP call runs again on every recovery
@workflow:Workflow
function processPayment(workflow:Context ctx, PaymentRequest req) returns PaymentResult|error {
    http:Client paymentGateway = check new ("https://payments.example.com");
    json response = check paymentGateway->post("/charge", req.toJson());
    // If the workflow recovers after this point, the charge is made AGAIN
    check ctx->callActivity(sendReceipt, {"email": req.email});
    return {status: "COMPLETED"};
}
```

```ballerina
// ✅ Correct — the payment call is wrapped in an activity
@workflow:Activity
function chargePayment(string orderId, decimal amount) returns json|error {
    http:Client paymentGateway = check new ("https://payments.example.com");
    return check paymentGateway->post("/charge", {orderId, amount});
}

@workflow:Workflow
function processPayment(workflow:Context ctx, PaymentRequest req) returns PaymentResult|error {
    json response = check ctx->callActivity(chargePayment, {"orderId": req.orderId, "amount": req.amount});
    check ctx->callActivity(sendReceipt, {"email": req.email});
    return {status: "COMPLETED"};
}
```

The same principle applies to any value the workflow acts on: random numbers, environment variables, external configuration. Wrap them in activities so the engine can record and replay the values safely.

## Durable Sleep

Use `ctx.sleep()` for delays that survive restarts:

```ballerina
import ballerina/time;

@workflow:Workflow
function reminderWorkflow(workflow:Context ctx, ReminderInput input) returns error? {
    // Send initial notification
    check ctx->callActivity(sendNotification, {"message": input.message});

    // Wait 24 hours (durable — survives restarts)
    check ctx.sleep({hours: 24});

    // Send follow-up
    check ctx->callActivity(sendNotification, {"message": "Reminder: " + input.message});
}
```

## Check Replay Status

Use `ctx.isReplaying()` to skip side effects during replay:

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    if !ctx.isReplaying() {
        // Only log on first execution, not during replay
        log:printInfo("Starting workflow for: " + input.id);
    }

    string result = check ctx->callActivity(doWork, {"id": input.id});
    return {id: input.id, result: result};
}
```

## Get Workflow Metadata

These methods return `string|error` because they communicate with the engine; use `check` to unwrap the result:

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    string workflowId = check ctx.getWorkflowId();
    string workflowType = check ctx.getWorkflowType();
    // ...
}
```

## Start a Workflow

Use `workflow:run()` to start a new workflow instance:

```ballerina
string workflowId = check workflow:run(processOrder, {
    orderId: "ORD-001",
    item: "laptop",
    quantity: 2
});
```

The returned `workflowId` uniquely identifies the running workflow instance.

## Get Workflow Results

Use `workflow:getWorkflowResult()` to wait for a workflow to complete and retrieve its result:

```ballerina
workflow:WorkflowExecutionInfo result = check workflow:getWorkflowResult(workflowId);
io:println(result.status);  // "COMPLETED", "FAILED", "RUNNING", etc.
io:println(result.result);  // The workflow return value (if completed)
```

Use `workflow:getWorkflowInfo()` to inspect a workflow's current state without waiting for completion:

```ballerina
workflow:WorkflowExecutionInfo info = check workflow:getWorkflowInfo(workflowId);
if info.status == "RUNNING" {
    io:println("Workflow is still running");
}
```

## Unsupported Language Features

Several Ballerina concurrency primitives are **not allowed** inside `@Workflow` functions. These constructs spawn independent execution strands that run outside the workflow scheduler, which the engine cannot track in the event history.

### Named Workers

Named `worker` blocks are rejected with a compile error (`WORKFLOW_118`):

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    // Compile error (WORKFLOW_118) — workers bypass the workflow scheduler
    worker w1 {
        int _ = doSomething();
    }
    // ...
}
```

### Fork Statements

`fork` statements are rejected with a compile error (`WORKFLOW_119`). Use sequential `ctx->callActivity()` calls instead — each call is individually durable and recorded in the event history:

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    // Compile error (WORKFLOW_119) — fork is not allowed
    fork {
        worker w1 { int _ = doA(); }
        worker w2 { int _ = doB(); }
    }

    // Correct — call activities in sequence; each is durably recorded
    string resultA = check ctx->callActivity(doA, {});
    string resultB = check ctx->callActivity(doB, {});
}
```

### Start Actions

The `start` action is rejected with a compile error (`WORKFLOW_120`). Use `ctx->callActivity()` for any work that should run asynchronously:

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    // Compile error (WORKFLOW_120) — start is not allowed
    future<int> f = start doSomething();

    // Correct — use an activity
    string result = check ctx->callActivity(doSomething, {});
}
```

### Why These Restrictions Exist

The workflow runtime records every decision a workflow makes into a persistent event history. During recovery after a failure or restart, the engine walks through this event history to determine the last successfully completed activity, restores the workflow's context to that point, and continues execution from there — without re-executing any previously completed activities. Concurrency primitives (`worker`, `fork`, `start`) spawn strands that the event history does not track, so their outcomes cannot be restored on recovery — leading to unpredictable behaviour and corrupted workflow state.

## What's Next

- [Write Activity Functions](write-activity-functions.md) — Implement activities for I/O operations
- [Handle Data](handle-data.md) — Receive external data in running workflows
- [Handle Errors](handle-errors.md) — Error handling patterns
