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

## What's Next

- [Get Started](get-started.md) — Set up and run your first workflow
