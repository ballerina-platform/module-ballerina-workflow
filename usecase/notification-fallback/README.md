# `notification-fallback` — email-then-SMS notification delivery

> **Domain:** Customer messaging · **Trigger:** HTTP API from any internal
> producer · **Connectors:** `ballerinax/googleapis.gmail`,
> `ballerinax/twilio`, `ballerinax/slack`
> **Category:** Error handling — *fallback channel*.

## Scenario

A customer notification must be delivered even when the primary email
provider is degraded. The workflow first tries Gmail with a short retry
budget; if Gmail is unreachable after retries, it falls back to a Twilio
SMS. Either way, a delivery-audit message is posted to a Slack channel
so operators can see which channel actually carried each notification.

1. **Try email (Gmail)** with bounded retries.
2. If email exhausts its retries:
   * **Fall back to SMS (Twilio).**
   * Capture the email error so it can be reported in the audit feed.
3. **Post a Slack audit message** describing the chosen channel.

```text
Caller ── POST /notifications ──▶ workflow start
                                        │
                                        ├─▶ gmail.send (with retries) ──┐
                                        │                                ├── on retry-exhausted
                                        │                                ▼
                                        │                          twilio.createMessage
                                        │
                                        └─▶ slack: audit which channel was used
```

No activity mocks the backend. Each integration is a real connector call
wrapped in an `@workflow:Activity`.

## Run

Configure Gmail, Twilio, and Slack settings in `Config.toml`, then:

```bash
bal run
```

Trigger a notification:

```bash
curl -X POST http://localhost:8121/notifications \
    -H "Content-Type: application/json" \
    -d '{
        "notificationId": "NOTIF-001",
        "recipientEmail": "alice@example.com",
        "recipientPhone": "+15555550199",
        "subject": "Service status update",
        "body": "Your scheduled job completed successfully."
    }'
```

Look up the result:

```bash
curl http://localhost:8121/notifications/<workflow-id>
```

## Where this pattern shows up

* **Recovering from downstream system downtime.** When the primary
  notification provider is offline, the fallback channel keeps customer
  communication flowing without operator intervention.
* **Multi-channel customer messaging.** Email with SMS fallback, Slack
  with email fallback, push notifications with SMS fallback — the
  workflow encodes the policy explicitly.
* **Provider failover.** Two equivalent providers (e.g. SES → SendGrid,
  Twilio → Vonage) with the second tried only when the first exhausts
  retries.
