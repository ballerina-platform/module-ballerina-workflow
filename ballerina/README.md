# Ballerina Workflow Library

The `ballerina/workflow` library provides durable, fault-tolerant workflow orchestration for Ballerina applications. It lets you define long-running business processes — spanning minutes, hours, or days — that automatically recover from crashes and process restarts without losing progress.

## Overview

Workflows and activities are ordinary Ballerina functions:

- **`@workflow:Workflow`** — A durable function that orchestrates a business process. The runtime checkpoints every step and replays recorded history to recover from failures.
- **`@workflow:Activity`** — A function that performs a single non-deterministic operation (API call, database query, email send). Once an activity completes, its result is recorded and never re-executed during replay.

```ballerina
import ballerina/workflow;

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    // Call external inventory API
    return true;
}

@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    boolean inStock = check ctx->callActivity(checkInventory, {"item": request.item});
    if !inStock {
        return {orderId: request.orderId, status: "OUT_OF_STOCK"};
    }
    return {orderId: request.orderId, status: "COMPLETED"};
}
```

## Starting a Workflow

Use `workflow:run()` to start a workflow instance from any entry point — HTTP service, scheduled job, message consumer, or `main`:

```ballerina
string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop"});
```

## Receiving External Data

A workflow can pause and wait for external input — approvals, payment confirmations, user decisions — using future-based event records. Send data to a running workflow with `workflow:sendData()`:

```ballerina
// In the workflow — wait for a human decision
ApprovalDecision decision = check wait events.approval;

// From outside — deliver the decision
check workflow:sendData(processOrder, workflowId, "approval", {approverId: "mgr-1", approved: true});
```

### Multi-Future Waits with `ctx->await`

Use `ctx->await` to wait for multiple futures at once, with optional quorum and timeout:

| Pattern | Example |
|---------|---------|
| Wait for all | `ctx->await([f1, f2])` |
| Wait for any (first wins) | `ctx->await([f1, f2], 1)` |
| Quorum (N of M) | `ctx->await([f1, f2, f3], 2)` |
| With deadline | `ctx->await([f1, f2], timeout = {hours: 48})` |

## Error Handling

Activity errors are returned as plain Ballerina values. The workflow decides what happens next:

```ballerina
string|error result = ctx->callActivity(chargeCard, {"amount": input.amount});
if result is error {
    // retry with a different card, fall back, or compensate
}
```

Enable automatic retries for transient failures:

```ballerina
string result = check ctx->callActivity(chargeCard, {"amount": input.amount},
    retryOnError = true, maxRetries = 3);
```

## Configuration

Add a `Config.toml` to your project. For local development with no server:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"
```

For production, connect to a Temporal server:

```toml
[ballerina.workflow]
mode = "TEMPORAL"
temporalHost = "localhost"
temporalPort = 7233
namespace = "default"
taskQueue = "my-task-queue"
```

## Documentation

| Guide | Description |
|-------|-------------|
| [Get Started](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/get-started.md) | Write and run your first workflow |
| [Key Concepts](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/key-concepts.md) | Workflows, activities, external data, and timers |
| [Write Workflow Functions](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/write-workflow-functions.md) | Signatures, determinism rules, and durable sleep |
| [Write Activity Functions](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/write-activity-functions.md) | Activity patterns and retry options |
| [Handle Data](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/handle-data.md) | Waiting for external input and sending data |
| [Handle Errors](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/handle-errors.md) | Propagation, retry, fallback, and compensation |
| [Configure the Module](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/configure-the-module.md) | Connection settings, TLS, and namespaces |

## Examples

| Example | Description |
|---------|-------------|
| [Get Started](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/get-started) | First workflow |
| [Order Processing](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/order-processing) | HTTP-triggered workflow with result polling |
| [Human in the Loop](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/human-in-the-loop) | Pause for a human approval |
| [Wait for All](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/wait-for-all) | Dual authorization — both teams must approve |
| [Alternative Wait](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/alternative-wait) | First responder wins (approval ladder) |
| [Forward Recovery](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/forward-recovery) | Pause for corrected data and retry |
| [Error Propagation](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-propagation) | Fail the workflow on a critical error |
| [Error Fallback](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-fallback) | Fall back to a secondary activity |
| [Error Compensation](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-compensation) | Saga: undo committed steps on failure |
