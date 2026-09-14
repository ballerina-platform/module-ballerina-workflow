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
they already use for HTTP services. Metrics and metric samples carry only structural
identifiers — workflow types, instance IDs, declared event names — never business data.
Task and activity **content** (what a person was shown and submitted, an activity's
arguments and result) is recorded on decision spans, audit entries and the activity
content log by the content-capture switches, which default to **on** and can be turned
off per deployment (see "Content capture" below).

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
| `TaskDecisionSpan` | `completeHumanTask`, `management:failHumanTask` | `workflow.human_task.id`, `workflow.task.action`, `workflow.task.name`, `user.id`, `user.roles`, `user.identity.source`, `workflow.task.input`, `workflow.task.content` |
| `TaskDecisionSpan` | `management:completeReviewActivity` | `workflow.review_activity.id`, `workflow.task.action`, `workflow.task.name`, `user.id`, `user.roles`, `user.identity.source`, `workflow.task.input`, `workflow.task.content` |
| `StartAgentSpan` | `DurableAgent.run` | `gen_ai.agent.name`, `workflow.instance.id` |
| `SendAgentEventSpan` | `DurableAgent.sendData` | `gen_ai.agent.name`, `workflow.instance.id`, `workflow.event.name` |

Every span carries `workflow.operation.name` and `span.type = workflow` (mirroring
`span.type = ai` in the AI module), plus the identity tags below (`module`,
`type = client`, `remote.url`, `task.queue`, `host`), and closes with error status via
`observe:finishSpanWithError` on failure. Agent spans reuse the OpenTelemetry GenAI
attribute `gen_ai.agent.name` so agent traces correlate with `ai.observe` spans; decision
spans reuse the OpenTelemetry `user.id` and `user.roles` attributes for who decided.

### Metrics: one events counter, uniform labels

The metrics follow the Ballerina integration observability standard (the model the file
integration modules share): one lifecycle counter carries every event, logical metrics are
derived from it by tag filters, and module-specific values appear only in tag values —
never in metric names.

**Identity tags**, present on every metric sample and every span, pin the origin of an
observation:

| Tag | Values | Notes |
|---|---|---|
| `module` | `workflow` | identifies the Ballerina module on shared dashboards |
| `type` | `client`, `worker` | a client API call vs a worker-side execution |
| `remote_url` | engine `host:port`, `in-memory` | the engine endpoint, from the connection configuration |
| `task_queue` | task queue name | the workflow analog of a listener's watched path |
| `host` | local hostname | `none` when resolution fails |

**`workflow_events_total`** counts every lifecycle event. Every increment carries the same
label keys — a key that does not apply to an event holds the sentinel `none` rather than
being omitted, so a series with labels *{a, b}* never splits from one with *{a, b, c}* and
tag-filtered aggregations neither drop nor double-count rows:

| Label | Values |
|---|---|
| `event` | lifecycle: `started`, `closed`, `activity_executed`, `data_sent`, `task_decided`; control: `suspended`, `resumed`, `terminated`, `cancelled`; agent steps: `agent_model_called`, `agent_tool_called`, `agent_task_awaited`, `agent_event_received`, `agent_slept`, `agent_tool_reviewed` |
| `workflow_type` | the registered workflow type, else `none` (control events are client-side and do not know it) |
| `activity_type` | the activity's plain name on `activity_executed`, the model activity (`llmChat`, `generate`, `generateResult`) on `agent_model_called`, the activity a tool ran on `agent_tool_called`; else `none` |
| `data_name` | the declared event name on `data_sent` (bounded to 64 distinct series; framework control signals such as `__wf_suspend` are not data events and are not counted) and on `agent_event_received`; else `none` |
| `task_kind`, `task_name` | the task's kind and declared name — on `task_decided`, on the `started`/`closed` events of human-task and review-activity child workflows, whose lifecycle doubles as the task's (created, decided-and-closed, time to decision), on `agent_task_awaited` (`HUMAN_TASK`) and `agent_tool_reviewed` (`REVIEW_ACTIVITY`); else `none` |
| `tool_name` | the tool the model called, by its advertised name, on `agent_tool_called`, `agent_task_awaited` and `agent_tool_reviewed`; else `none` |
| `action` | what was decided, on `task_decided` and `agent_tool_reviewed`; how a sleep ended (`completed`, `interrupted`) on `agent_slept`; else `none` |
| `outcome` | `success`, `failure` |
| `error_type` | the failure's application error type (else its class name); `none` on success. An event wait that ran out is `TIMEOUT`, one that hit the agent's `maxEventWaits` cap is `MAX_EVENT_WAITS`; a control operation on an unknown instance is `WorkflowNotFound` |

