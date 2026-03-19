# Pattern: Propagate — Fail the Workflow

Use `check` to propagate an activity error immediately. The workflow transitions to **Failed**, all subsequent steps are skipped, and the error is returned to whoever called `workflow:getWorkflowResult()`.

> **Runnable example:** [`examples/error-propagation/`](../../examples/error-propagation/)

## When to Use

- The failed activity is a prerequisite for everything that follows (e.g., inventory check before order confirmation, authentication before any business step).
- There is no meaningful recovery — the workflow cannot produce a useful result without this step succeeding.
- You want the failure to be immediately visible as a **Failed** workflow with a clear error message.

## Code Pattern

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // If checkInventory returns an error, `check` propagates it immediately.
    // The workflow is marked Failed. confirmOrder is never called.
    boolean _ = check ctx->callActivity(checkInventory, {"item": input.item});

    string _ = check ctx->callActivity(confirmOrder, {
        "orderId": input.orderId,
        "item": input.item
    });

    return {orderId: input.orderId, status: "COMPLETED"};
}
```

The `check` expression is the entire mechanism — it unwraps `T|error` to `T` on success, and returns the error to the caller on failure. There is no special workflow API involved.

## How the Error Appears in the Workflow Engine

When the workflow fails via `check`:
- The workflow engine records an `ActivityTaskFailed` event for the failing activity.
- It records a `WorkflowExecutionFailed` event on the workflow.
- The full error message, type, and any detail fields are serialized into the failure payload and visible in the workflow engine's Web UI under **Event History**.

The caller sees the failure when it calls `workflow:getWorkflowResult()`:

```ballerina
workflow:WorkflowExecutionInfo info = check workflow:getWorkflowResult(workflowId);
if info.status == "FAILED" {
    // info.errorMessage contains the activity error message
    io:println("Workflow failed: " + (info.errorMessage ?: "unknown error"));
}
```

## Retries Before Propagation

If the activity is transient (network call, external API), combine retries with propagation. The workflow only fails after all retries are exhausted:

```ballerina
// The workflow engine retries up to 3 times; if all fail, check propagates the final error
boolean _ = check ctx->callActivity(checkInventory, {"item": input.item},
        retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
```

## What's Next

- [Handle Errors](../handle-errors.md) — Pattern overview and comparison
- [Fallback Pattern](error-fallback.md) — Try an alternative when the primary fails
- [Compensation Pattern](error-compensation.md) — Undo committed steps after a failure
