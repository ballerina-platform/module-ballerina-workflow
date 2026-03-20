# Write Workflow Functions

A workflow function defines the orchestration logic for a durable business process. It coordinates activities, handles events, and manages the overall flow of work.

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
| `workflow:Context ctx` | Only if using runtime APIs | Provides `callActivity`, `sleep`, `currentTime`, `isReplaying`, `getWorkflowId`, and `getWorkflowType` |
| Input | No | Workflow input data. Must be a subtype of `anydata` |
| Events record | No | Record with `future<T>` fields for receiving external data. See [Handle Data](handle-data.md) |

### Return Type

The return type must be a subtype of `anydata` or `error`.

## Call Activities

Activities **must** be called using `ctx->callActivity()`. Direct calls to `@Activity` functions inside a workflow produce a compile error.

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    // Correct — use ctx->callActivity()
    string result = check ctx->callActivity(sendEmail, {"to": input.email, "subject": "Hello"});

    // Compile error (WORKFLOW_108) — direct calls not allowed
    // string result = check sendEmail(input.email, "Hello");
}
```

Pass arguments as a `map<anydata>`:

```ballerina
InventoryStatus status = check ctx->callActivity(checkInventory, {
    "item": request.item,
    "quantity": request.quantity
});
```

## Determinism Rules

Workflow functions must be **deterministic** — given the same inputs and history, they must produce the same sequence of operations. The runtime may replay a workflow from its history at any time.

**Do:**
- Call activities for I/O operations
- Use `ctx.sleep()` for durable delays
- Use standard control flow (`if`, `match`, `foreach`)
- Use `wait` on data futures

**Don't:**
- Make HTTP calls or access databases directly (use activities)
- Use `runtime:sleep()` (use `ctx.sleep()` instead)
- Generate random values (use activities)
- Read system time for decisions (use `ctx.currentTime()` instead)
- Access mutable global state
- Use `worker`, `fork`, or `start` — see [Unsupported Language Features](#unsupported-language-features)

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

Several Ballerina concurrency primitives are **not allowed** inside `@Workflow` functions. These constructs spawn independent execution strands that run outside the workflow scheduler, breaking the deterministic event-history replay that the runtime depends on.

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

`fork` statements are rejected with a compile error (`WORKFLOW_119`). Use sequential `ctx->callActivity()` calls instead — activities are individually durable and the runtime already handles parallel scheduling where possible:

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

The workflow runtime records every decision a workflow makes into an event history. During failures or restarts, the workflow is **replayed** from this history to restore its exact state. Concurrency primitives (`worker`, `fork`, `start`) spawn strands that the event history does not track, so their outcomes are unpredictable on replay — producing different results from the original execution and corrupting workflow state.

## What's Next

- [Write Activity Functions](write-activity-functions.md) — Implement activities for I/O operations
- [Handle Data](handle-data.md) — Receive external data in running workflows
- [Handle Errors](handle-errors.md) — Error handling patterns
