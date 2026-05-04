# `support-case-resolution` — asynchronous support case resolution

> **Domain:** Customer support automation · **Trigger:** REST API from a
> support portal or chatbot · **Connectors:** `ballerinax/salesforce`,
> `ballerinax/slack`, `ballerinax/googleapis.gmail`

## Scenario

A customer opens a support case from a portal, chatbot, or mobile app.
The workflow creates a Salesforce Case, keeps the customer updated by
email, notifies support engineering in Slack, and then pauses at several
checkpoints until external systems send the next event.

1. **Create a Salesforce Case** for the inbound support request.
2. **Email the customer** asking for diagnostic details.
3. **Notify support engineering** in Slack.
4. **Wait for diagnostics** from the customer portal or chatbot.
5. **Wait for engineer triage** from an internal support console.
6. If a product fix is required, **wait for a deployment webhook** from
   the release or CI/CD system.
7. **Wait for customer confirmation** that the issue is resolved.
8. **Close or update the Salesforce Case** and send the final email.

This use case demonstrates long-running workflow state without a custom
state table. The workflow runtime holds the execution state while the
case waits for customer messages, engineer decisions, and deployment
events. Each external callback resumes the correct checkpoint with the
workflow id.

```
Portal/Chatbot ── POST /support/cases ──▶ workflow:run
                                               │
                                               ├─▶ salesforce: create Case
                                               ├─▶ gmail: request diagnostics
                                               ├─▶ slack: notify support
                                               │
                         ░ wait events.diagnosticsProvided ░
Portal/Chatbot ── POST /support/cases/{id}/diagnostics ── workflow:sendData
                                               │
                                               ├─▶ salesforce: update Case
                                               │
                         ░ wait events.engineerTriaged ░
Support console ── POST /support/cases/{id}/triage ── workflow:sendData
                                               │
                         ░ wait events.fixDeployed ░
CI/CD webhook ── POST /support/cases/{id}/deployment ── workflow:sendData
                                               │
                         ░ wait events.customerConfirmed ░
Portal/Chatbot ── POST /support/cases/{id}/confirmation ── workflow:sendData
                                               │
                                               ├─▶ salesforce: close Case
                                               └─▶ gmail/slack: final updates
```

## Checkpoints

| Checkpoint | External caller | Resume event |
| ---------- | --------------- | ------------ |
| Diagnostics | Support portal, chatbot, or mobile app | `diagnosticsProvided` |
| Engineer triage | Internal support console or Slack workflow callback | `engineerTriaged` |
| Fix deployment | CI/CD, release, or incident-management webhook | `fixDeployed` |
| Customer confirmation | Support portal, chatbot, or email-link handler | `customerConfirmed` |

## Endpoints

| Method | Path | Purpose |
| ------ | ---- | ------- |
| POST | `/support/cases` | Starts the support workflow. |
| POST | `/support/cases/{workflowId}/diagnostics` | Delivers customer diagnostics. |
| POST | `/support/cases/{workflowId}/triage` | Delivers engineer triage. |
| POST | `/support/cases/{workflowId}/deployment` | Delivers deployment completion. |
| POST | `/support/cases/{workflowId}/confirmation` | Delivers customer confirmation. |
| GET | `/support/cases/{workflowId}` | Fetches the workflow result. |

## Run

Configure Salesforce, Slack, and Gmail credentials in `Config.toml`, then:

```bash
bal run
```

```bash
# Start a support case from a portal or chatbot
curl -X POST localhost:8105/support/cases \
  -H 'content-type: application/json' \
  -d '{
    "caseRef": "SUP-1042",
    "customerName": "Asha Perera",
    "customerEmail": "asha@example.com",
    "companyName": "Acme Cloud",
    "subject": "Checkout API returns intermittent 500 responses",
    "description": "Failures started after the latest mobile release.",
    "severity": "High"
  }'

# Resume when the customer provides diagnostics
curl -X POST localhost:8105/support/cases/<workflowId>/diagnostics \
  -H 'content-type: application/json' \
  -d '{
    "logsUrl": "https://storage.example.com/support/SUP-1042/logs.txt",
    "reproductionSteps": "Open mobile checkout and submit payment twice.",
    "environment": "iOS production"
  }'

# Resume when an engineer triages the case
curl -X POST localhost:8105/support/cases/<workflowId>/triage \
  -H 'content-type: application/json' \
  -d '{
    "engineerName": "Sam Lee",
    "classification": "Product Bug",
    "requiresDeployment": true,
    "notes": "Regression in checkout retry handler."
  }'

# Resume when CI/CD or release tooling reports deployment
curl -X POST localhost:8105/support/cases/<workflowId>/deployment \
  -H 'content-type: application/json' \
  -d '{
    "deploymentId": "deploy-8891",
    "version": "checkout-api-2026.05.04",
    "deployedBy": "release-bot"
  }'

# Resume when the customer confirms resolution
curl -X POST localhost:8105/support/cases/<workflowId>/confirmation \
  -H 'content-type: application/json' \
  -d '{
    "confirmed": true,
    "comment": "Checkout is working again."
  }'

# Fetch result
curl localhost:8105/support/cases/<workflowId>
```
