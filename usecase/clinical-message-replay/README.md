# `clinical-message-replay` — auditable replay of failed clinical messages

> **Domain:** Healthcare interoperability · **Trigger:** HTTP API from an
> integration gateway, replay console, or operator dashboard · **Connectors:**
> `http`, `ballerinax/jira`, `ballerinax/slack`
> **Category:** Human task with error recovery.

## Scenario

Healthcare integration teams often need to replay failed messages after a
mapping defect, a downstream EMR outage, an over-strict filter rule, or a
server disruption that left a batch of messages in an error state. This
use case models that replay as part of the workflow itself instead of as an
out-of-band dashboard action.

1. **Receive** a failed clinical message from the integration layer.
2. **Validate and deliver** it to the downstream EMR over HTTP.
3. If validation or delivery fails:
   * **Create a Jira replay task** as the auditable system of record.
   * **Notify interoperability operations** in Slack.
   * **Pause** until an analyst resolves the task and sends replay instructions.
4. **Replay** the message with corrected ids or a filter override, then
   record the final outcome in workflow history and Slack.

```text
Integration gateway ── POST /interop/messages ──▶ workflow start
                                                      │
                                                      ├─▶ validate mapped message
                                                      ├─▶ http: deliver to downstream EMR
                                                      │
                                                      ├─▶ on failure: jira create replay task
                                                      ├─▶ on failure: slack notify interop ops
                                                      │
                                                      ░ wait replayInstruction ░
                                                      │
                           Jira / operator console ── POST /interop/messages/{id}/replay-resolution
                                                      │
                                                      └─▶ retry delivery with operator patch
```

No activity mocks the backend. The human step is auditable because the
workflow does not silently retry on its own: it creates a task, waits for
operator input, and records the replay decision in workflow history.

## Replay instructions

The callback supports three replay outcomes:

| Action | Meaning |
| ------ | ------- |
| `RETRY_AS_IS` | Replay the message without changing any fields. Useful after downstream downtime is resolved. |
| `RETRY_WITH_PATCH` | Replay with corrected patient or encounter ids and/or a filter override. Useful after mapping fixes or filter adjustments. |
| `CANCEL` | Keep the message out of the target system and close the workflow as cancelled. |

## Run

Configure Jira, Slack, and the downstream EMR endpoint in `Config.toml`, then:

```bash
bal run
```

Start one workflow for a failed message:

```bash
curl -X POST http://localhost:8123/interop/messages \
    -H "Content-Type: application/json" \
    -d '{
        "messageId": "MSG-001",
        "sourceSystem": "ADT-Gateway",
        "messageType": "ADT_A01",
        "patientId": "",
        "encounterId": "ENC-7788",
        "routingOutcome": "FILTERED",
        "downstreamPath": "/messages",
        "hl7Payload": "MSH|^~\\&|ADT|..."
    }'
```

When the workflow creates a Jira task and pauses, send the replay
instruction after the mapping/filter issue is fixed or the downstream
system is back online:

```bash
curl -X POST http://localhost:8123/interop/messages/<workflow-id>/replay-resolution \
    -H "Content-Type: application/json" \
    -d '{
        "jiraIssueKey": "INTEROP-101",
        "analystName": "Nina Patel",
        "action": "RETRY_WITH_PATCH",
        "correctedPatientId": "PAT-44321",
        "overrideFilter": true,
        "notes": "Transformer fixed and filter override approved."
    }'
```

Look up the final result:

```bash
curl http://localhost:8123/interop/messages/<workflow-id>
```

## Where this pattern shows up

- **Correcting data mapping errors.** The operator fixes the transformer,
  supplies corrected identifiers in the replay instruction, and the
  workflow replays the message with the updated values.
- **Recovering from downstream system downtime.** The target EMR comes
  back online, the analyst resolves the Jira task with `RETRY_AS_IS`, and
  the workflow replays the original message without losing the audit trail.
- **Retrying previously filtered messages.** An over-strict rule blocked a
  valid message; the analyst sets `overrideFilter = true` and the workflow
  re-runs the delivery as an approved exception.
- **Managing server disruptions.** When a restart or network glitch causes
  many messages to fail, start one workflow per affected message from the
  selected timeframe. Each replay remains individually auditable and
  resumable.