`started` and `closed` are recorded by the workflow adapter, replay-gated, at the one
place every start path converges — a `workflow:run`, a management start, a child workflow,
a human task, an agent — so each run counts exactly once. `activity_executed` counts each
real attempt (attempts are never replayed). `data_sent`, `task_decided` and the four control
events (`management:suspendWorkflow`/`resumeWorkflow`/`terminateWorkflow`/`cancelWorkflow`,
accepted or refused) are client-side.

**Durable agent steps.** An agent's loop runs inside its workflow body: the model call,
the tool dispatch, the human task it creates, the event it waits for, the sleep it takes,
the review a gated tool goes through. Each is recorded from the workflow thread when the
step completes — under the same replay gate as `closed`, so a step counts once however
many times the history is replayed after a restart — with its duration on the engine's
deterministic clock (so a wait that spans a worker restart is still measured end to end):

| Step | `event` | Distinguished by |
|---|---|---|
| Thinking — a built-in model activity finished | `agent_model_called` | `activity_type` = `llmChat` \| `generate` \| `generateResult` |
| A tool the model called finished | `agent_tool_called` | `tool_name` (advertised name), `activity_type` (the activity it ran, or `executeAgentTool` for an AI tool) |
| A human task the agent created was decided | `agent_task_awaited` | `task_kind = HUMAN_TASK`, `task_name`, `tool_name`; `error_type` = `HUMANTASK_REJECTED` \| `HUMANTASK_TIMEOUT` |
| An event wait ended | `agent_event_received` | `data_name`; `error_type` = `TIMEOUT` \| `MAX_EVENT_WAITS` on failure |
| The built-in sleep tool returned | `agent_slept` | `action` = `completed` \| `interrupted` (woken by `management:wakeAgent`) |
| A person decided on a gated tool call | `agent_tool_reviewed` | `task_kind = REVIEW_ACTIVITY`, `task_name`, `tool_name`, `action` = the decision |

The model activities also appear as ordinary `activity_executed` attempts (wall clock, per
attempt, worker-side) — the agent step is the loop's view of the same call: one per
iteration, including the automatic retries, with the failure the model was told about.
Token usage is not on these metrics: the workflow module drives `ai:ModelProvider->chat`
through the `llmChat` activity, and `ballerina/ai`'s own `ai.observe` spans record input
and output token counts from inside that call when tracing is on.

Logical metrics are derived, never published as separate names:

