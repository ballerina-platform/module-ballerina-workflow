# Pattern: Compensation (Saga) — Undo Completed Steps

When a multi-step workflow partially succeeds and a later step fails, run **compensating activities** in reverse order to undo the work that was already committed. This is the Saga pattern for distributed transactions.

> **Runnable example:** [`examples/error-compensation/`](../../examples/error-compensation/)

## When to Use

- The workflow makes changes across multiple independent services or data stores (e.g., debit account → credit account, reserve inventory → release inventory).
- A single database transaction cannot span all the steps.
- Rolling back to a consistent state is preferable to leaving partial changes in place.
- Each step that commits work has a defined undo operation.

## Code Pattern

```ballerina
@workflow:Workflow
function transferFunds(workflow:Context ctx, TransferInput input) returns string|error {
    // Step 1: complete the debit. Use `check` — if this fails there is nothing to compensate.
    string _ = check ctx->callActivity(debitAccount, {
        "accountId": input.sourceAccount,
        "amount": input.amount
    });

    // Step 2: capture as T|error so we can compensate on failure.
    string|error creditResult = ctx->callActivity(creditAccount, {
        "accountId": input.destAccount,
        "amount": input.amount
    }, retryOnError = true, maxRetries = 2, retryDelay = 1.0, retryBackoff = 2.0);

    if creditResult is error {
        // Step 2 exhausted retries — compensate by reversing step 1.
        // Use `check` — if compensation itself fails, the workflow fails so an operator can act.
        string _ = check ctx->callActivity(reverseDebit, {
            "accountId": input.sourceAccount,
            "amount": input.amount
        });
        return string `Transfer ${input.transferId} ROLLED_BACK`;
    }

    return string `Transfer ${input.transferId} COMPLETED`;
}
```

## Designing Compensating Activities

A compensating activity must be:
- **Ideally idempotent** — safest when compensation may be retried.
- **Always succeeds** — if compensation can also fail, configure it with retries or handle the error explicitly. A failed compensation usually requires manual intervention.
- **Semantically correct** — it undoes the specific committed change, not just a generic rollback.

Compensation is different from ACID rollback. A compensating action may introduce new side effects (fees, cancellation penalties, audit events) while restoring acceptable business consistency.

If strict idempotency is not achievable, document the risk and define operational runbooks for manual review and repair using workflow history.

## Scaling to More Steps

For N steps, track compensations as each step succeeds and execute them in reverse order on failure:

```ballerina
// Step 1 completed
string _ = check ctx->callActivity(step1, {...});

// Step 2 captured so it can fail and still allow compensation of step1
string|error step2Result = ctx->callActivity(step2, {...});
if step2Result is error {
    string _ = check ctx->callActivity(compensateStep1, {...});
    return "ROLLED_BACK";
}

// Step 2 completed

// Step 3 — capture as T|error
string|error step3Result = ctx->callActivity(step3, {...});

if step3Result is error {
    // Compensate in reverse: step 2 first, then step 1
    string _ = check ctx->callActivity(compensateStep2, {...});
    string _ = check ctx->callActivity(compensateStep1, {...});
    return "ROLLED_BACK";
}
return "COMPLETED";
```

## Durability Under Failures

Because every activity call — including the compensating activities — is a fully durable activity, the compensation steps survive worker restarts. If the process crashes mid-compensation, the workflow replays from its Event History and continues compensating from where it left off.

## What's Next

- [Handle Errors](../handle-errors.md) — Pattern overview and comparison
- [Human in the Loop](human-in-the-loop.md) — Escalate to a reviewer instead of rolling back
- [Fallback Pattern](error-fallback.md) — Try an alternative rather than undoing work
