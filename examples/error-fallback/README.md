# Error Fallback Example

This example demonstrates the **fallback pattern**: when a primary activity exhausts its Temporal retries, the error is captured as a value and a secondary activity is called instead. The workflow completes successfully via the fallback path.

An HTTP service exposes the workflow so you can send notifications and observe the email→SMS fallback.

See the full pattern explanation in [Handle Errors — Fallback](../../docs/patterns/error-fallback.md).

## What This Example Shows

- Opting into Temporal retries with `retryPolicy = {maxRetries: 2}`
- Capturing the `T|error` return value after retries are exhausted
- Calling a secondary (fallback) activity when the primary fails
- Workflow completes successfully even when the primary path fails

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Start the service (IN_MEMORY mode — no server required)

```bash
bal run
```

The HTTP service starts on port **8093**.

### Send a notification

```bash
curl -s -X POST http://localhost:8093/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "user-123",
    "message": "Your order has shipped!",
    "email": "user@example.com",
    "phone": "+1-555-0100"
  }'
```

### Get the result

```bash
curl -s http://localhost:8093/api/notifications/<workflow-id>
```

Response:

```json
{"status": "COMPLETED", "result": "Delivered via SMS: SMS delivered to +1-555-0100"}
```

Email always fails in this demo, so SMS fallback kicks in automatically.

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
