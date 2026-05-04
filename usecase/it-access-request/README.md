# `it-access-request` — IT privileged-access approval

> **Domain:** IT automation · **Trigger:** HTTP webhook from a service portal ·
> **Connectors:** `ballerinax/slack`, `ballerinax/jira`, `ballerinax/twilio`

## Scenario

An employee requests privileged access (e.g. `admin` on `prod-aws`) via
an internal service portal, which posts the request to the workflow.

1. **Notify** the IT/SEC approvers' Slack channel.
2. **Create a Jira `Service Request`** task assigned to the on-call
   approver.
3. **Pause** until the Jira webhook calls back with the resolution.
4. **SMS the requester** (Twilio) with the approval/denial outcome.

```
Portal ── POST /it/access-requests ──▶ workflow start
                                            │
                                            ├─▶ slack notify approvers
                                            ├─▶ jira create approval task
                                            │
                                            ░ wait events.approval ░
                                            │
   Jira ── POST /it/access-requests/{id}/jira-resolved ── workflow:sendData
                                            │
                                            └─▶ twilio SMS to requester
```

## Endpoints

| Method | Path                                                  | Purpose                                |
| ------ | ----------------------------------------------------- | -------------------------------------- |
| POST   | `/it/access-requests`                                 | Service-portal webhook trigger.        |
| POST   | `/it/access-requests/{workflowId}/jira-resolved`      | Jira webhook callback.                 |
| GET    | `/it/access-requests/{workflowId}`                    | Fetch result.                          |

## Run

```bash
bal run
```

```bash
curl -X POST localhost:8102/it/access-requests \
  -H 'content-type: application/json' \
  -d '{
    "requestId": "REQ-9001",
    "requesterEmail": "asha@example.com",
    "requesterPhone": "+94771234567",
    "requesterName": "Asha Perera",
    "targetSystem": "prod-aws",
    "accessLevel": "read-only",
    "justification": "Investigating a production incident.",
    "approverJiraAccountId": "5b10ac8d82e05b22cc7d4ef5"
  }'

curl -X POST localhost:8102/it/access-requests/<workflowId>/jira-resolved \
  -H 'content-type: application/json' \
  -d '{
    "jiraIssueKey": "ITSEC-42",
    "resolution": "Done",
    "approverDisplayName": "Jane Smith",
    "comment": "Approved for 8 hours"
  }'
```
