# Real-World Use Cases for Ballerina Workflow

This directory contains a curated set of integration and automation use
cases across HR, IT, Finance, Sales, Customer Support, Marketing
Operations, and Supply Chain. They demonstrate how to build durable,
long-running business processes with the
[`ballerina/workflow`](../ballerina) module **using real Ballerina
connectors** (`ballerinax/slack`, `ballerinax/jira`, `ballerinax/twilio`,
`ballerinax/salesforce`, `ballerinax/googleapis.gmail`, `ballerina/ftp`,
`ballerina/email`, `ballerinax/trigger.google.sheets`, `ballerinax/mssql`,
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

## Use cases

| # | Use case | Domain | Trigger | Connectors used |
| - | -------- | ------ | ------- | --------------- |
| 1 | [hr-onboarding](./hr-onboarding/) | HR | HTTP webhook | `slack`, `googleapis.gmail`, `jira` |
| 2 | [it-access-request](./it-access-request/) | IT | HTTP webhook | `slack`, `jira`, `twilio` |
| 3 | [finance-invoice-processing](./finance-invoice-processing/) | Finance | SFTP listener | `ftp`, `email` (SMTP), `salesforce` |
| 4 | [sales-lead-qualification](./sales-lead-qualification/) | Sales | HTTP webhook | `salesforce`, `twilio`, `slack` |
| 5 | [support-case-resolution](./support-case-resolution/) | Customer Support | REST API and callbacks | `salesforce`, `slack`, `googleapis.gmail` |
| 6 | [sheets-campaign-sync](./sheets-campaign-sync/) | Marketing Operations | Google Sheets append/update | `trigger.google.sheets`, `slack`, `salesforce`, `googleapis.gmail` |
| 7 | [mssql-inventory-replenishment](./mssql-inventory-replenishment/) | Supply Chain | SQL Server CDC | `mssql`, `mssql.cdc.driver`, `slack`, `salesforce`, `googleapis.gmail` |

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
scheduled poll. Ballerina lets each of those events enter through listener that best matches the source system, then start a
workflow with `workflow:run(...)`.

Common trigger patterns include:

| Caller | Typical trigger point |
| ------ | --------------------- |
| Mobile app | REST API or GraphQL mutation |
| Web app | REST API or GraphQL mutation |
| Automated system | Webhook, such as a GitHub webhook, database trigger, or SaaS trigger |

The use cases in this directory use these trigger types:

| Trigger                           | Used in                                                  |
| --------------------------------- | -------------------------------------------------------- |
| `http:Listener` (webhook/API)     | `hr-onboarding`, `it-access-request`, `sales-lead-qualification`, `support-case-resolution` |
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
