# Pattern: Forward Recovery

When an activity fails and automated retries cannot resolve the problem, pause the workflow and wait for a human to supply **corrected data**, then retry the failed activity with the updated values. Instead of rolling back or failing, the workflow holds its durable state and moves forward once the user fixes the problem.

> **Runnable example:** [`examples/forward-recovery/`](../../examples/forward-recovery/) — payment fails, user sends corrected card details, workflow retries and succeeds.

## When to Use

- An activity fails with data that a human can correct (wrong card, invalid address, bad input).
- Rolling back is undesirable (e.g., inventory is already reserved, a physical action has occurred).
- You want the corrected data and the retry to be part of the durable workflow history.
- The resolution may take minutes, hours, or days — and the workflow must survive process restarts during that time.

## Code Pattern

### Declare the Correction Type and Workflow Signature

```ballerina
type PaymentCorrection record {|
    string cardToken;
    decimal? amount;     // nil = keep original amount
|};

@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentCorrection> paymentRetry; |} events
) returns OrderResult|error {
```

The third parameter is the events record. The field name (`paymentRetry`) is the data name used when sending corrected data to this workflow.

### Attempt the Activity — Pause on Failure for Corrected Data

```ballerina
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentCorrection> paymentRetry; |} events
) returns OrderResult|error {
    // Attempt payment
    string|error paymentResult = ctx->callActivity(processPayment, {
        "cardToken": input.cardToken,
        "amount": input.amount
    });

    if paymentResult is error {
        // Notify the user that payment failed
        string _ = check ctx->callActivity(notifyPaymentFailure, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow pauses here until the user sends corrected payment details
        PaymentCorrection correction = check wait events.paymentRetry;

        // Retry with corrected values
        string txnId = check ctx->callActivity(processPayment, {
            "cardToken": correction.cardToken,
            "amount": correction.amount ?: input.amount
        });
        return {orderId: input.orderId, status: "COMPLETED", message: txnId};
    }

    return {orderId: input.orderId, status: "COMPLETED", message: paymentResult};
}
```

### Send Corrected Data from an HTTP Endpoint

```ballerina
service /api on new http:Listener(8090) {
    resource function post orders/[string workflowId]/retryPayment(PaymentCorrection correction)
            returns json|error {
        check workflow:sendData(processOrder, workflowId, "paymentRetry", correction);
        return {status: "correction received"};
    }
}
```

`workflow:sendData` delivers the corrected data immediately. The workflow resumes from the `wait events.paymentRetry` point and retries the activity with the new values.

## Durability While Paused

While the workflow is waiting at `wait events.paymentRetry`:
- Worker process restarts do not lose the paused state — the workflow replays its Event History and returns to the `wait` point.
- The `notifyPaymentFailure` activity is not re-executed on replay — its result is already in the history.
- The corrected data can also be sent directly from the workflow engine's Web UI without going through your application's HTTP API (useful during incidents).

## How It Differs from Human in the Loop

| Aspect | Human in the Loop | Forward Recovery |
|--------|-------------------|------------------|
| **Trigger** | Business rule (e.g., amount threshold) | Activity failure |
| **Data sent** | A decision (approve/reject) | Corrected input values |
| **What happens next** | Proceed or abort based on decision | Retry the failed activity with new data |
| **Use case** | Approvals, reviews, compliance gates | Wrong card, invalid address, bad input |

Both patterns use the same mechanism — `wait events.<name>` to pause and `workflow:sendData` to resume — but serve different purposes.

## What's Next

- [Human in the Loop](human-in-the-loop.md) — Pause for a human decision (approve/reject)
- [Handle Data](../handle-data.md) — Full reference for receiving external data
- [Handle Errors](../handle-errors.md) — Error handling patterns overview
