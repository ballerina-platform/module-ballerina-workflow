# Human-in-the-Loop Example

This example demonstrates a workflow that pauses for **human approval** before proceeding. High-value orders require a manager's decision — the workflow durably pauses until a reviewer sends their approval via the HTTP API. Low-value orders are auto-approved.

An HTTP service exposes the workflow, allowing external systems (dashboards, Slack bots, etc.) to start orders and submit approval decisions via REST.

See the full pattern explanation in [Human in the Loop](../../docs/patterns/human-in-the-loop.md).

## What This Example Shows

- Pausing a workflow at `wait events.approval` for a human decision
- Conditional approval: only high-value orders require human input
- Sending external data to a waiting workflow via `workflow:sendData`
- Exposing the workflow via an HTTP service (`POST /api/orders`, `POST /api/orders/{id}/approve`)
- Workflow durability: state is preserved across worker restarts while paused
- Three outcomes: approved, rejected, or auto-approved (below threshold)

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Start the service (IN_MEMORY mode — no server required)

```bash
bal run
```

The HTTP service starts on port **8090**.

### Start a high-value order (requires approval)

```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-001",
    "item": "standing-desk",
    "amount": 799.00
  }'
```

Response:

```json
{"workflowId":"<uuid>"}
```

The workflow validates the order, notifies the approval team, and pauses waiting for a decision.

### Send the manager's decision (approve)

Replace `<workflow-id>` with the ID from the previous response:

```bash
curl -s -X POST http://localhost:8090/api/orders/<workflow-id>/approve \
  -H "Content-Type: application/json" \
  -d '{
    "approverId": "manager-ops-1",
    "approved": true,
    "reason": "Approved for Q2 budget"
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
    "message": "Order fulfilled: FULFILLED-ORD-001"
  }
}
```

To reject instead, send `"approved": false`. The result will have `"status": "REJECTED"`.

### Start a low-value order (auto-approved)

```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-002",
    "item": "mouse-pad",
    "amount": 25.00
  }'
```

This order completes immediately without waiting for approval.

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
