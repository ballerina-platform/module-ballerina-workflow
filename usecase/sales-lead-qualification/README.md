# `sales-lead-qualification` — Lead qualification with SLA timeout

> **Domain:** Sales automation · **Trigger:** HTTP webhook from a marketing form ·
> **Connectors:** `ballerinax/salesforce`, `ballerinax/twilio`, `ballerinax/slack` ·
> **Pattern:** SLA-bounded human interaction (`ctx->await(..., timeout = ...)`)

## Scenario

A marketing form posts new leads to the workflow. Each lead is created
in Salesforce, the assigned rep is notified, and the workflow waits for
the rep to qualify the lead — but only for a configurable SLA window.
If the rep doesn't respond in time, the workflow escalates to sales
leadership.

1. **Create** a Salesforce `Lead` record.
2. **Notify** `#sales` and **SMS** the assigned rep.
3. `ctx->await([events.qualification], timeout = {hours: slaHours})`
   * **Response in time** → update the Lead's `Status` and post the
     outcome on `#sales`.
   * **SLA breach** → update Lead status to `Stale - Escalated` and
     post on `#sales-leadership`.

```
Form ── POST /sales/leads ──▶ workflow start
                                    │
                                    ├─▶ salesforce: create Lead
                                    ├─▶ slack: post to #sales
                                    ├─▶ twilio: SMS to assigned rep
                                    │
                                    ░ ctx.await(events.qualification, timeout) ░
                                    │
                                ┌──── in time ────┐
   Rep UI ── POST /sales/leads/{id}/qualification ── workflow:sendData
                                │                                   │
                                ├─▶ salesforce: update Lead Status  │
                                └─▶ slack: post outcome             │
                                                                    │
                                ┌──── SLA breach ────────────────────┘
                                ├─▶ salesforce: Stale - Escalated
                                └─▶ slack: post to #sales-leadership
```

## Endpoints

| Method | Path                                               | Purpose                                 |
| ------ | -------------------------------------------------- | --------------------------------------- |
| POST   | `/sales/leads`                                     | Marketing-form webhook trigger.         |
| POST   | `/sales/leads/{workflowId}/qualification`          | Rep-response callback.                  |
| GET    | `/sales/leads/{workflowId}`                        | Fetch the workflow's final result.      |

## Run

```bash
bal run
```

```bash
curl -X POST localhost:8104/sales/leads \
  -H 'content-type: application/json' \
  -d '{
    "leadId": "LD-7001",
    "firstName": "Asha",
    "lastName": "Perera",
    "company": "Acme Cloud Inc",
    "email": "asha@acme.example",
    "phone": "+94771234567",
    "source": "Web",
    "estimatedValueUsd": 25000.00,
    "assignedRepName": "John Doe",
    "assignedRepPhone": "+15555550199"
  }'

# Rep responds in time
curl -X POST localhost:8104/sales/leads/<workflowId>/qualification \
  -H 'content-type: application/json' \
  -d '{"outcome": "Qualified", "notes": "Strong fit; demo scheduled"}'
```

To see the SLA-breach branch, set `slaHours = 1` (or a few seconds via
`ctx->await(..., timeout = {seconds: 30})` for a quick demo) and don't
post the qualification callback.
