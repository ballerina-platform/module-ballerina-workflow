# `subscription-provisioning-saga` — multi-step Salesforce provisioning with rollback

> **Domain:** Subscription billing / SaaS operations · **Trigger:** HTTP API
> from a self-service signup or sales hand-off · **Connectors:**
> `ballerinax/salesforce`, `ballerinax/slack`, `ballerinax/googleapis.gmail`
> **Category:** Error handling — *compensation (Saga pattern)*.

## Scenario

Provisioning a new subscription means creating several linked records in
Salesforce. If any step fails permanently after retries, the system of
record must not be left holding partial state — the previously committed
records have to be undone. This use case implements that policy with
real Salesforce writes and compensating deletes.

1. **Create** a Salesforce `Account` for the new customer.
2. **Create** a Salesforce `Contact` linked to the Account. *If this
   step fails →* delete the Account and finish with `ROLLED_BACK`.
3. **Create** a Salesforce `Contract` for the subscription. *If this
   step fails →* delete the Contact and the Account in reverse order and
   finish with `ROLLED_BACK`.
4. **Audit** the outcome (success or rollback) on the provisioning Slack
   channel and email billing operations.

```text
Caller ── POST /subscriptions ──▶ workflow start
                                        │
                                        ├─▶ salesforce: create Account
                                        │       └── on later failure: deleteAccount
                                        ├─▶ salesforce: create Contact
                                        │       └── on later failure: deleteContact
                                        ├─▶ salesforce: create Contract
                                        ├─▶ slack:  audit
                                        └─▶ gmail:  billing-ops email
```

The workflow always completes — either as `PROVISIONED` (all three
records created) or `ROLLED_BACK` (any committed records have been
deleted). Compensating activities are real Salesforce DELETE calls and
are themselves retried on transient failures.

## Run

Configure Salesforce, Slack, and Gmail settings in `Config.toml`, then:

```bash
bal run
```

Trigger a successful provisioning:

```bash
curl -X POST http://localhost:8122/subscriptions \
    -H "Content-Type: application/json" \
    -d '{
        "requestId": "SUB-001",
        "companyName": "Acme Industries",
        "industry": "Manufacturing",
        "primaryContactFirstName": "Alice",
        "primaryContactLastName": "Adams",
        "primaryContactEmail": "alice@acme.example.com",
        "planName": "Enterprise",
        "contractTermMonths": 12,
        "contractStartDate": "2026-01-01"
    }'
```

Look up the result:

```bash
curl http://localhost:8122/subscriptions/<workflow-id>
```

If Salesforce rejects the `Contract` step (for example because the
target object lacks the field-level security or required-fields setup
expected by the payload), the workflow rolls back and the response from
the result endpoint shows `"status": "ROLLED_BACK"` together with the
captured failure reason.

## Where this pattern shows up

* **Multi-leg fund transfer.** Debit succeeds, credit fails — reverse
  the debit so the customer is never left with a missing balance.
* **Travel booking (hotel + flight + car).** A later leg refuses to
  reserve, so previously confirmed legs are cancelled in reverse order.
* **Multi-system provisioning** beyond Salesforce — for example,
  creating an account in an ERP, a license in an entitlement system,
  and a tenant in an authentication provider, then unwinding any
  partially completed write when a later step fails.
* **CRM ↔ ERP customer onboarding** that crosses a transactional
  boundary the underlying SaaS does not provide.
