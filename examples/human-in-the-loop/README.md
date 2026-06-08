# Human-in-the-Loop Example

This example demonstrates a workflow that pauses for **human approval** using `callHumanTask`. High-value orders require a manager's decision — the workflow durably pauses until a reviewer submits their decision. Low-value orders are auto-approved.

The task is modelled as a Temporal child workflow. No separate HTTP callback endpoint or `sendData` call is needed in the workflow code.

## What This Example Shows

- Pausing a workflow at `ctx->callHumanTask(...)` for a human decision
- Conditional approval: only high-value orders require human input
- Listing pending tasks via `management:listPendingHumanTasks`
- Completing a task via `workflow:completeHumanTask`
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

### Step 1 — Start a high-value order (requires approval)

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

The workflow validates the order, then calls `callHumanTask` and pauses durably, waiting for a manager's decision.

### Step 2 — List pending approval tasks

```bash
curl -s http://localhost:8090/api/orders/<workflow-id>/tasks
```

Response:

```json
[{"taskName":"approveOrder","taskIds":["humantask-<workflow-id>-approveOrder-<uuid>"]}]
```

Task types are sorted alphabetically. Each group lists the instance IDs under that type in start order.

### Step 3 — Submit the manager's decision

Replace `<task-id>` with the first element of `taskIds[0]` from the previous response:

```bash
curl -s -X POST http://localhost:8090/api/tasks/<task-id>/complete \
  -H "Content-Type: application/json" \
  -d '{
    "approved": true,
    "reason": "Approved for Q2 budget"
  }'
```

To reject instead, send `"approved": false`.

### Step 4 — Get the workflow result

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

Response (rejected):

```json
{
  "status": "COMPLETED",
  "result": {
    "orderId": "ORD-001",
    "status": "REJECTED",
    "message": "Rejected: Budget exceeded"
  }
}
```

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

This order completes immediately without a human task.

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
