# Proposal: Observability integration for the workflow module

- Status: Implemented (initial minimal integration)
- Authors: @hasithaa
- Reviewed by: TBD

## Summary

Add first-class observability — distributed tracing spans and runtime metrics — to the
`ballerina/workflow` module, covering both normal workflows and durable agent workflows.
The integration lives entirely in the module's durable-engine wrapper layer and plugs into
Ballerina's standard observability pipeline (`observabilityIncluded = true`, Prometheus /
Jaeger / New Relic extensions), so users get workflow telemetry with the same switches
they already use for HTTP services. No business data (inputs, payloads, results) is ever
recorded — only structural identifiers such as workflow types, instance IDs, and declared
event names.

## Motivation

The `ballerina/ai` module already ships an exported `ai.observe` submodule that traces
agent operations following the OpenTelemetry GenAI semantic conventions. Workflow
applications have no equivalent: a service that starts workflows, sends data, and
completes human tasks produces traces that end at the service boundary, and no metrics
exist for workflow throughput, failures, or activity latency.

The workflow engine (Temporal) is a separate service, so its server-side observability
cannot stand in for application-side telemetry — and the engine's own client metrics/
tracing hooks would tie the module's observability story to a vendor-specific pipeline.
Instead, this proposal instruments the module's own wrapper layer and emits through
Ballerina's observability runtime.

## Design

The design mirrors `ai.observe` where the execution model allows it, and deliberately
diverges where durable execution makes the AI module's approach incorrect.

### The replay constraint

Workflow bodies are re-executed ("replayed") deterministically by the durable engine
after worker crashes, on queries, and during resets. Two consequences:

1. **Spans must not be emitted from inside a workflow body** — every replay would emit
   duplicates, and wall-clock timings taken inside a body are meaningless. Execution-side
   visibility already exists through the engine history and the management API
   (`getWorkflowHistory`, `getActivityTree`, `getExecutionGraph`).
2. **Worker-side metrics must be replay-gated** — a completion is only counted when the
   engine reports fresh progress (`not replaying`), so crash recovery and queries never
   double-count.

### Tracing: the `workflow.observe` submodule (Ballerina, client side)

A new exported submodule `workflow.observe` provides typed span classes over
`ballerina/observe`, one per instrumented client-side operation:

| Span | Created by | Tags |
|---|---|---|
| `StartWorkflowSpan` | `workflow:run` | `workflow.type`, `workflow.instance.id` |
| `SendDataSpan` | `workflow:sendData` | `workflow.instance.id`, `workflow.data.name` |
| `GetWorkflowResultSpan` | `workflow:getWorkflowResult` | `workflow.instance.id` |
| `CompleteHumanTaskSpan` | `workflow:completeHumanTask` | `workflow.human_task.id` |
| `StartAgentSpan` | `DurableAgent.run` | `gen_ai.agent.name`, `workflow.instance.id` |
| `SendAgentEventSpan` | `DurableAgent.sendEvent` | `gen_ai.agent.name`, `workflow.instance.id`, `workflow.event.name` |

Every span carries `workflow.operation.name` and `span.type = workflow` (mirroring
`span.type = ai` in the AI module), and closes with error status via
`observe:finishSpanWithError` on failure. Agent spans reuse the OpenTelemetry GenAI
attribute `gen_ai.agent.name` so agent traces correlate with `ai.observe` spans.

Spans are recorded only when **both** hold:

- tracing is enabled for the program (`observe:isTracingEnabled()`), and
- the call is **not** executing inside a workflow body (checked natively via the engine's
  thread-local workflow context) — the replay constraint above.

Because these calls run in the caller's strand (typically an HTTP resource), the spans
nest naturally into the service's existing request trace. When observability is not
included or tracing is disabled, every span operation is a no-op.

The public API functions keep their exact signatures; they become thin Ballerina wrappers
around renamed private externals. Functions whose signatures use inferred typedesc
parameters (`typedesc<anydata> T = <>`, e.g. `DurableAgent.getResult`) must remain
external and are not traced in this iteration.

### Metrics: the wrapper-layer Java hooks (worker + client side)

