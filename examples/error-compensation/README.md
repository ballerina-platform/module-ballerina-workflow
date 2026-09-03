# Error Compensation Example (Saga Pattern)

This example demonstrates the **Saga compensation pattern**: when a later step fails after exhausting its retries, the workflow executes compensating activities to reverse earlier committed work, then completes with a rolled-back status.

An HTTP service exposes the workflow so you can trigger fund transfers and observe the compensation.

See the full pattern explanation in [Handle Errors — Compensation](../../docs/patterns/error-compensation.md).

## What This Example Shows

- Executing multiple activities in sequence (step 1 then step 2)
- Retrying step 2 on transient failures with `retryPolicy = {maxRetries: 2}`
- Running a compensating activity to undo step 1 when step 2 fails
- Workflow completes with ROLLED_BACK status (not Failed)

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Start the service (IN_MEMORY mode — no server required)

```bash
bal run
```

The HTTP service starts on port **8092**.

### Start a fund transfer

```bash
curl -s -X POST http://localhost:8092/api/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "transferId": "TXN-001",
    "sourceAccount": "ACC-SRC-123",
    "destAccount": "ACC-DST-456",
    "amount": 500.00
  }'
```

### Get the result

```bash
curl -s http://localhost:8092/api/transfers/<workflow-id>
```

Response:

```json
{"status": "COMPLETED", "result": "Transfer TXN-001 ROLLED_BACK. Reversed debit of 500.0 on ACC-SRC-123"}
```

The credit step always fails in this demo, so debit is compensated automatically.

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

Then start your Temporal dev server and run:

```bash
bal run
```

## Building with the local module

```bash
cd ../..
./gradlew :workflow-examples:build
```
