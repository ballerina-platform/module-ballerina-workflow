# Alternative Wait — Approval Ladder

This example demonstrates the **alternative wait** pattern. A purchase request is sent to two approvers — a Manager and a Director. Both send to the same data channel (`"approval"`). The workflow waits once — whichever approver responds first unblocks the workflow, and any subsequent response is silently ignored.

This is sometimes called an **approval ladder**: any approver at the right level can unblock the workflow.

## How It Works

1. A purchase request is submitted via `POST /api/purchases`.
2. The workflow validates the request and notifies both approvers.
3. The workflow pauses at `wait events.approval`.
4. Both approvers can call `POST /api/purchases/{id}/approval` — the first response unblocks the workflow.
5. If approved, the purchase is processed. If rejected, the workflow returns `REJECTED`.

## Running the Example

Start the service:

```bash
bal run
```

### Submit a Purchase Request

```bash
curl -X POST http://localhost:8090/api/purchases \
  -H "Content-Type: application/json" \
  -d '{"requestId": "REQ-001", "item": "ergonomic-chair", "amount": 1200.00, "requestedBy": "alice"}'
```

Response:

```json
{"workflowId": "<workflow-id>"}
```

### Send an Approval (Manager or Director)

Only one approval is needed — any subsequent response is ignored.

**Manager approves:**

```bash
curl -X POST http://localhost:8090/api/purchases/<workflow-id>/approval \
  -H "Content-Type: application/json" \
  -d '{"approverId": "manager-1", "approved": true, "reason": "Within budget"}'
```

**Or Director approves:**

```bash
curl -X POST http://localhost:8090/api/purchases/<workflow-id>/approval \
  -H "Content-Type: application/json" \
  -d '{"approverId": "director-1", "approved": true, "reason": "Approved"}'
```

### Get the Result

```bash
curl http://localhost:8090/api/purchases/<workflow-id>
```

## Pattern Guide

See [patterns/alternative-wait.md](../../docs/patterns/alternative-wait.md) for the full pattern documentation.
