# `crm-erp-customer-sync` — CRM-to-Salesforce customer synchronization

> **Domain:** Sales operations / RevOps · **Trigger:** HTTP webhook from an
> upstream CRM or sign-up form · **Connectors:** `ballerinax/salesforce`,
> `ballerinax/slack`, `ballerinax/googleapis.gmail`
> **Category:** Transactional workflow (no human in the loop).

## Scenario

When a customer signs up — through a marketing form, a partner CRM, or a
self-service portal — every downstream system needs an entry. This use
case replays one new-customer event into Salesforce as an `Account` plus
a primary `Contact`, posts an audit message in Slack, and emails the
primary contact a welcome message. The workflow runtime holds the
business state while individual activities retry on transient failures.

1. **Create** a Salesforce `Account` from the company details.
2. **Create** a Salesforce `Contact` linked to that Account.
3. **Notify** RevOps in Slack with the new account and contact ids.
4. **Send a welcome email** to the primary contact via Gmail.

```text
Upstream CRM ── POST /crm/customers ──▶ workflow start
                                              │
                                              ├─▶ salesforce: create Account
                                              ├─▶ salesforce: create Contact
                                              ├─▶ slack:  notify RevOps
                                              └─▶ gmail:  welcome email
```

No activity mocks the backend. Every external interaction is a real
connector call wrapped in an `@workflow:Activity` function.

## Run

Configure Slack, Salesforce, and Gmail settings in `Config.toml`, then:

```bash
bal run
```

Trigger one workflow:

```bash
curl -X POST http://localhost:8120/crm/customers \
    -H "Content-Type: application/json" \
    -d '{
        "requestId": "REQ-001",
        "companyName": "Acme Industries",
        "industry": "Manufacturing",
        "annualRevenueUsd": 12500000.00,
        "region": "CA",
        "primaryContactName": "Alice Adams",
        "primaryContactEmail": "alice@acme.example.com"
    }'
```

Look up the result:

```bash
curl http://localhost:8120/crm/customers/<workflow-id>
```

## Where this pattern shows up

* Replicating a new customer record into multiple systems of record
  (CRM, ERP, support ticketing, data warehouse).
* Fan-out integrations after a webhook arrives — every downstream
  write becomes one activity.
* Any "create-here, mirror-everywhere" automation that must survive
  worker restarts and SaaS rate limits.
