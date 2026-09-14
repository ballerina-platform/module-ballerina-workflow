# Observe Workflows

The workflow module plugs into Ballerina's standard observability pipeline: build with
observability included, switch on metrics and tracing in `Config.toml`, and workflow
telemetry flows through the same reporters your HTTP services already use.

```toml
# Ballerina.toml
[build-options]
observabilityIncluded = true
```

## Metrics with Prometheus and Grafana

Ballerina's supported metrics reporter is Prometheus, via `ballerinax/prometheus`:

```ballerina
import ballerinax/prometheus as _;
```

```toml
# Config.toml
[ballerina.observe]
metricsEnabled = true
metricsReporter = "prometheus"
```

The reporter serves `http://localhost:9797/metrics` for Prometheus to scrape; point a
Grafana dashboard at Prometheus for graphs and alerts. Two things about how the reporter
exposes names: a counter is published with a `_value` suffix, so the queries below read
`workflow_events_total_value` in Prometheus (a summary publishes `_value`, `_mean`, `_max`
and the percentiles under the base name), and tag values are sanitised — a workflow type
`workflow-orderFlow` appears as `workflow_orderFlow`.

One counter carries every workflow lifecycle event, and logical metrics are derived from
it by tag filters:

| What you want | PromQL |
|---|---|
| Runs started | `workflow_events_total{event="started", task_kind="none"}` |
| Runs completed / failed | `workflow_events_total{event="closed", task_kind="none", outcome="success"/"failure"}` |
| Activity attempts and failures | `workflow_events_total{event="activity_executed", outcome=…}` |
| Data events delivered | `workflow_events_total{event="data_sent"}` |
| Human tasks created | `workflow_events_total{event="started", task_kind="HUMAN_TASK"}` |
| Human tasks decided, by outcome | `workflow_events_total{event="closed", task_kind="HUMAN_TASK", outcome=…}` — a rejection closes with `error_type="HUMANTASK_REJECTED"`, an expiry with `error_type="HUMANTASK_TIMEOUT"` |
| Decisions people submitted (accepted / refused) | `workflow_events_total{event="task_decided", outcome=…}` |
| Review activities decided | the same queries with `task_kind="REVIEW_ACTIVITY"` |
| Runs suspended / resumed / terminated / cancelled | `workflow_events_total{event="suspended"}`, `"resumed"`, `"terminated"`, `"cancelled"` — `outcome="failure"` with `error_type="WorkflowNotFound"` when the instance did not exist |

Durations are summaries with p50/p75/p90/p95/p99 over a five-minute window:
`workflow_duration_seconds` (run start to close — for a human task, that is its
**time to decision**, filter by `task_kind` and `task_name`) and
`workflow_activity_duration_seconds` (per attempt).

### Durable agents

A durable agent's loop is instrumented step by step, on the agent's own `workflow_type`,
so an agent dashboard is the same counter with different `event` values:

| What you want | PromQL |
|---|---|
| Thinking — model calls per agent | `workflow_events_total{event="agent_model_called", workflow_type="workflow-<agent>"}` (`activity_type` tells `llmChat` from `generate`) |
| Tool calls, by tool and outcome | `workflow_events_total{event="agent_tool_called", tool_name=…, outcome=…}` |
| Human tasks the agent created | `workflow_events_total{event="started", task_kind="HUMAN_TASK", task_name=~"<agent>\\..*"}` |
| …and how they were decided | `workflow_events_total{event="agent_task_awaited", task_name=…, outcome=…}` — `error_type` is `HUMANTASK_REJECTED` or `HUMANTASK_TIMEOUT` |
| Events received / waits that timed out | `workflow_events_total{event="agent_event_received", data_name=…, outcome=…}` — a timeout is `outcome="failure", error_type="TIMEOUT"` |
| Sleeps, and how they ended | `workflow_events_total{event="agent_slept", action="completed"/"interrupted"}` |
| Gated tools a person reviewed | `workflow_events_total{event="agent_tool_reviewed", tool_name=…, action=…}` |

Every step also lands in `workflow_agent_step_duration_seconds` (same percentiles, on the
engine's clock, tagged with the step's `event`, `tool_name`, `data_name`, `task_name` and
`outcome`): filter `event="agent_task_awaited"` for **creation-to-completion time** of the
tasks an agent hands to people, `event="agent_event_received"` for how long it waited on
each event, `event="agent_model_called"` for model latency as the loop saw it (retries
included). Token counts are not on these metrics — `ballerina/ai`'s own `ai.observe` spans
record them from inside the `llmChat` activity when tracing is on.

Every metric also carries the identity tags `module="workflow"`, `type` (`client` or
`worker`), `remote_url`, `task_queue`, and `host`, and every increment carries the same
label keys — a dimension that does not apply holds `none`, so `sum by (...)` never splits
a series.

## Traces with Jaeger

Tracing answers *what happened inside one request*: starting a workflow, sending it data,
deciding a human task each leave a span nested in the caller's trace, tagged with the
workflow type, instance ID, and — on decisions — who decided and in which roles.

[Jaeger](https://www.jaegertracing.io) is Ballerina's supported tracing backend, via
`ballerinax/jaeger`:

```ballerina
import ballerinax/jaeger as _;
```

```toml
# Config.toml
[ballerina.observe]
tracingEnabled = true
tracingProvider = "jaeger"

[ballerinax.jaeger]
agentPort = 4317    # Jaeger's OTLP gRPC port; the module's default (55680) is the legacy OTLP port
```

Run Jaeger locally (it accepts the OTLP traffic the provider sends on 4317) and open the
UI at `http://localhost:16686`:

```shell
docker run -d --name jaeger \
    -p 16686:16686 -p 4317:4317 -p 4318:4318 \
    jaegertracing/all-in-one:1.60
```

In the UI, search by the span tags: `workflow.instance.id` finds every client-side
operation that touched one instance; `user.id` finds every decision one person made. The
spans deliberately stop at the client boundary — a durable run may execute for days across
restarts, so execution-side visibility comes from the engine history and the management
API, joined to a trace by `workflow.instance.id`.

By default the trace sampler is `const` with rate 1 (every trace is reported); for
production volumes configure sampling under `[ballerinax.jaeger]` (`samplerType`,
`samplerParam`, `reporterFlushInterval`, `reporterBufferSize`). Spans are exported in
batches: a service keeps flushing as it runs, but a short-lived program should stay up a
few seconds past its last operation or its final batch may never leave the process.

## Log-based metrics and the audit stream

Platforms that build metrics from log records instead of scrapes (such as the WSO2
Integration Control Plane) can index the module's structured samples — one record per
workflow event under `logger="workflow-metrics"`, the control operations and the agent
steps included (`agent.model_called`, `agent.tool_called`, …, with the same fields as the
registry tags) — and the human-decision **audit entries**, which are ordinary
`ballerina/log` output written whether or not any observability is enabled. See the proposal in
[`proposals/observability-integration.md`](proposals/observability-integration.md) for the
full vocabulary and the content-capture switches.