Metrics are recorded natively through the Ballerina runtime metric registry
(`io.ballerina.runtime.observability.metrics`), the same registry the Prometheus/
New Relic metric extensions publish from. All recording is gated on
`ObserveUtils.isMetricsEnabled()` and never throws into workflow execution.

| Metric | Type | Tags | Recorded at |
|---|---|---|---|
| `workflow_starts_total` | counter | `workflow_type` | client-side top-level start |
| `workflow_completions_total` | counter | `workflow_type`, `status` | workflow adapter, replay-gated |
| `workflow_duration_seconds` | gauge (summary) | `workflow_type`, `status` | run start → completion, engine time |
| `workflow_activity_executions_total` | counter | `activity_type`, `status` | activity adapter, per attempt |
| `workflow_activity_duration_seconds` | gauge (summary) | `activity_type`, `status` | activity adapter, wall clock |
| `workflow_data_events_sent_total` | counter | `data_name` | client-side data delivery |

Placement rationale — every execution funnels through two dynamic adapters in the
wrapper layer, so instrumenting them covers everything with two hooks:

- **Workflow adapter** (`BallerinaWorkflowAdapter.execute`): covers user workflows,
  durable agent runner workflows (`workflow-<agentName>` types), human task child
  workflows (`humantask-…` types), and review-activity child workflows
  (`reviewactivity-…` types) — each distinguishable by its `workflow_type` tag.
  Duration uses the engine's deterministic clock against the run-start timestamp,
  so it is exact even across worker restarts.
- **Activity adapter** (`BallerinaActivityAdapter.execute`): covers user activities,
  built-in activities, and — for durable agents — every LLM turn (`…​.llmChat`) and tool
  dispatch, since agent steps execute as activities. Activity attempts are never
  replayed, so each record is a real execution; retries appear as multiple attempts.

Tag cardinality is bounded by construction: tags are workflow/activity **types** and
declared event names (compile-time sets), never instance IDs.

### What is deliberately out of scope (this iteration)

- Spans inside workflow bodies (activity calls, sleeps, awaits) — blocked by the replay
  constraint; revisit with engine-side interceptors plus trace-context propagation
  through headers if cross-boundary traces are needed.
- The engine SDK's own client metrics scope — vendor-specific pipeline; the wrapper-layer
  metrics above cover the application-facing signals.
- Tracing for inferred-typedesc APIs (`getResult`, `waitForResult`, `callActivity`, …).
- Management/REST API metrics (already observable as regular HTTP listeners).
- Worker liveness/slot gauges.

## Backward compatibility

None of the public API signatures change; wrappers preserve behavior exactly and the new
submodule is purely additive. Programs built without `observabilityIncluded = true` (or
with observability disabled at runtime) take the no-op paths. The `workflow.observe`
submodule is exported so applications and future tooling can attach additional tags.

## Usage

```toml
# Ballerina.toml
[build-options]
observabilityIncluded = true
```

```toml
# Config.toml
[ballerina.observe]
metricsEnabled = true
metricsReporter = "prometheus"
tracingEnabled = true
tracingProvider = "jaeger"
```

No workflow-module configuration is required; the standard Ballerina observability
switches control everything.

## Known artifact

With `observabilityIncluded = true`, the Ballerina runtime auto-instruments every remote
method call — including `ctx->callActivity` and the other `Context` remote calls — from
inside workflow bodies. Those auto-spans predate this proposal, start their own traces
(no client-side parent), and can repeat under replay. They are emitted by the runtime,
not by this module; suppressing them would require engine-side gating of the runtime
observation hooks and is left for a future iteration.

## Testing

- Module unit tests pass unchanged (behavior-preserving wrappers).
- With observability off (the default for all existing users), every new code path
  reduces to a flag check.
- Verified end-to-end with a smoke application (`observabilityIncluded = true`,
  Prometheus metrics + mock tracer, IN_MEMORY mode): all six metrics emit with the
  expected tags for completed and failed workflows, and `start_workflow`, `send_data`,
  and `get_workflow_result` spans nest under the caller's trace, carrying
  `error.message` on failures.
