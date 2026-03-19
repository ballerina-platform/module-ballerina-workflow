# Human-in-the-Loop (Forward Recovery) Example

This example demonstrates **forward recovery**: when an activity fails and automated retries cannot resolve the problem, the workflow pauses and waits for a human decision. A reviewer can approve (trigger one final retry) or cancel the order.

An HTTP service exposes the workflow, allowing external systems (dashboards, Slack bots, etc.) to start orders and submit review decisions via REST.

See the full pattern explanation in [Handle Errors — Human in the Loop](../../docs/patterns/human-in-the-loop.md).

## What This Example Shows

- Exhausting retries with `retryOnError = true, maxRetries = 3`
- Pausing the workflow at a `wait events.review` after failure
- Exposing the workflow via an HTTP service (`POST /api/orders`, `POST /api/orders/{id}/review`)
- Workflow durability: state is preserved across worker restarts while paused
- Two outcomes: reviewer approves (retry) or cancels

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Start the service (IN_MEMORY mode — no server required)

```bash
bal run
```

The HTTP service starts on port **8090**.

### Start a new order

```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-001",
    "item": "standing-desk",
    "amount": 799.00,
    "cardToken": "tok_visa_4242"
  }'
```

Response:

```json
{"workflowId":"<uuid>"}
```

The workflow starts, the `chargeCard` activity fails after 3 retries, the review team is notified, and the workflow pauses waiting for a decision.

### Send the reviewer's decision (approve)

Replace `<workflow-id>` with the ID from the previous response:

```bash
curl -s -X POST http://localhost:8090/api/orders/<workflow-id>/review \
  -H "Content-Type: application/json" \
  -d '{
    "reviewerId": "reviewer-ops-1",
    "approved": true,
    "note": "Manual gateway check passed"
  }'
```

### Get the workflow result

```bash
curl -s http://localhost:8090/api/orders/<workflow-id>
```

Response (approved):

```json
{
  "status": "COMPLETED",
  "result": {
    "orderId": "ORD-001",
    "status": "COMPLETED",
    "message": "TXN-MANUAL-tok_visa_4242"
  }
}
```

To cancel instead, send `"approved": false` in the review decision. The result will have `"status": "CANCELLED"`.

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

Start your Temporal dev server, then start the service. While the workflow is paused waiting for the review data you can also send the data directly from the **Temporal Web UI** (see [Handle Errors — Recovering via Workflow Engine UI](../../docs/handle-errors.md#recovering-workflows-via-workflow-engine-ui)).

## Building with the local module

```bash
cd ../..
./gradlew :workflow-examples:build
```
