# Real-World Use Cases for Ballerina Workflow

This directory contains a curated set of integration and automation use
cases across HR, IT, Finance, Sales, Customer Support, Marketing
Operations, and Supply Chain. They demonstrate how to build durable,
long-running business processes with the
[`ballerina/workflow`](../ballerina) module **using real Ballerina
connectors** (`ballerinax/slack`, `ballerinax/jira`, `ballerinax/twilio`,
`ballerinax/salesforce`, `ballerinax/googleapis.gmail`, `ballerina/ftp`,
`ballerina/email`, `ballerina/http`, `ballerinax/trigger.google.sheets`, `ballerinax/mssql`,
…).

The examples avoid mock activities. Every step that interacts with the
outside world is implemented as a real connector call inside an
`@workflow:Activity` function, while workflow code keeps the durable
business process state.

---

## Why workflows for integration work?

A workflow is executable business logic whose state survives worker
restarts, crashes, and process upgrades. That makes it a good fit for
integration processes that:

* take **minutes, hours, or days** (waiting for a human, a file, a
  callback, an SLA timer);
* need to be **resumed exactly where they left off** after a restart; and
* must **call out to many external systems** with retries, timeouts, and
  compensation logic.

Each use case starts from a real-world trigger, such as an HTTP webhook
or an SFTP file drop, and then uses Ballerina connectors to coordinate
with the external systems involved in the process.

---

## Categories

The use cases are grouped by the dominant workflow pattern they
demonstrate so you can jump straight to the category that matches your
problem:

