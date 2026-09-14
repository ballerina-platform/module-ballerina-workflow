# Configure the Module

The workflow module supports multiple deployment modes through `Config.toml`. All configuration uses flat `configurable` variables under `[ballerina.workflow]`. The `mode` field selects the deployment mode; irrelevant fields for a given mode are ignored at init time.

## Deployment Modes

| Mode | Use Case |
|------|----------|
| `LOCAL` | Local Temporal server for development (default) |
| `CLOUD` | Temporal Cloud managed service |
| `SELF_HOSTED` | Self-hosted Temporal server |
| `IN_MEMORY` | In-memory testing (no server needed) |

## Local Development (Default)

Connect to a local Temporal server. This is the default if no `Config.toml` is provided.

```toml
[ballerina.workflow]
mode = "LOCAL"
url = "localhost:7233"
namespace = "default"
taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE"
maxConcurrentWorkflows = 100
maxConcurrentActivities = 100
```

Start a local server using the Temporal CLI:

```bash
temporal server start-dev
```

## Temporal Cloud

Connect to Temporal Cloud with an API key:

```toml
[ballerina.workflow]
mode = "CLOUD"
url = "my-ns.my-account.tmprl.cloud:7233"
namespace = "my-ns.my-account"
authApiKey = "my-api-key"
taskQueue = "MY_TASK_QUEUE"
```

## Self-Hosted Server

Connect to a self-hosted Temporal server, optionally with mTLS authentication:

```toml
[ballerina.workflow]
mode = "SELF_HOSTED"
url = "temporal.mycompany.com:7233"
namespace = "production"
authMtlsCert = "/path/to/client.pem"
authMtlsKey = "/path/to/client.key"
taskQueue = "PRODUCTION_QUEUE"
maxConcurrentWorkflows = 200
maxConcurrentActivities = 200
```

You can also connect without authentication:

```toml
[ballerina.workflow]
mode = "SELF_HOSTED"
url = "temporal.internal:7233"
namespace = "default"
```

## Task Queue Naming

The `taskQueue` value must be **unique for each workflow integration deployment**. A task queue is how Temporal routes work to the correct worker — if two deployments share the same task queue on the same Temporal cluster, they will compete for each other's tasks and produce unpredictable results.

Integrations sharing a **namespace** (a project) also share management-API visibility: listings identify each row's `namespace` and `taskQueue`, accept an optional `taskQueue` filter, and task mutations (complete/fail/review decisions) are only accepted by the integration serving that task's queue — other integrations receive a `403`.

> If you run multiple workflow integrations (e.g., an order service and a customer onboarding service) on the same Temporal cluster, give each a distinct task queue name:

```toml
# Order service deployment
[ballerina.workflow]
taskQueue = "ORDER_SERVICE_QUEUE"

# Customer onboarding deployment (separate application)
[ballerina.workflow]
taskQueue = "ONBOARDING_SERVICE_QUEUE"
```

A good convention is to use a name that reflects the service and environment, for example `ORDER_SERVICE_PROD` or `INVENTORY_SERVICE_STAGING`. The same task queue name can safely be used across multiple replicas of the **same** deployment — Temporal load-balances across them correctly.

## In-Memory Mode

For testing without an external server:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"
```

## Configuration Reference

### Connection & Scheduler

| Parameter | Default | Description |
|-----------|---------|-------------|
| `mode` | `LOCAL` | Deployment mode (`LOCAL`, `CLOUD`, `SELF_HOSTED`, `IN_MEMORY`) |
| `url` | `localhost:7233` | Temporal server URL |
| `namespace` | `default` | Workflow namespace |
| `taskQueue` | `BALLERINA_WORKFLOW_TASK_QUEUE` | Task queue for workflow and activity execution. **Must be unique per deployment** — see [Task Queue Naming](#task-queue-naming) below. |
| `maxConcurrentWorkflows` | `100` | Maximum concurrent workflow executions |
| `maxConcurrentActivities` | `100` | Maximum concurrent activity executions |

### Authentication

| Field | Type | Description |
|-------|------|-------------|
| `authApiKey` | `string?` | API key for Temporal Cloud authentication |
| `authMtlsCert` | `string?` | Path to mTLS client certificate (PEM format) |
| `authMtlsKey` | `string?` | Path to mTLS client private key |
| `authCaCert` | `string?` | Path to CA certificate for verifying the server's TLS certificate (PEM format). Use when the server uses a private or self-signed CA |

### Default Activity Retry Policy

Configure a default retry policy for all activities:

```toml
[ballerina.workflow]
activityRetryInitialInterval = 1
activityRetryBackoffCoefficient = 2.0
activityRetryMaximumAttempts = 3
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `activityRetryInitialInterval` | `1` | Initial delay (seconds) before the first retry |
| `activityRetryBackoffCoefficient` | `2.0` | Multiplier applied to the interval after each retry |
| `activityRetryMaximumInterval` | `0` | Maximum delay between retries (0 = no limit) |
| `activityRetryMaximumAttempts` | `1` | Maximum number of retry attempts (1 = no retries) |

### Observability

The module plugs into Ballerina's observability pipeline: build with
`observabilityIncluded = true` and switch on metrics and tracing under `[ballerina.observe]`
as for any Ballerina program. Every decision a person makes on a human task or a review
activity is also written to the log as an audit entry — who decided, in which roles, what,
on which task, when, and whether it was accepted — whether or not observability is enabled.

What those records *contain* is governed under `[ballerina.workflow.observe]`:

```toml
[ballerina.workflow.observe]
captureHumanTaskContent = true
captureActivityContent = true
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `captureHumanTaskContent` | `true` | Record a decision's content on its span and audit entry: what the person was shown (the human task's input, or the arguments of the activity under review) and what they submitted (the completion result, the rejection reason and details, or the review decision's input and feedback). Who decided, what and when are recorded regardless |
| `captureActivityContent` | `true` | Log every activity execution attempt's arguments and result (or error) to the worker's log, beside its outcome and duration. Long values are truncated |
| `publishMetricSamples` | `true` | Publish one structured log record per workflow event — run started/closed with duration, activity attempt with outcome and duration, data event delivered, task decided — tagged `logger = "workflow-metrics"`, so a log pipeline can build a workflow metrics index the way `ballerinax/metrics.logs` feeds HTTP metrics. Structural fields only |

Both are on by default, as `ballerina/ai`'s `ai.observe` records prompt and completion
content: the engine already persists everything a workflow handles — inputs, activity
arguments and results, task submissions — in the run's history for the life of the run, so
the log carries nothing the workflow store does not, and telemetry retention retires its
copy on its own schedule. The module's standing advice still applies — keep sensitive data
out of workflow inputs, activity arguments and task results altogether. Where that is not
possible and the content must not leave the workflow store, switch the relevant capture off.

## What's Next

- [Get Started](get-started.md) — Set up and run your first workflow