| Logical metric | PromQL derivation |
|---|---|
| Runs started | `workflow_events_total{event="started"}` |
| Runs completed / failed | `workflow_events_total{event="closed", outcome=…}` |
| Activity attempts | `workflow_events_total{event="activity_executed"}` |
| Data events delivered | `workflow_events_total{event="data_sent"}` |
| Task decisions (accepted / refused) | `workflow_events_total{event="task_decided", outcome=…}` |
| Human tasks created | `workflow_events_total{event="started", task_kind="HUMAN_TASK"}` |
| Human tasks decided, by outcome | `workflow_events_total{event="closed", task_kind="HUMAN_TASK", outcome=…}` — rejections close with `error_type="HUMANTASK_REJECTED"`, expiries with `error_type="HUMANTASK_TIMEOUT"` |
| Time to decision | `workflow_duration_seconds{task_kind="HUMAN_TASK", task_name=…}` |
| Review activities created / decided | the same three, with `task_kind="REVIEW_ACTIVITY"` |
| Runs suspended / resumed / terminated / cancelled | `workflow_events_total{event="suspended"}` etc., `outcome=…` |
| Agent model calls ("thinking") | `workflow_events_total{event="agent_model_called", workflow_type=…}` |
| Agent tool calls, by tool | `workflow_events_total{event="agent_tool_called", tool_name=…, outcome=…}` |
| Human tasks an agent created | `workflow_events_total{event="started", task_kind="HUMAN_TASK", task_name=~"<agent>\\..*"}` — and, once decided, `agent_task_awaited` with the outcome |
| Events an agent received / waits that timed out | `workflow_events_total{event="agent_event_received", data_name=…, outcome=…}` (`error_type="TIMEOUT"`) |
| Agent sleeps | `workflow_events_total{event="agent_slept", action=…}` |
| Human-task creation to completion, per agent | `workflow_agent_step_duration_seconds{event="agent_task_awaited", task_name=…}` |

