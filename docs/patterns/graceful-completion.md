# Pattern: Graceful Completion — Tolerate Non-Critical Failures

Not every activity failure should stop a workflow. When an activity is a side effect — a notification, an audit log, a cache warm-up — its failure should be noted and skipped rather than used to fail the workflow. **Graceful completion** separates critical steps (propagated with `check`) from non-critical ones (captured as `T|error`).

> **Runnable example:** [`examples/graceful-completion/`](../../examples/graceful-completion/)

## When to Use

- The workflow has a clear primary outcome (reservation, payment, record creation) and secondary side effects (email, audit log, analytics event).
- The business outcome is valid even if a side effect could not be delivered.
- You want to record which steps were skipped so the result is transparent.

## Code Pattern

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns string|error {
    // CRITICAL — propagate failure. The workflow cannot complete without inventory.
    string reservationId = check ctx->callActivity(reserveInventory, {
        "orderId": input.orderId,
        "item": input.item,
        "quantity": input.quantity
    });

    // NON-CRITICAL — tolerate failure. Retry once; skip if still failing.
    string|error emailResult = ctx->callActivity(sendConfirmationEmail,
            {"email": input.customerEmail, "orderId": input.orderId},
            retryPolicy = {maxRetries: 1, retryDelay: 1.0});

    // NON-CRITICAL — tolerate failure. Retry once; skip if still failing.
    string|error auditResult = ctx->callActivity(writeAuditLog,
            {"orderId": input.orderId, "reservationId": reservationId},
            retryPolicy = {maxRetries: 1, retryDelay: 1.0});

    // Build result, noting any skipped steps
    string[] skipped = [];
    if emailResult is error { skipped.push("email"); }
    if auditResult is error { skipped.push("audit"); }

    string suffix = skipped.length() > 0
        ? string ` (skipped: ${string:'join(", ", ...skipped)})`
        : "";
    return reservationId + suffix;
}
```

## The Distinction That Matters

| Activity role | Return handling | Workflow outcome on failure |
|--------------|----------------|----------------------------|
| Critical | `check` | Workflow fails immediately |
| Non-critical | `T|error` variable | Workflow continues and completes |

The same activity can be critical in one workflow and non-critical in another — the distinction is a business decision, not a property of the activity itself.

## Surfacing Skipped Steps

The example above encodes skipped step names into the return value. An alternative is to use a structured result type:

```ballerina
type OrderResult record {|
    string orderId;
    string status;
    string[] skippedSteps;
|};

// ...
return {
    orderId: input.orderId,
    status: "COMPLETED",
    skippedSteps: skipped
};
```

This makes it easy for callers to inspect and log skipped steps without parsing strings.

## What's Next

- [Handle Errors](../handle-errors.md) — Pattern overview and comparison
- [Propagate Pattern](error-propagation.md) — Fail immediately when a critical activity fails
- [Fallback Pattern](error-fallback.md) — Try an alternative rather than skipping
