# Pattern: Fallback — Try an Alternative

When a primary activity exhausts its retries, capture the error as a value and call a secondary activity instead. The workflow completes successfully via the fallback path.

> **Runnable example:** [`examples/error-fallback/`](../../examples/error-fallback/)

## When to Use

- The goal can be achieved by more than one means (e.g., email → SMS, primary API → backup API, real-time service → cached result).
- The primary is preferred but not required — a fallback result is acceptable to the business.
- You want the workflow engine to automatically retry transient failures on the primary before the workflow even knows about the failure.

## Code Pattern

```ballerina
@workflow:Workflow
function sendNotification(workflow:Context ctx, NotificationInput input) returns string|error {
    // Try the primary with retries for transient failures
    string|error emailResult = ctx->callActivity(sendEmail,
            {"to": input.email, "message": input.message},
            retryOnError = true, maxRetries = 2, retryDelay = 1.0, retryBackoff = 2.0);

    if emailResult is error {
        // All retries exhausted — fall back to SMS
        // `check` here means: if SMS also fails, propagate and fail the workflow
        return check ctx->callActivity(sendSms,
                {"phone": input.phone, "message": input.message});
    }

    return emailResult;
}
```

The key distinction:
- The **primary** uses `string|error` (not `check`) so the error is captured as a value after retries.
- The **fallback** uses `check` — if the fallback also fails, there is no further alternative, so the workflow fails.

## Chaining Multiple Fallbacks

Apply the same pattern for each additional tier:

```ballerina
string|error emailResult = ctx->callActivity(sendEmail, {...},
        retryOnError = true, maxRetries = 2, retryDelay = 1.0);
if emailResult is error {
    string|error smsResult = ctx->callActivity(sendSms, {...},
            retryOnError = true, maxRetries = 1, retryDelay = 1.0);
    if smsResult is error {
        // Final fallback — if this fails the workflow fails
        return check ctx->callActivity(createSupportTicket, {...});
    }
    return smsResult;
}
return emailResult;
```

## Retry Timing

With `retryDelay = 1.0` and `retryBackoff = 2.0`:

| Attempt | Delay before attempt |
|---------|----------------------|
| 1st (initial) | none |
| 2nd (retry 1) | 1.0 s |
| 3rd (retry 2) | 2.0 s |

The fallback activity is only called after all primary attempts have been recorded in the workflow's Event History.

## What's Next

- [Handle Errors](../handle-errors.md) — Pattern overview and comparison
- [Compensation Pattern](error-compensation.md) — Undo committed steps after a failure
- [Graceful Completion](graceful-completion.md) — Tolerate non-critical failures entirely
