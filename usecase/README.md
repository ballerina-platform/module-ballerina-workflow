# Real-World Use Cases for Ballerina Workflow

This directory contains a curated set of integration/automation use cases —
spanning HR, IT, Finance, and Sales — that demonstrate how to build
durable, long-running business processes with the
[`ballerina/workflow`](../ballerina) module **using real Ballerina
connectors** (`ballerinax/slack`, `ballerinax/jira`, `ballerinax/twilio`,
`ballerinax/salesforce`, `ballerinax/googleapis.gmail`, `ballerina/ftp`,
`ballerina/email`, …).

The examples avoid mock activities — every "do something with the outside
world" step is implemented as a real connector call inside an
`@workflow:Activity` function.

---

## Why workflows for integration work?

A workflow is a piece of code whose execution state survives worker
restarts, crashes, and process upgrades. That makes it a good fit for
business processes that:

* take **minutes, hours, or days** (waiting for a human, a file, a
  callback, an SLA timer);
* need to be **resumed exactly where they left off** after a restart; and
* must **call out to many external systems** with retries, timeouts, and
  compensation logic.

Each use case here triggers from a real-world event — an HTTP webhook, an
SFTP file drop — and uses Ballerina connectors to interact with the
external world.

---

## Pattern: human interaction without a built-in user portal

The Ballerina Workflow module does not yet ship a user portal for
human-in-the-loop steps. Instead, every example follows this
three-step pattern:

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

## System triggers used here

| Trigger                           | Used in                                                  |
| --------------------------------- | -------------------------------------------------------- |
| `http:Listener` (webhook)         | `hr-onboarding`, `it-access-request`, `sales-lead-qualification` |
| `ftp:Listener` (SFTP file watch)  | `finance-invoice-processing`                             |

Polling-based triggers are also straightforward: a `task:Job` (from
`ballerina/task`) or a `workflow` that runs in a loop with `ctx.sleep(...)`
can query an external API on a schedule and start a downstream workflow
when new work appears.

---

## Use cases

| # | Use case                                              | Domain   | Trigger        | Connectors used                                                          |
| - | ----------------------------------------------------- | -------- | -------------- | ------------------------------------------------------------------------ |
| 1 | [hr-onboarding](./hr-onboarding/)                     | HR       | HTTP webhook   | `slack`, `googleapis.gmail`, `jira`                                      |
| 2 | [it-access-request](./it-access-request/)             | IT       | HTTP webhook   | `slack`, `jira`, `twilio`                                                |
| 3 | [finance-invoice-processing](./finance-invoice-processing/) | Finance  | SFTP listener  | `ftp`, `email` (SMTP), `salesforce`                                      |
| 4 | [sales-lead-qualification](./sales-lead-qualification/) | Sales    | HTTP webhook   | `salesforce`, `twilio`, `slack`                                          |

All examples use `mode = "IN_MEMORY"` for the workflow scheduler so they
compile and run without an external Temporal server. Switch
`Config.toml` to `LOCAL` / `SELF_HOSTED` / `CLOUD` for production
deployments.

> **Real credentials are not required to compile these examples.** The
> connectors only attempt to authenticate when their client is created
> and used at runtime. Each example documents the configurable secrets
> needed to actually run end-to-end.

---

## Build & run any example

```bash
cd <use-case-folder>
bal build      # compiles the workflow + connector dependencies
bal run        # starts the listener(s) and the workflow scheduler
```

Each example's `README.md` lists the HTTP/SFTP entry points and example
`curl` commands.
