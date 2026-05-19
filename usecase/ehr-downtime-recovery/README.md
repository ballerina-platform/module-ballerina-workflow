# EHR Downtime Recovery

> **Category**: Error Handling — durable retry
> **Domain**: Healthcare interoperability
> **Trigger**: HTTP API (`POST /interop/dispatch`)
> **Connectors**: `ballerina/http` (EHR endpoint), `ballerinax/slack`, `ballerinax/googleapis.gmail`
> **Notifications**: Optional (`enableDispatchNotifications`)

## Overview

When a downstream EHR/EMR goes offline, traditional message-routing and ETL pipelines write undelivered messages to a **"Queued" or "Errored"** state. An operator must then drain that queue manually once the system is back online.

In a workflow-based architecture **there is no separate physical queue**. The workflow itself is the durable message carrier:

> A running workflow that is retrying a failed activity **IS** the queued message — it is persisted, observable, and retried automatically without any operator action.

This use case demonstrates that pattern end-to-end.

## Flow

```text
POST /interop/dispatch
         │
         ▼
 ┌───────────────────┐
 │  First delivery   │  ── HTTP POST ──►  EHR endpoint
 │  attempt (probe)  │
 └────────┬──────────┘
          │ success (2xx)
          ▼
  Optional Slack: "Delivered" ◄── happy path ends here
          │
          │ EHR down (5xx / connection refused)
          ▼
 ┌───────────────────┐
 │  Optional Slack   │  "#interop-ops: EHR offline — workflow retrying"
 └────────┬──────────┘
          │
          ▼
 ┌────────────────────────────────────────────────┐
 │  Retry with exponential backoff                │
 │  maxRetries=20, retryDelay=30s, factor=1.5     │
 │  (30s → 45s → 67s → 101s … ≈ 55 h window)     │
 │                                                │
 │  Workflow runtime persists state across        │
 │  restarts — no message queue needed.           │
 └────────┬───────────────────────────────────────┘
          │ EHR recovers — retry succeeds
          ▼
 ┌───────────────────┐
 │  Optional Slack:  │  + Optional Gmail delivery report to ops team
 │  "Recovered"     │
 └───────────────────┘
```

If all 20 retries are exhausted (EHR still down after ~55 hours) the workflow transitions to `FAILED`, which is observable via `GET /interop/dispatch/{workflowId}`.

## Why no queue?

| Traditional pipeline              | Workflow-based architecture              |
|-----------------------------------|------------------------------------------|
| Message written to "Queued" state | Workflow paused at retry step            |
| Operator drains queue after EHR recovers | Workflow resumes automatically      |
| Queue depth = operations dashboard | Workflow runtime = operations dashboard |
| Manual replay triggered by operator | No action needed — retries are durable |

## API

### Dispatch a clinical message

```bash
curl -X POST http://localhost:8124/interop/dispatch \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "MSG-20260508-001",
    "patientId":  "P-12345",
    "messageType": "ADT_A01",
    "sourceSystem": "integration-gateway-admit",
    "payload": "{\"resourceType\":\"Bundle\",\"type\":\"message\"}",
    "downstreamPath": "/api/fhir/messages"
  }'
```

Response (EHR online):
```json
{ "workflowId": "wf-abc123", "messageId": "MSG-20260508-001" }
```

### Check delivery status

```bash
curl http://localhost:8124/interop/dispatch/wf-abc123
```

Response while retrying (EHR offline):
```json
{ "workflowId": "wf-abc123", "status": "RUNNING", "workflowType": "dispatchClinicalMessage" }
```

Response after recovery:
```json
{
  "workflowId": "wf-abc123",
  "status": "COMPLETED",
  "result": {
    "messageId": "MSG-20260508-001",
    "status": "RECOVERED",
    "httpStatusCode": 200,
    "summary": "Message MSG-20260508-001 delivered after EHR recovered (HTTP 200)."
  }
}
```

## Configuration

| Key | Description |
|-----|-------------|
| `servicePort` | HTTP listener port (default `8124`) |
| `slackBotToken` | Slack bot token for ops alerts |
| `interopOpsChannel` | Slack channel for interop ops (e.g. `#interop-ops`) |
| `enableDispatchNotifications` | Enable/disable Slack and Gmail notifications (`true` by default) |
| `gmailRefreshToken` / `gmailClientId` / `gmailClientSecret` | Gmail OAuth2 credentials |
| `gmailFromAddress` | Sender address for delivery reports |
| `opsEmail` | Ops team email that receives delivery reports |
| `emrBaseUrl` | Base URL of the downstream EHR endpoint |

## Retry parameters

Tune in `main.bal` → `retryEhrDelivery()`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxRetries` | `20` | Maximum delivery attempts after the first probe |
| `retryDelay` | `30.0` s | Initial wait before first retry |
| `retryBackoff` | `1.5` | Exponential factor (30 s → 45 s → 67 s → …) |

20 retries with factor 1.5 and base 30 s gives a total retry window of roughly **10 hours** — long enough to cover overnight maintenance windows.

## Where this pattern applies

The same shape — automatic retry until the system recovers — fits any scenario where a downstream service has planned or unplanned downtime:

- **Payments gateway maintenance window.** Charge attempts during the window are held as retrying workflows; no batch re-submission is needed afterward.
- **Inventory system nightly refresh.** Stock update workflows started during the refresh window retry automatically once the system is back online.
- **Partner API rate-limit recovery.** When a third-party API returns `429 Too Many Requests`, the workflow retries with backoff and eventually succeeds without operator intervention.
- **Database failover.** During a primary → replica failover, write activities fail transiently. The workflow retries until the new primary is writable.
