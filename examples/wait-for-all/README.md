# Wait for All — Dual Authorization

This example demonstrates the **wait-for-all** pattern. A fund transfer requires authorization from **both** the Operations team and the Compliance team. The workflow waits until both teams send their decisions before proceeding.

## How It Works

1. A transfer request is submitted via `POST /api/transfers`.
2. The workflow validates the transfer and notifies both teams.
3. The workflow calls `ctx->await([events.operationsApproval, events.complianceApproval])` — a single call that blocks until **both** futures complete.
4. **Data arrival order does not matter** — if Compliance responds before Operations, the data is stored and `ctx->await` resolves as soon as the last outstanding future completes.
5. If both approve, the transfer is executed. If either rejects, the transfer is rejected.

## Running the Example

Start the service:

```bash
bal run
```

### Submit a Transfer Request

```bash
curl -X POST http://localhost:8090/api/transfers \
  -H "Content-Type: application/json" \
  -d '{"transferId": "TXF-001", "fromAccount": "ACC-001", "toAccount": "ACC-002", "amount": 50000.00}'
```

Response:

```json
{"workflowId": "<workflow-id>"}
```

### Send Operations Authorization

```bash
curl -X POST http://localhost:8090/api/transfers/<workflow-id>/operationsApproval \
  -H "Content-Type: application/json" \
  -d '{"approverId": "ops-lead", "approved": true, "reason": "Verified"}'
```

### Send Compliance Authorization

```bash
curl -X POST http://localhost:8090/api/transfers/<workflow-id>/complianceApproval \
  -H "Content-Type: application/json" \
  -d '{"approverId": "compliance-officer", "approved": true, "reason": "KYC passed"}'
```

### Get the Result

```bash
curl http://localhost:8090/api/transfers/<workflow-id>
```

## Pattern Guide

See [patterns/wait-for-all.md](../../docs/patterns/wait-for-all.md) for the full pattern documentation.
