# `hr-onboarding` — HR new-hire onboarding

> **Domain:** HR automation · **Trigger:** HTTP webhook from the HRIS ·
> **Connectors:** `ballerinax/slack`, `ballerinax/jira`, `ballerinax/googleapis.gmail`

## Scenario

Your HRIS (Workday, BambooHR, Rippling, …) emits a webhook when a new
hire is created. The workflow runs end-to-end in a durable, resumable
fashion:

1. **Announce** the new hire in the team Slack channel.
2. If the equipment cost exceeds the threshold, **create a Jira approval
   task** assigned to the hiring manager and **pause** until the manager
   resolves the issue.
3. **Send a personalised welcome email** via Gmail.

The "human in the loop" step (manager approving an equipment budget)
follows the project-wide three-step pattern: notify → create task →
webhook callback.

```
HRIS ── POST /hr/new-hire ──▶ workflow start
                                   │
                                   ├─▶ slack: chat.postMessage  (#onboarding)
                                   │
                                   ├─▶ jira: create issue       (HR-123)
                                   │
                                   ░ wait events.equipmentApproval ░
                                   │
   Jira ── POST /hr/onboarding/{id}/jira-resolved ── workflow:sendData
                                   │
                                   └─▶ gmail: send welcome email
```

## Endpoints

| Method | Path                                              | Purpose                                          |
| ------ | ------------------------------------------------- | ------------------------------------------------ |
| POST   | `/hr/new-hire`                                    | HRIS webhook trigger; starts the workflow.       |
| POST   | `/hr/onboarding/{workflowId}/jira-resolved`       | Jira webhook callback when issue is resolved.    |
| GET    | `/hr/onboarding/{workflowId}`                     | Fetch the workflow's final result.               |

## Config

`Config.toml` exposes Slack/Jira/Gmail credentials and the equipment
approval threshold. With the placeholder defaults, `bal build` succeeds;
filling them in is required to actually call the SaaS APIs at runtime.

## Run

```bash
bal run
```

```bash
# Start an onboarding
curl -X POST localhost:8101/hr/new-hire \
  -H 'content-type: application/json' \
  -d '{
    "employeeId": "EMP-1042",
    "fullName": "Asha Perera",
    "workEmail": "asha@example.com",
    "department": "Platform Engineering",
    "jobTitle": "Senior Engineer",
    "managerJiraAccountId": "5b10ac8d82e05b22cc7d4ef5",
    "startDate": "2026-02-01",
    "equipmentRequestUsd": 2500.00
  }'

# Simulate Jira webhook on issue resolution
export WORKFLOW_ID=<paste-workflow-id-here>
curl -X POST "http://localhost:8101/hr/onboarding/$WORKFLOW_ID/jira-resolved" \
  -H 'content-type: application/json' \
  -d '{
    "id": "'"$WORKFLOW_ID"'",
    "jiraIssueKey": "HR-123",
    "resolution": "Done",
    "approverDisplayName": "Jane Smith",
    "budgetUsd": 2500.00
  }'

# Fetch result
curl "http://localhost:8101/hr/onboarding/$WORKFLOW_ID"
```
