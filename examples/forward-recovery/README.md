# Forward Recovery Example

This example demonstrates **forward recovery**: when an activity fails, the workflow pauses and waits for a human to supply **corrected data**, then retries the failed activity with the updated values. Instead of rolling back, the workflow moves forward once the user fixes the problem.

An HTTP service exposes the workflow, allowing external systems (dashboards, apps, etc.) to start orders and submit corrected payment details via REST.

See the full pattern explanation in [Forward Recovery](../../docs/patterns/forward-recovery.md).

## What This Example Shows

- Forward recovery: retrying a failed activity with human-supplied corrected data
- Pausing the workflow at `wait events.paymentRetry` for external input
- Sending corrected data to a waiting workflow via `workflow:sendData`
- Exposing the workflow via an HTTP service (`POST /api/orders`, `POST /api/orders/{id}/retryPayment`)
- Workflow durability: state is preserved across worker restarts while paused

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Start the service (IN_MEMORY mode — no server required)

```bash
bal run
```

The HTTP service starts on port **8090**.

### Start a new order (with a card that will be declined)

```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-001",
    "item": "standing-desk",
    "amount": 799.00,
    "cardToken": "tok_declined"
  }'
```

Response:

```json
{"workflowId":"<uuid>"}
```

The workflow attempts payment, the card is declined, the user is notified, and the workflow pauses waiting for corrected payment details.

### Send corrected payment details

Replace `<workflow-id>` with the ID from the previous response:

```bash
curl -s -X POST http://localhost:8090/api/orders/<workflow-id>/retryPayment \
  -H "Content-Type: application/json" \
  -d '{
    "cardToken": "tok_visa_4242",
    "amount": null
  }'
```

The `amount` field is optional — set it to override the original amount, or pass `null` to keep the original.

### Get the workflow result

```bash
curl -s http://localhost:8090/api/orders/<workflow-id>
```

Response:

```json
{
  "status": "COMPLETED",
  "result": {
    "orderId": "ORD-001",
    "status": "COMPLETED",
    "message": "Payment succeeded (TXN-tok_visa_4242-799.00), order fulfilled (FULFILLED-ORD-001)"
  }
}
```

### Running tests

```bash
bal test
```

### Using a local Temporal server

Update `Config.toml`:

```toml
[ballerina.workflow]
mode = "LOCAL"
```

Start your Temporal dev server, then start the service.

## Building with the local module

```bash
cd ../..
./gradlew :workflow-examples:build
```