**Durations** stay their own summaries, as the standard keeps `file_databinding_duration`:
`workflow_duration_seconds` (run start to close, on the engine's deterministic clock),
`workflow_activity_duration_seconds` (wall clock per attempt) and
`workflow_agent_step_duration_seconds` (one agent step, engine clock; tagged with the
step's `event`, `activity_type`, `tool_name`, `data_name` and `task_name`), each tagged with
the identity tags, the type dimension, and `outcome` — `workflow_duration_seconds` also
carries `task_kind`/`task_name`, so a human task's summary is its time-to-decision —
publishing p50/p75/p90/p95/p99 over a five-minute sliding window.

Tag cardinality is bounded by construction: workflow types, activity types, declared event
names, tool names and task names are compile-time sets, `error_type` is a closed set of
failure types, and instance IDs never appear on metrics — they live on spans, samples and
audit entries.

### Governance: every decision a person makes is recorded

A human task or a review activity is where a person enters the workflow, and governance
asks four things of that moment: who acted, in which capacity, what they decided, and
when. The module answers them at the one place every path converges — the runtime natives
behind `completeHumanTask`, `failHumanTask` and `completeReviewActivity`, whether reached
through the root module, `workflow.management`, the REST service or `executeCommand`.

On a decision the native validates the task and, instead of returning nothing, hands back
a **receipt**: the task's declared name, the workflow that created it, and the roles it
allowed to decide it, all read from the memo it already fetched to validate. The Ballerina
wrapper turns the call into one `TaskDecisionSpan`, which on close writes three things:

- an **audit entry** through `ballerina/log` — `taskKind`, `taskId`, `taskName`,
  `parentWorkflowId`, `action`, `outcome`, `userId`, `userRoles` (as presented by the
  caller), `identitySource`, `assignedRoles` (as declared on the task), `decidedAt`.
  Written at `INFO` for an accepted decision and `WARN` for a refused one, with the
  refusal's error attached;
- the span above, so the decision sits in the caller's request trace;
- one increment of `workflow_events_total{event="task_decided"}`.

A **refused** decision — wrong role, task no longer running, task not found — is recorded
too: `denied` on the audit entry, `outcome = failure` with the refusing `error_type` on the
metric. An audit trail that only shows what succeeded is half a trail.
A refused decision never resolved the task, so its entry carries what the caller presented
and no task name or input.

**Identity provenance.** The audit entry and the span record where the deciding identity
came from, as `identitySource` / `user.identity.source`: `verified` when it was resolved
from a credential the receiving service's auth layer validated — the REST gateway sets it
for a user ID read from a validated JWT's claims or a basic-auth username — and `asserted`
when the application supplied it through the embedded API, or a trusted gateway forwarded
it in `x-user-*` headers. The label is provenance for the audit trail, not authorization:
role checks run the same either way. It travels on `management:Identity.identitySource` through
`executeCommand`, so any platform embedding the command API can mark its own verified
identities; direct calls to the public functions record `asserted`.

The audit entry goes through `ballerina/log` deliberately, not the worker's Java log: it is
the governance record, so it must land where the application's logs land, in the format
and at the level `[ballerina.log]` configures, and it is written whether or not tracing or
metrics are enabled. Logs are the right primary carrier for an audit event: traces are
sampled and metrics are aggregates, and neither may drop or merge a decision.

### Content capture: on by default, switchable off

The records above name the task; two switches under `[ballerina.workflow.observe]`, both
`true` by default, decide whether they also carry its content. On by default because the
engine already persists every value a workflow handles in the run's history: the log and
the span carry nothing the workflow store does not, and telemetry retention retires the
copy. Where a deployment must keep that content inside the workflow store, it turns the
switch off:

| Switch | What it adds | Where |
|---|---|---|
| `captureHumanTaskContent` | what the person was shown — the human task's input, or the arguments of the activity under review — and what they submitted — the completion result, the rejection reason and details, or the review decision's input and feedback — as JSON | `workflow.task.input` and `workflow.task.content` on the decision span; `taskInput` and `content` on its audit entry |
| `captureActivityContent` | every activity execution attempt's arguments and result (or error), as JSON | one `INFO` line per attempt in the worker's module log, beside the attempt's outcome and duration |

Values longer than 8192 characters are cut. The shown input comes from the task's memo
(`taskInput` for a human task, `activityArgs` for a review), which the decision's validation
already fetched — so recording it costs no extra call. The activity line is a worker-side
Java log — the stream the module's activity-failure warnings already use — because activity
threads are outside any Ballerina strand.

On by default follows `ai.observe`, which records prompt and completion content on its spans
with no switch at all, and the reasoning is the workflow store's own: the engine already
persists everything a workflow handles for the life of the run, and telemetry retention
retires its copy on its own schedule, so the copy adds exposure only where the content
should never have entered the workflow in the first place. The module's standing advice
still applies — keep sensitive data out of inputs, arguments and results altogether — and
where that cannot hold, a deployment switches the relevant capture off, once, in
configuration. (OpenTelemetry's GenAI conventions make the opposite call, opt-in, for
message content; this module sides with its in-house precedent.)

### Samples for log-based metrics

Platforms such as the Integration Control Plane build their metrics from log records rather
than from a scrape: `ballerinax/metrics.logs` publishes one record per HTTP request
(`logger = "metrics"`, the request's fields as keys) and a log pipeline indexes them. The
registry counters above never reach such a platform. So the module publishes its own samples,
in the same shape, under `logger = "workflow-metrics"` with a `sample` name:

| `sample` | Fields | Written from |
|---|---|---|
| `workflow.started` | `workflow_type`, `workflow_id`, `run_id` | the workflow adapter on the run's first execution, replay-gated — so a management start, a child workflow, a human task and an agent run count like a `run` |
| `workflow.closed` | `workflow_type`, `workflow_id`, `run_id`, `outcome` (`success`/`failure`), `duration_seconds` | the workflow adapter, replay-gated |
| `activity.executed` | `activity_type`, `workflow_id`, `run_id`, `attempt`, `outcome`, `duration_seconds` | the activity adapter, per attempt |
| `data.sent` | `data_name`, `workflow_id` | the client, on `sendData` |
| `task.decided` | `task_kind`, `task_name`, `action`, `outcome` | beside the decision's audit entry |
| `workflow.suspended`, `workflow.resumed`, `workflow.terminated`, `workflow.cancelled` | `workflow_id`, `outcome` | the client, on the management control operations |
| `agent.model_called`, `agent.tool_called`, `agent.task_awaited`, `agent.event_received`, `agent.slept`, `agent.tool_reviewed` | `workflow_type`, `workflow_id`, `run_id`, `activity_type`, `tool_name`, `data_name`, `task_kind`, `task_name`, `action`, `outcome`, `error_type`, `duration_seconds` — the registry tags of the matching `agent_*` event, `none` where one does not apply | the agent loop on the workflow thread, replay-gated |

Samples use the same `outcome = success|failure` vocabulary as the registry metrics.

Structural fields only — never inputs, results or who decided; those stay on the audit entry
and the content log. The Java-side samples go through the module's console handler, whose
formatter renders a record's `Map` parameter as top-level `key=value` pairs, so a logfmt parser
reads them exactly as it reads `ballerina/log` output. `publishMetricSamples = false` under
`[ballerina.workflow.observe]` turns them off. What a platform does with them — a
`ballerina-workflow-metrics-*` index and a workflow view — is the platform's side; the
field names above are the contract.

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

No workflow-module configuration is required for spans, metrics or the decision audit
entries; the standard Ballerina observability switches control the first two, and the audit
entries follow `[ballerina.log]`. The two content switches are on unless turned off:

```toml
# Config.toml
[ballerina.workflow.observe]
captureHumanTaskContent = false   # keep what was shown and submitted off the span and audit entry
captureActivityContent = false    # keep activity arguments and results out of the worker log
```

## Known artifact

With `observabilityIncluded = true`, the Ballerina runtime auto-instruments every remote
method call — including `ctx->callActivity` and the other `Context` remote calls — from
inside workflow bodies. Those auto-spans predate this proposal, start their own traces
(no client-side parent), and can repeat under replay. They are emitted by the runtime,
not by this module; suppressing them would require engine-side gating of the runtime
observation hooks and is left for a future iteration.

## Testing

- **Unit tests** (`ballerina/tests/observe_test.bal`): the module test run is
  observability-enabled (`--observability-included`, Prometheus reporter, mock tracer),
  so the whole IN_MEMORY suite executes the real recording paths, and the observe tests
  assert span tags (identity tags included), the `workflow_events_total` surface driven
  end-to-end by an agent turn, decision audits across input shapes, content bounding, and
  `workflowTypeNameOf`. The disabled no-op paths stay covered by the integration
  auth-variant runs, whose regenerated config has no `[ballerina.observe]` section.
- **Integration tests** (`integration-tests/tests/observability_test.bal`): the
  integration package builds with `observabilityIncluded = true` and runs with metrics
  enabled (Prometheus reporter) and the distribution's mock tracer against a real engine
  dev server. They assert the `workflow_events_total` events (with the identity tags, the
  uniform label set and its `none` sentinels) and the duration summaries for successful
  and failed runs (`testWorkflowMetricsEmission`, `testWorkflowFailureMetricsEmission`)
  and the `start_workflow`/`send_data`/`get_workflow_result` spans tagged with the
  instance ID (`testWorkflowSpanEmission`). `testHumanTaskDecisionTelemetry` refuses a
  decision from the wrong role and then accepts one, and asserts both are counted
  (`outcome = "failure"` with the refusing `error_type` and `task_name = "none"`, and
  `outcome = "success"`) and both leave a span naming the decider, their
  roles and the action — with the task's input and the submitted result on the span exactly
  when `captureHumanTaskContent` is on. `testReviewActivityDecisionTelemetry` does the same
  for a `proceed-with-input` review decision, including the reviewed activity's arguments.
  `testWorkflowControlMetricsEmission` suspends, resumes and terminates runs and refuses a
  suspend on an unknown instance, asserting the four control events. `testDurableAgentStepMetrics`
  runs a scripted agent through a sleep, an event wait that times out, an activity tool, a
  human task and the model calls between them, and asserts every `agent_*` event with its
  dimensions, the task child's `started` under the same task name, and the step duration
  summaries (the sleep's covers the second it slept).
- The unit run also pins the agent-step vocabulary — event value, derived sample name,
  outcome from error type, `none` sentinels — through `describeAgentSteps`, and drives the
  control and agent-step recorders through their registry seams.
  The integration config leaves both content switches at their default, on, so the capture
  paths run under the whole suite. The full pre-existing
  integration suite also runs with observability enabled, so it doubles as a regression
  check that instrumentation never disturbs execution.
- With observability off (the default for all existing users), every new code path
  reduces to a flag check.
