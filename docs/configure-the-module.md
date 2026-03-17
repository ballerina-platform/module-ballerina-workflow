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
| `taskQueue` | `BALLERINA_WORKFLOW_TASK_QUEUE` | Task queue for workflow and activity execution |
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
- [API Reference](api-reference.md) — Complete function and type reference