- [Human Task](#human-task) — durable pause-and-resume for approvals,
  reviews, and customer callbacks
- [Transactional Workflow](#transactional-workflow) — multi-step
  automation that coordinates many external systems
- [Error Handling](#error-handling) — propagation, fallback,
  compensation, and graceful completion across real integrations

---

### Human Task

Workflows that pause durably for an external decision — an approval, a
review, a replay instruction, an SLA-bounded confirmation — and resume when the human or the
system of record sends data back via `workflow:sendData(...)`. They
follow the project-wide three-step pattern: **notify → create task →
webhook callback**.

| # | Use case | Domain | Trigger | Connectors used |
| - | -------- | ------ | ------- | --------------- |
| 1 | [hr-onboarding](./hr-onboarding/) | HR | HTTP webhook | `slack`, `googleapis.gmail`, `jira` |
| 2 | [it-access-request](./it-access-request/) | IT | HTTP webhook | `slack`, `jira`, `twilio` |
| 3 | [finance-invoice-processing](./finance-invoice-processing/) | Finance | SFTP listener | `ftp`, `email` (SMTP), `salesforce` |
| 4 | [sales-lead-qualification](./sales-lead-qualification/) | Sales | HTTP webhook | `salesforce`, `twilio`, `slack` |
| 5 | [support-case-resolution](./support-case-resolution/) | Customer Support | REST API and callbacks | `salesforce`, `slack`, `googleapis.gmail` |
| 6 | [clinical-message-replay](./clinical-message-replay/) | Healthcare interoperability | HTTP API and callbacks | `http`, `jira`, `slack` |

---

### Transactional Workflow

Workflows that coordinate a sequence of activities across multiple
external systems as a single durable business transaction. There is no
human in the loop — the workflow's job is to make sure every downstream
system ends up in a consistent state, even when the program is restarted/crashed
mid-flight.

| # | Use case | Domain | Trigger | Connectors used |
| - | -------- | ------ | ------- | --------------- |
| 7 | [sheets-campaign-sync](./sheets-campaign-sync/) | Marketing Operations | Google Sheets append/update | `trigger.google.sheets`, `slack`, `salesforce`, `googleapis.gmail` |
| 8 | [mssql-inventory-replenishment](./mssql-inventory-replenishment/) | Supply Chain | SQL Server CDC | `mssql`, `mssql.cdc.driver`, `slack`, `salesforce`, `googleapis.gmail` |
| 9 | [crm-erp-customer-sync](./crm-erp-customer-sync/) | Sales Operations / RevOps | HTTP webhook | `salesforce`, `slack`, `googleapis.gmail` |

#### Where this pattern applies

The same shape — durable trigger, ordered activity calls, optional
compensation — fits many other integration scenarios:

- **Multi-leg fund transfer.** A debit on the source account succeeds,
  but the credit on the destination account fails. The workflow runs a
  compensating "reverse debit" activity and reports the transfer as
  rolled back, so the customer is never left with a missing balance.
- **Travel booking (hotel + flight + car).** The hotel and flight are
  already booked when the car-rental provider rejects the reservation.
  The workflow cancels the hotel and flight bookings in reverse order
  rather than leaving partial state behind.
- **Subscription provisioning.** Account creation succeeds and a
  license is assigned, but the downstream billing system rejects the
  new contract. The workflow revokes the license and deprovisions the
  account so no orphaned records remain.
- **E-commerce checkout.** Inventory is reserved, payment authorization
  is captured, and shipping is scheduled. If the carrier integration
  fails permanently, the workflow refunds the payment and releases the
  inventory hold so the SKU goes back on sale.
- **CRM ↔ ERP customer sync.** A new customer record in the CRM must
  be replicated to the ERP, the data warehouse, and the support
  ticketing system. Each downstream write is an activity; if one of
  them fails after retries, the workflow can either compensate or
  surface the partial state for an operator to resolve.

---

### Error Handling

Workflows that show how to react when an activity fails: propagate the
error to the caller, fall back to an alternative channel, tolerate
non-critical failures, or compensate already-committed work across
multiple systems.

| # | Use case | Domain | Trigger | Connectors used |
| - | -------- | ------ | ------- | --------------- |
| 10 | [notification-fallback](./notification-fallback/) | Customer messaging | HTTP API | `googleapis.gmail`, `twilio`, `slack` |
| 11 | [subscription-provisioning-saga](./subscription-provisioning-saga/) | Subscription billing | HTTP API | `salesforce`, `slack`, `googleapis.gmail` |
| 12 | [ehr-downtime-recovery](./ehr-downtime-recovery/) | Healthcare interoperability | HTTP API | `http`, `slack`, `googleapis.gmail` |

#### Where this pattern applies

- **Correcting transformation errors.** An activity that maps an
  incoming payload to a downstream system has faulty mapping logic
  (for example, the wrong field is sent as the customer ID). The
  workflow propagates the error and transitions to `Failed`. Once the
  mapping is fixed and redeployed, the affected executions can be
  replayed to push the corrected data to the target system.
- **Recovering from downstream system downtime.** A target system,
  such as a CRM, a payments gateway, or an EHR, goes offline. Activity
  calls keep failing and the workflow either retries durably or falls
  back to a secondary channel — for example, switching the customer
  notification path from email to SMS. When the primary system is
  back online, the queued or paused workflows resume from the last
  successful step instead of restarting from the top.
- **Retrying after over-strict validation.** A validation activity
  rejects records that should have been accepted because a business
  rule was tightened too aggressively. The rejected workflows surface
  as `Failed` and, after the rule is relaxed, they can be replayed
  through the corrected validator without losing any of the upstream
  data already gathered.
- **Riding out server disruptions.** A worker process crashes or a
  pod is rescheduled while many workflows are mid-flight. Because
  workflow state is durable, every in-flight execution resumes
  automatically from the last completed activity once a worker is
  available again — no need to re-trigger the upstream caller or
  replay messages by hand.
- **Tolerating non-critical side effects.** The core business outcome
  (the order is shipped, the contract is signed) must complete even
  if a side effect such as sending an audit email or pushing an
  analytics event fails. The workflow captures the error from the
  non-critical activity, records the skipped step, and still
  completes successfully.

---

All examples use `mode = "IN_MEMORY"` for the workflow scheduler so they
compile and run without an external Temporal server. Switch
`Config.toml` to `LOCAL` / `SELF_HOSTED` / `CLOUD` for production
deployments.

> **Real credentials are not required to compile these examples.** The
> connectors only attempt to authenticate when a client is created and
> used at runtime. Each example documents the configurable secrets
> needed to actually run end-to-end.

---

## System triggers

Workflow orchestration usually starts from an external system event: an
API call, webhook, file arrival, spreadsheet change, database change, or
scheduled poll. Ballerina lets each of those events enter through the
listener that best matches the source system, then start a workflow
with `workflow:run(...)`.

Common trigger patterns include:

| Caller | Typical trigger point |
| ------ | --------------------- |
| Mobile app | REST API or GraphQL mutation |
| Web app | REST API or GraphQL mutation |
| Automated system | Webhook, such as a GitHub webhook, database trigger, or SaaS trigger |

The use cases in this directory use these trigger types:

| Trigger                           | Used in                                                  |
| --------------------------------- | -------------------------------------------------------- |
| `http:Listener` (webhook/API)     | `hr-onboarding`, `it-access-request`, `sales-lead-qualification`, `support-case-resolution`, `clinical-message-replay`, `crm-erp-customer-sync`, `notification-fallback`, `subscription-provisioning-saga`, `ehr-downtime-recovery` |
| `ftp:Listener` (SFTP file watch)  | `finance-invoice-processing`                             |
| Google Sheets trigger             | `sheets-campaign-sync`                                   |
| `mssql:CdcListener`               | `mssql-inventory-replenishment`                          |

---

## Pattern: Human interactions

Some integration workflows include a human-in-the-loop step, such as an
approval, review, or customer confirmation. These flows are different
from fully automated system-triggered workflows because the workflow must
pause durably and resume later when the external interaction completes.

The Ballerina Workflow module does not yet ship a built-in user portal.
Full human task management, such as access control and delegation, is
currently outside the scope of this module and is expected to be managed
in external systems.
As a best practice, we recommend the following three-step pattern for
managing human interactions:

1. **Notify the user** via a real channel — Slack, Email (Gmail/SMTP),
   or SMS (Twilio).
2. **Create a task in an external system of record** — a Jira issue, a
   Salesforce Case/Task, etc. — so there is an auditable, assignable item
   the user can act on.
3. **Receive completion** through either:
   * a **webhook callback** from the system of record (preferred — the
     workflow exposes an HTTP endpoint that calls
     `workflow:sendData(...)`), or
   * a **polling activity** that asks the system of record for the task's
     status until it is `Done` / `Closed`.

In the workflow, the human-step boundary is a `wait events.<name>` (or
`ctx->await([events.<name>], timeout = {...})`) on a typed signal
channel.

---

## Build & run any example

```bash
cd <use-case-folder>
bal build      # compiles the workflow + connector dependencies
bal run        # starts the listener(s) and the workflow scheduler
```

Each example's `README.md` lists the HTTP/SFTP entry points and example
`curl` commands.
