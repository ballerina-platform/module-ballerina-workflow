# Changelog

This file contains all the notable changes done to the Ballerina Workflow package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **The descriptor now carries each workflow's graph, and executions say where they are in it.**
  A workflow that calls the same activity from both arms of an `if` produced two invocations that
  history could not tell apart, which is what stopped the control plane from drawing the workflow
  and highlighting the path a run actually took.

  `workflow.def.json` gains a `graph` per workflow (still `descriptorVersion` 1.0 — the
  descriptor has never shipped, so its first release simply includes the graph): its durable steps —
  activities, human tasks, child workflows, event waits, sleeps — in source order, nested under
  the `BRANCH`/`LOOP`/`TRY` constructs that guard them, with edges that follow control flow.
  Lexical `line`/`column` travel alongside for display but are deliberately not part of a step's
  identity, so reformatting and unrelated edits do not move it.

  Durable agents get a `graph` too, in the same shape: an agent has no lexical control flow — the
  model decides what runs — so it is a star, with data events and human tasks inbound and tools
  and the model outbound.

- **Steps can be named: `stepId`.** Every step in the graph carries an id. Name it with the new
  `stepId` parameter on `callActivity` and `awaitHumanTask` — `stepId = "charge-card"` — or leave it
  out and the compiler generates `<target>#<ordinal>` from the occurrences of that target in source
  order. Naming is worth doing for the steps you care about: a generated ordinal shifts when a call
  to the same target is added earlier in the workflow, so an in-flight execution then points at the
  wrong node, while a chosen id does not move at all.

  A chosen id must be a constant string — the graph is written at build time, so an expression
  evaluated per execution cannot be described, and that is an error. Sharing an id with another step
  is only a warning: the later step is described with a numeric suffix (`book`, then `book#2`) and
  that same id is written back to the call, so the graph and the execution still agree. There are no
  reserved characters — generation steps over any id a call chose, so a step may be called `order#1`
  if that reads best.

  The id travels in the call config the activity already carries, and `ActivityTreeNode.stepId` and
  `GraphNode.metadata.stepId` report it back — that join is what lets a viewer highlight the path a
  run actually took. Where the server records user metadata it is also the activity's summary in the
  Temporal UI. Activity *inputs* are not compared during replay, so in-flight executions are
  unaffected; an execution started before this release reports `()`, and a step renamed since a run
  started reports the old id — so treat the join as optional and draw an unmatched node
  unhighlighted rather than failing.

- **`management:ErrorCode` and `management:errorCodeOf(Error)`** — the machine-readable,
  protocol-independent reason a management operation failed (`NOT_FOUND`, `ACCESS_DENIED`,
  `INVALID_REQUEST`, `CONFLICT`, `INVALID_PAYLOAD`, `EXECUTION_ERROR`). Consumers that carry
  errors across a boundary — the HTTP API in `workflow.management.rest`, the ICP bridge's
  generated command-tunnel glue — branch on the reason instead of `is`-checking this module's
  error subtypes, and each adapter owns mapping the reason to its wire vocabulary (the
  management module itself names no status codes). A new error subtype now surfaces as a
  classified reason everywhere, instead of silently degrading in hand-copied mappings.

- **A run can be restarted from a chosen point.** Recovering a run meant terminating it and
  starting a new one, which loses the work that already succeeded — including the steps that
  charged a card or reserved stock — and gives the new instance a different ID, so the audit
  trail forks.

  Two operations expose Temporal's reset through the management API. `instances.resetPoints`
  (`GET /workflow/workflows/{workflowId}/reset-points`, and a `{runId}` variant) lists the events a
  run can be restarted from; `instances.reset` (`POST .../reset`) restarts it there, preserving history
  up to that point and re-executing everything after it as a **new run of the same workflow ID**.

  `resetType` chooses the point: `"first-workflow-task"` runs the whole workflow again with the
  input it started with, `"workflow-task-id"` (with `eventId`) starts from a selected step, and
  `"last-workflow-task"` moves a run wedged on a failing workflow task onto fixed code.

  **A reset point is a workflow task, not an activity.** Steps scheduled by one task always come
  back together, and everything after the point re-runs — including the error handling and
  compensation the workflow already performed. Each point therefore reports the steps it
  schedules (`nodeIds`/`nodeNames`, joinable to the activity tree) so a caller can see what a
  choice re-runs before making it, and the point that re-runs the first failed step is flagged
  `isFirstFailure`. A target that is not one of the run's points is refused with the eligible
  event IDs rather than passed to the runtime, whose error for this does not say what is valid.

  `reapply` controls what is re-delivered to the new run: `{"type": "signal"}` (the engine
  default) re-delivers signals, `"none"` nothing, and `"all-eligible"` also updates — which
  matters for durable agents, whose turns arrive as updates and are therefore *not* re-delivered
  under the default. `exclude` withholds individual categories. A `reapply` that is malformed, or
  names a category that does not exist, is reported rather than ignored: dropping it would
  re-deliver exactly what the caller asked to withhold. Both operations require caller roles, as
  the history reads they are built on do. Replay runs against the worker's current code, so a
  workflow function that changed since the run started can fail to replay.

- **Failed activities can be retried or failed in bulk.** A workflow that fails several
  activities under a human-review retry policy raised one review task per failure, and an
  operator had to decide each one individually — the same decision, repeated, with no way to
  clear an inbox after a downstream system came back.

  The new `reviewActivities.bulkRetry` operation applies one decision to many failure reviews:
  `POST /workflow/review-activities/bulk-retry` with `{"action": "retry"}` to rerun the
  activities with their original arguments, or `{"action": "fail"}` to surface the original
  failures to the workflows. Tasks are named either explicitly with `taskIds`, or by
  `parentWorkflowId` for every pending failure review of one workflow — optionally narrowed to
  one activity with `activityName`.

  **The decision cannot change the payload.** There is no field for replacement arguments
  anywhere in the request, so editing what an activity is retried with stays a single-task
  decision (`proceed-with-input`), where the reviewer sees the activity they are editing. For
  the same reason, a `parentWorkflowId` selection covers only failure reviews: approval gates
  (`PRE_RUN`) are a different decision and are never bulk-approved.

  A bulk decision races other operators by nature, so it reports per-task outcomes instead of
  failing as a whole — `APPLIED`, `SKIPPED` (already decided, or not a failure review), or
  `FAILED` (unknown task, or one the caller may not decide) — with counts and the deciding
  user. The response is `200` whenever the batch was accepted, including when some tasks were
  skipped or failed; only a malformed selection is `400`. Re-issuing the same batch is
  therefore safe: tasks decided in the meantime come back `SKIPPED`.

  `maxBulkRetrySize` (default `100`, under `[ballerina.workflow.management]`) caps one batch. A
  selection that resolves to more is rejected rather than truncated, so a caller is never told a
  decision was applied to a larger set than it was.

### Changed

- **Activities are scheduled under their plain name.** An activity's Temporal type was
  `<workflowType>.<activity>`, which added nothing — Ballerina function names are already unique
  within a package — while making one registry entry per (workflow, activity) pair and a longer
  type in every history. It is now just the activity name, and a review task is listed as
  `<workflow>.<activity>` without the runtime's internal `workflow-` prefix.

  An activity's type *is* compared during replay, so the change is gated per execution with
  `Workflow.getVersion`: an execution started before this release keeps scheduling the qualified
  name — which stays registered — and only new executions use the plain one. No instance needs
  draining, at the cost of one marker event per execution that calls an activity.

  The runtime metadata document is unchanged: it still reports one activity per workflow, from an
  ownership map rather than by splitting the registry key.
### Fixed

- **`workflow.def.json` now reaches the built executable.** The descriptor was registered as a
  package resource with `SourceGeneratorContext.addResourceFile`, which puts nothing into the
  executable, the BALA, or `target` — while a file physically present in a package's `resources`
  directory is packed into both. It is now written into the emitted executable by a
  compiler-lifecycle task, as the root-level entry `workflow.def.json`, byte-identical to the
  document the builder produced. Executables only: a BALA does not carry it, and a consumer that
  needs the artifact regenerates it from the package. Nothing about registration changes — the
  runtime registers from the copy the source modifier embeds.

- **A rejected human task now reaches the awaiting workflow.** `awaitHumanTask` declared
  `T|HumanTaskTimeoutError`, so a timeout was the only failure it could return. Every
  other outcome — a task rejected through the `fail` management operation, a terminated
  task, a submitted value that did not match `T` — built an error outside that union and
  **panicked the workflow with `{ballerina}TypeCastError`** at the Java→Ballerina
  boundary. The rejection reason travelled correctly as far as the task workflow's
  failure (recorded in its history) and was then lost, so a workflow could not
  compensate on it and the instance failed with an opaque cast error instead.

  `awaitHumanTask` now returns `T|HumanTaskError`, a union of three distinct errors:

  - `HumanTaskTimeoutError` — unchanged, including its detail record;
  - `HumanTaskRejectedError` — new, carrying `reason`, the structured `details`, and
    `rejectedBy` exactly as submitted to the `fail` operation;
  - `HumanTaskFailedError` — new; the task produced no result (terminated, or the
    submitted value did not match `T`).

  **Breaking (source):** the documented `on fail workflow:HumanTaskTimeoutError e`
  pattern no longer compiles, because the `do` block can now fail with a wider type.
  Catch the family and narrow inside:

  ```ballerina
  do {
      decision = check ctx->awaitHumanTask("approve", "FINANCE", timeout = {hours: 24});
  } on fail workflow:HumanTaskError e {
      if e !is workflow:HumanTaskTimeoutError {
          return e;                       // a rejection is an answer, not an escalation
      }
      // ... escalate on timeout, as before
  }
  ```

  A rejection recorded by an older runtime carries no structured details; its
  `HumanTaskRejectedError` reports the reason with `details` as `()`.

### Changed

- **Breaking**: the management HTTP API moved from `ballerina/workflow.management`
  to the new `ballerina/workflow.management.rest` module. `workflow.management` is
  now a pure Ballerina API — importing it never starts a listener. To serve the
  HTTP API, add `import ballerina/workflow.management.rest as _;`.

  Configurables split between the two modules, so a migration moves *most* keys but
  must leave two behind. `maxPageSize` and `reviewActivityAccessRole` belong to the
  operations themselves and stay under `[ballerina.workflow.management]`; everything
  else (listener, auth, CORS, identity) moves to `[ballerina.workflow.management.rest]`
  with unchanged key names. Moving `reviewActivityAccessRole` by mistake is not
  inert — it would fall back to its default, which leaves review activities that
  declare no roles of their own visible to any caller:

  ```toml
  [ballerina.workflow.management]
  maxPageSize = 100
  reviewActivityAccessRole = "OPS"          # keep here, or the restriction is lost

  [ballerina.workflow.management.rest]
  port = 8234                                # everything else moves here
  enableJwtAuth = true
  ```

  Routes, methods, base path, default port, success status codes, and success payload
  shapes are unchanged. Identity resolution is the one behavioral difference: it now
  comes from token claims and overrides `x-user-*` headers
  (`trustForwardedIdentity = true` restores the old precedence).

- **Breaking (generated API specification)**: the specification generated from the
  management service's source is now less detailed than the API it describes. The
  resources return `http:Response` rather than typed union returns, so
  `bal openapi -i` (and anything reading its output) no longer sees:

  - the per-route status codes — every operation is described with a single
    unconstrained response instead of its `200`/`201`/`400`/`403`/`404`/`409`/`500` set;
  - the response body schemas — `WorkflowInstancePage`, `HumanTaskInfo`,
    `ErrorResponse`, and the rest no longer appear as response types (they remain
    public Ballerina types and the wire payloads are byte-for-byte unchanged);
  - the `x-user-*` header parameters, which were declared as resource parameters and
    are now read from the request inside the service.

  The **served** contract is unchanged — no route, method, status code, or payload
  moved, so a running client keeps working. What breaks is anything *derived* from the
  generated specification: regenerated client stubs lose their typed responses and
  header parameters, and a gateway, mock server, or contract test built from that
  document degrades to an untyped passthrough. Contract tests that assert the generated
  specification matches a stored copy will fail on regeneration and must be re-baselined.

  Migration, if you depend on the specification: pin the last specification generated
  before this release and maintain it by hand, or generate it from a running endpoint
  rather than from the source. The response types are still public, so a hand-maintained
  document can reference them directly. Typed resource signatures may be restored in a
  later release once error mapping no longer needs the raw `http:Response`; that would
  restore the detail without changing the served API again.

  The HTTP-only types `CompletionInfo`, `ReviewDecisionInfo`, `HumanTaskPage`, and
  `ReviewActivityPage` moved with the service; the unused `ManagementServiceConfig`
  and `CorsConfig` types were removed.

### Added

- **Descriptor-driven registration (zero metadata codegen)**: the compiler plugin no
  longer generates per-workflow `registerWorkflow`/`registerHumanTask` calls. The
  generated module-init function hands the canonical descriptor document to the
  runtime in a single data-only `registerWorkflowDescriptor` call; when the worker
  starts, the runtime registers every described workflow, activity, and human task
  and resolves the implementation functions by their recorded module coordinates
  (symbol references invoked through `Runtime.callFunction` with a concurrent-safe
  strand). Direct pointer registration remains supported for durable-agent runners
  and programmatic use. Only runtime values stay generated: module-level client
  connections and durable-agent declarations.
- **Workflow Definition Descriptor (WDD)**: the compiler plugin now generates a
  versioned, OpenAPI-style definition file describing every workflow component in
  the package — workflows, activities (input and output), human tasks (completion
  forms), review activities, events, and durable agents — with embedded JSON
  Schemas, and packs it into the executable JAR as the fixed-name resource
  `workflow.def.json`. Every schema-bearing position is a typed slot: the resolved
  Ballerina type is always recorded, and the JSON Schema is emitted per
  representability tier (exact for closed anydata shapes, permissive + `lossy` for
  open anydata, omitted for `xml`/`error`/behavioral types). The document is
  canonical JSON with a SHA-256 content checksum, so consumers can persist, diff,
  and audit definitions over time. The packed descriptor is served under the
  `descriptor` field of `management:getWorkflowMetadata()` (nil when the program
  was built without one, e.g. under `bal test`). The meta-schema lives in
  `docs/spec/`.
- `management:getWorkflowMetadata()` — a startup-complete metadata document
  (definitions with input schemas, human tasks with completion-form schemas,
  activities with input schemas, the review-action vocabulary, and durable-agent
  declarations) for control planes to publish. Completion-form schemas are read
  from the packed workflow descriptor before a task first runs; the registry
  takes over once the task has executed.
- `management:executeCommand()` — runs any management operation named by the
  `management:Operation` enum, returning the operation's `json` payload or a
  `management:Error`. The HTTP API in `workflow.management.rest` dispatches through
  the same call, so a command and the matching REST route produce identical
  payloads. `workflow.management` itself stays free of transport concepts: it
  reports *why* an operation failed through distinct error types (`NotFoundError`,
  `AccessDeniedError`, `InvalidRequestError`, `ConflictError`, `InvalidPayloadError`,
  `ExecutionError`), and each adapter maps those onto its own protocol — status
  codes in the REST module, and the tunnel envelope in a control-plane bridge.
- Token-based caller identity for the management REST API: the gateway interceptor
  resolves the caller's identity once per request and stores it in the
  `http:RequestContext` (spec §8.1.11), where every resource reads it — requests are
  never mutated. With JWT/OAuth2 auth, the user ID and roles are extracted from the
  bearer token's claims (configurable `userIdClaim`, `rolesClaim` with dotted-path
  support) and replace any forwarded `x-user-id`/`x-user-roles` headers so identity
  cannot be spoofed alongside a valid token (`trustForwardedIdentity = true` restores
  header precedence). Optional OAuth scope enforcement per operation class via
  `enforceScopes` and the `scopeWorkflowView/Manage`, `scopeHumanTaskView/Manage`
  configurables. Basic auth defaults the audit user ID to the authenticated username.
- Declared agent activities honor `bindings`: arguments fixed at registration (typically a
  client the model cannot supply) are carried from the declaration through to the
  registration, so a connection-based activity can be exposed as an agent tool by binding
  its client to a module-level variable.
- Compile-time guards for durable agent declarations: a tool that declares an
  `@ai:AgentTool` authorization requirement is rejected (durable agents do not run the
  `ai:Agent` loop that acquires tokens and validates scopes), capability names must be
  constant strings (the name drives both the designer rendering and the Temporal
  registration), and an activity is rejected when a parameter the model cannot supply
  is left without a `bindings` entry.

### Fixed

- Duplicate capability names in a durable agent now fail at startup. Activities, tools,
  events, human tasks, and peers share one namespace per agent — the name is the tool the
  model calls, and for a human task also the Temporal workflow type — but a second
  registration used to replace the first silently. Registration now rejects a name that is
  already claimed, both at module init (where the declaration registers) and on the agent
  context (where names the compiler plugin cannot see are registered), so the conflict
  surfaces even where the WORKFLOW_150 compile-time check cannot reach.

## [0.8.1] - 2026-08-03

### Added

- Durable agents can declare a `resultType`: as the reasoning loop concludes, one more
  durable model call converts the outcome into that type, and `getResult`/`waitForResult`
  return the typed value instead of the final text.
- Durable sleep for agents: agents can pause on a workflow-side timer, and a sleeping
  agent can be woken early through the management API (`POST /workflows/{id}/wake`),
  which cancels the pending sleep.
- Management API task-queue scoping for namespaces shared by multiple integrations
  (a project): human-task, review-activity, and workflow-instance listings (and the
  pending count) accept an optional `taskQueue` filter, every list/detail result
  carries `namespace` and `taskQueue` identifying its owning integration, and task
  mutations (complete/fail/decide) are rejected with 403 when the task is served by
  a different integration's task queue.
- `ToolDecl` gating is honored end to end for durable agent tools: the compiler plugin
  forwards `{tool: x, requiresApproval: true, userRoles: ...}` entries to the registration,
  every tool shape (`@ai:AgentTool` function, `ai:ToolConfig`, `ai:BaseToolKit`) is accepted
  on the declaration, and AI-tool approval reviews use the declared reviewer roles.

### Changed

- Timeout fields across the module (`sleep`, human tasks, approval config, event timeouts)
  now use a module-owned `workflow:Duration` record, structurally identical to
  `time:Duration` (existing values remain assignable).
- The module builds against the latest `ballerina/ai` and `ballerina/mcp` releases.

### Fixed

- Reading a durable agent's result no longer reports the instance as permanently busy:
  the read checks the instance status instead of relying on a short result timeout.

## [0.8.0] - 2026-07-24

### Added

- Data-event waits are now visible: a workflow blocked on `wait dataEvents.<name>`
  publishes the awaited event names to the execution memo (`wfWaitingEvents`),
  which lands in the event history and is readable from a describe call. The
  activity tree and execution graph render such waits as `DATA` nodes with
  status `WAITING`, completing them in place when the data arrives — so diagrams
  can show exactly where a halted workflow is waiting.

### Removed

- The deprecated retry-task management surface: `management:completeRetryTask`,
  `listPendingRetryTasks`, `listAllRetryTasks`, `getRetryTaskInfo`, the
  `RetryDecision`/`RetryTaskSummary`/`RetryTaskInfo`/`RetryTaskPage`/`RetryDecisionInfo`
  types, and the `/workflow/retry-tasks/...` HTTP routes. Use the review-activity
  equivalents (`completeReviewActivity`, `listPendingReviewActivities`,
  `listAllReviewActivities`, `getReviewActivityInfo`, `ReviewDecision`,
  `ReviewActivity*` types, and `/workflow/review-activities/...`).

### Changed

- Review-activity child workflows now use per-activity Temporal workflow types
  (`reviewactivity-<workflowDefinition.activityName>`), mirroring the human-task
  child types; the legacy shared `retrytask` type remains dispatchable for
  pre-rename persisted executions.

### Added

- Added **durable AI agents** (`workflow:DurableAgent`): an LLM agent declared once as
  a module-level `final` **object** whose constructor config carries every capability —
  `activities` (`@workflow:Activity` functions, gated/retried via `ActivityDecl`),
  `tools` (`@ai:AgentTool` functions and toolkits), `events` (named two-way channels
  with request/response types and per-channel `SINGLE_EVENT`/`MULTI_EVENT`
  cardinality), `humanTasks`, and `peers` (other durable agents advertised to the
  model as delegable tools). The agent runs as a Temporal-backed workflow, so its
  reasoning loop, tool calls, and multi-turn conversations are journaled and survive
  worker crashes and restarts. The compiler plugin generates the registration at
  module init from the declaration (`WORKFLOW_149` enforces module-level `final`;
  `WORKFLOW_150` enforces one flat capability namespace) and bans direct AI
  model/agent calls inside workflow bodies (`WORKFLOW_148`).
- Durable agent drivers: `agent.run(query, input)` starts an instance durably and
  always returns the instance ID (a top-level start from services; a **true Temporal
  child workflow** from inside a `@workflow:Workflow`, so sub-agents' lifecycles are
  tied to the caller). Non-blocking reads (`getResult`/`getDataResult`) return the
  value or a `workflow:AgentBusyError` while the agent is still working; blocking
  reads (`waitForResult`/`waitForDataResult`) suspend durably inside workflows and
  are crash-resumable from services. `sendData(instanceId, eventName, data)` sends
  one turn and returns a correlation token — a Temporal Update from services
  (rediscoverable via `getPendingAgentEvents`), a deterministic reply-correlated
  signal from inside workflows. Model-driven peer delegations run the peer agent as
  a child workflow, synchronously or asynchronously with the reply delivered on a
  declared callback event channel; peers honor `requiresApproval` via `PRE_RUN`
  review activities, and manual activity retries surface as `ON_FAILURE` reviews.
- Child workflow composition on the workflow context: `ctx->runChildWorkflow(fn, input)`
  starts a **true Temporal child workflow** (lifecycle tied to the parent — closing the
  parent cancels in-flight children) and returns its instance ID;
  `ctx->getChildWorkflowResult(id)` reads the result without blocking, returning the new
  `workflow:WorkflowBusyError` while the child is still running;
  `ctx->waitForChildWorkflow(id)` durably suspends (crash-resumable, no thread held)
  until the child completes; `ctx->callWorkflow(fn, input)` fuses start + durable wait;
  and `ctx->sendDataToChildWorkflow(id, dataName, data)` signals a running workflow
  instance from inside a workflow via a deterministic external-workflow signal.
- Compile-time validation for the child-workflow methods: `workflow:run` and
  `workflow:sendData` are now rejected inside a workflow body in favour of the context
  methods (`WORKFLOW_138`); the first argument of `runChildWorkflow`/`callWorkflow` must
  be a `@Workflow` function (`WORKFLOW_139`); and the `input` argument is validated
  against the child workflow's declared input type (`WORKFLOW_140`, `WORKFLOW_141`).
  Previously `workflow:run`/`sendData` inside a workflow were routed through implicit
  activities, which started detached top-level workflows with no parent lifecycle.

- Renamed the management "retry task" concept to **review activity**
  ([#8906](https://github.com/ballerina-platform/ballerina-library/issues/8906)): one
  concept for a human reviewing an activity call — after it fails (`ON_FAILURE`, the
  former manual retry) or, in an upcoming release, before it runs (`PRE_RUN`, an
  approval gate). New management functions (`completeReviewActivity`,
  `listPendingReviewActivities`, `listAllReviewActivities`, `getReviewActivityInfo`)
  and HTTP routes (`/workflow/review-activities/...`) with unified decisions
  `proceed` / `proceed-with-input` / `reject` (plus optional reviewer `feedback`).
  The retry-task functions, types, and `/workflow/retry-tasks/...` routes are kept but
  **deprecated**; review activity titles and descriptions now state that the task
  reviews a failed activity. Retry tasks persisted by pre-0.7.0 releases
  (`retrytask-*` IDs, `RETRY_TASK` memo kind) remain visible and completable through
  both the review activity API and the deprecated retry-task API.
- Review activity list and detail routes now apply the same role-based visibility as
  human tasks: activities that declare roles require a matching `x-user-roles` entry.
  Activities without declared roles are visible to any caller by default; the new
  `reviewActivityAccessRole` configurable (default `()`) optionally restricts them —
  and the decision routes — to callers holding the configured role.
- [#8895](https://github.com/ballerina-platform/ballerina-library/issues/8895) -
  `getReviewActivityInfo` (and `GET /workflow/review-activities/{taskId}`) now returns a
  `formSchema` JSON Schema describing the input accepted by the `proceed-with-input`
  decision — one property per data parameter of the reviewed activity — alongside the
  recorded `activityArgs` (for pre-filling) and the activity's `errorMessage`.

- Compile-time validation for `workflow:run()` calls: the first argument must be a
  function with the `@Workflow` annotation (`WORKFLOW_130`), the `input` argument type
  must match the workflow function's declared input parameter type (`WORKFLOW_131`),
  and passing an input to a workflow that declares no input parameter is an error
  (`WORKFLOW_132`).
- Compile-time validation for `workflow:sendData()` calls: the target workflow must
  declare an events record (`WORKFLOW_133`), the `dataName` argument must match a field
  of the workflow's events record when statically resolvable (`WORKFLOW_134`), and the
  `data` argument type must match the event future's inner type (`WORKFLOW_135`).
- [#8835](https://github.com/ballerina-platform/ballerina-library/issues/8835) -
  Compile-time validation of the contextually expected type of `ctx->callActivity(...)`
  calls against the activity function's declared return type (`WORKFLOW_137`). A call
  site that requests a type the activity can never produce (e.g. `int? x = check
  ctx->callActivity(checkPayment, {})` for an activity returning `PaymentRecord?`) is
  now a compile error instead of a runtime conversion failure.

### Changed

- [#8892](https://github.com/ballerina-platform/ballerina-library/issues/8892) - Human
  task and review activity statuses now mirror the underlying task workflow:
  `PENDING` (awaiting a human) | `COMPLETED` (a human submitted a result) |
  `FAILED` (rejected via the fail operation, or timed out before anyone acted) |
  `CANCELED` (retired internally because the parent workflow closed) |
  `TERMINATED` (an admin terminated the task). `TIMED_OUT` is folded into `FAILED`
  (the workflow still receives a `HumanTaskTimeoutError`), the fail operation now fails
  the task instance with the rejection reason (carried in a dedicated signal envelope, so
  completion results that legitimately contain an `__rejected` field are unaffected),
  task child workflows use a request-cancel
  parent-close policy (so parent closure reports `CANCELED`, not `TERMINATED`), and the
  `cancelHumanTask` operation was removed from the management API — cancellation happens
  only internally.

- `workflow:run()` now accepts any `anydata` value as the workflow input (previously
  `map<anydata>?`), matching the workflow function input contract. Primitive inputs
  (`string`, `int`, `boolean`, ...), `json`, `xml`, arrays, and tables are now passed
  through to the workflow instead of being silently dropped.
- `workflow:Context` is now a mandatory first parameter for every `@Workflow` function
  (`WORKFLOW_100`). Direct calls to `@Workflow` functions are rejected at compile time
  (`WORKFLOW_136`); workflows must be started via `workflow:run()`. Together these prevent
  workflow functions from being invoked as normal functions from other modules.
- The management HTTP listener is now registered as a dynamic listener during module
  initialization when `enableManagementApi = true`, so programs that use a `main`
  function (instead of services) keep serving the management API after `main` returns.
  The listener is deregistered on graceful shutdown.

- [#8840](https://github.com/ballerina-platform/ballerina-library/issues/8840) -
  Widened the `ctx->await()` dependent type parameter to
  `typedesc<anydata|error|(anydata|error)[]>` (returning `T`). The result can now be
  destructured directly with a tuple-binding pattern
  (`[Approval, Payment] [a, p] = check ctx->await(...)`), captured as `[T1, T2, ...]|error`
  without a forced `check`, and use per-position error types (`[T1|error, T2|error]`). The
  compiler plugin validates that each tuple position matches the corresponding future's type.
- The human-task completion HTTP endpoint now returns `422 Unprocessable Entity` when the
  submitted payload does not match the task's expected result type.
- Made the `enableManagementApi` configurable public so the management API can be toggled from
  application configuration.

### Fixed

- [#8894](https://github.com/ballerina-platform/ballerina-library/issues/8894) -
  `getReviewActivityInfo` / `GET /workflow/retry-tasks/{taskId}` no longer returns a bogus
  record when given a human task ID — both info endpoints now validate the workflow kind
  and return a not-found error for mismatches (and `getHumanTaskInfo` likewise rejects
  review activity IDs).

- [#8903](https://github.com/ballerina-platform/ballerina-library/issues/8903) - The
  suspend management API now actually suspends the workflow: the workflow stops making
  progress at its next durable operation (activity call, timer, human task, retry task,
  or child workflow) until resumed, and its status is reported as `SUSPENDED` by
  `getWorkflowInfo` and `listWorkflowInstances` (the `RUNNING` filter excludes suspended
  workflows; a `SUSPENDED` filter returns only them).
- [Fix#8820](https://github.com/ballerina-platform/ballerina-library/issues/8820) -
  `workflow:sendData()` now supports all persistable `anydata` payloads — primitive types
  (`boolean`, `int`, `float`, `decimal`, `string`), `json`, `xml`, and `table` — not only
  records. Previously a non-record payload was delivered as an empty `map<anydata>`, causing a
  `{ballerina}ConversionError`. Added the `WORKFLOW_129` compiler diagnostic to enforce that
  each `future<T>` field in a workflow's events record has a `T` that is a subtype of `anydata`.
- Fixed a `TypeCastError` crash when a human task was completed with an empty or
  type-mismatched payload. `completeHumanTask` now validates the payload against the task's
  expected result type before completing it, returning an error (and leaving the task pending)
  instead of failing the workflow ([#8866](https://github.com/ballerina-platform/ballerina-library/issues/8866)).
- Generated JSON schemas no longer list optional record fields (declared with `?`) as
  `required`.


### Fixed

- The management listener is initialized only when the management API is enabled
  (`enableManagementApi`); previously the port was opened unconditionally.
- Starting and listing workflows and durable agents is unified in the management API:
  agents carry `kind: "AGENT"` and a `startInputSchema`, and both start through the
  same endpoint.

## [0.5.0] - 2026-06-18

### Added

- **Management API** — new `ballerina/workflow.management` submodule and HTTP management
  service for operating running workflows. Supports listing workflows, retrieving a specific
  workflow run, fetching run information and execution history, suspending and resuming runs,
  cancelling and terminating workflows, listing pending human tasks and pending retry tasks,
  and generating the input schema for a workflow.
- Human-task user tracking (assigned/candidate roles) and human-task validation in the
  management API.
- CORS configuration options for the management API service, and a configurable management
  API port.
- A management API example and accompanying dashboard.
- **Human tasks** — `ctx->awaitHumanTask(...)` for human-in-the-loop steps, with a
  `HumanRetry` option for retrying pending human tasks, plus compiler-plugin validation and
  test coverage for human-task usage.

### Changed

- Renamed the human-task API to its final form `awaitHumanTask` (previously introduced as
  `callHumanTask`, then `createHumanTask`).
- Improved Temporal log suppression to reduce log flooding and clarify startup logging
  (server URL and namespace tracking).
- Removed the unused `stopWorkflowRuntimeNow` and `getRegisteredWorkflows` functions.

### Fixed

- Fixed a "Failed to list workflows" issue in the management API.
- Fixed escaping of backslashes in workflow IDs and signal payload field handling in the
  native management layer.

- [Diff](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.4.0...release-0.5.0)

## [0.4.0] - 2026-05-21

### Added

- Built-in activities `callSoapAPI` and `sendEmail`, with integration tests.
- Connection-variable analysis in the compiler plugin and additional compiler-plugin test
  cases.
- A collection of end-to-end integration use cases and use-case documentation, including
  clinical message replay and EHR downtime recovery notification scenarios.

### Changed

- Enhanced workflow configuration handling and identifier normalization.
- `sendData()`/signal sending now fails gracefully by returning `false` instead of throwing
  when the target signal cannot be delivered.
- Added `Dependencies.toml` files for the `ballerina` and `integration-tests` packages.

### Fixed

- Fixed `ctx->await()` type validation incorrectly reporting an error for optional future
  types.

- [Diff](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.4...release-0.4.0)

## [0.3.4] - 2026-04-27

### Fixed

- [Fix#8743](https://github.com/ballerina-platform/ballerina-library/issues/8743) -
  `ctx->await()`: improved partial-await validation and diagnostics, including better error
  location reporting for tuple types.
- Improved integer-literal extraction to support constant symbols and decimal, hexadecimal,
  and binary formats.

- [Diff](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.3...release-0.3.4)

## [0.3.3] - 2026-04-08

### Fixed

- [Fix#8737](https://github.com/ballerina-platform/ballerina-library/issues/8737) -
  `ctx->await()`: Fix compile-time type validation and partial-wait runtime semantics for
  scalar types.
- Documentation updates and additional examples.

- [Diff](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.2...release-0.3.3)

## [0.3.2] - 2026-03-26

- Minor Bug Fixes and Improvements. [1](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.1...release-0.3.2)

## [0.3.1] - 2026-03-24

- Minor Bug Fixes and Improvements. [1](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.0...release-0.3.1)

## [0.3.0] - 2026-03-20

### Added

- New `ballerina/workflow.internal` submodule containing internal registration APIs used by the
  compiler plugin. Not intended for direct use by application code.
- Added `ctx.currentTime() returns time:Utc` method to the `Context` client class — returns
  the current workflow time as reported by the workflow engine. This value is deterministic
  across replays and is **not** the same as `time:utcNow()` from `ballerina/time`, which
  reads the OS clock and must not be used inside `@workflow:Workflow` functions.
- Added `WORKFLOW_113` compiler diagnostic (warning) for usage of `time:utcNow()` inside
  `@workflow:Workflow` functions — suggests using `ctx.currentTime()` instead.
- Added support for dependently-typed `@Activity` functions — an activity may declare a
  `typedesc<anydata>` parameter with an inferred default (`<>`) to enable type-safe result
  conversion. The constraint type must be `anydata`. The typedesc parameter is excluded from
  workflow history serialization and reconstructed at runtime by the activity adapter from
  the type information supplied by `callActivity`.
- Added `WORKFLOW_114` compiler diagnostic (error) for `@Activity` functions with unsupported
  typedesc patterns — only the inferred-default form `typedesc<anydata> t = <>` is allowed.
  Explicit defaults (e.g., `= string`) and required typedesc parameters (no default) both
  produce this error.

### Changed

- **[Breaking]** Flattened `WorkflowConfig` from a union of nested records (`LocalConfig`,
  `CloudConfig`, `SelfHostedConfig`, `InMemoryConfig`, `SchedulerConfig`, `AuthConfig`) to a
  flat set of `configurable` variables under `[ballerina.workflow]` in Config.toml.
  This improves the low-code UI experience by removing nested TOML sections.
  Mode-specific validation (e.g., CLOUD requires authentication) is now performed at module
  init time; fields irrelevant to the selected mode are ignored.
- Added `Mode` enum type (`LOCAL`, `CLOUD`, `SELF_HOSTED`, `IN_MEMORY`) for the `mode`
  configurable variable, replacing the previous plain string.
- Auth fields (`authApiKey`, `authMtlsCert`, `authMtlsKey`) now use `string?` optional type
  instead of empty string defaults.
- Activity retry fields use descriptive `activityRetry*` prefixes
  (`activityRetryInitialInterval`, `activityRetryBackoffCoefficient`,
  `activityRetryMaximumInterval`, `activityRetryMaximumAttempts`).
- Added init-time validation for positive integer constraints on scheduler and retry policy
  configurable values (`maxConcurrentWorkflows`, `maxConcurrentActivities`,
  `activityRetryInitialInterval`, `activityRetryBackoffCoefficient`,
  `activityRetryMaximumInterval`, `activityRetryMaximumAttempts`).
- Added runtime validation in `parseRetryPolicy` (native layer) to reject invalid retry policy
  values from `callActivity` options (defense-in-depth for per-call `ActivityOptions`).

### Removed

- **[Breaking]** Removed `workflow:registerProcess()` from the public API — this function was an
  internal API used by the compiler plugin code generation. It has been moved to the
  `ballerina/workflow.internal` submodule as `registerWorkflow()`. Application code
  should not call this function directly; compiler-plugin-generated code may call
  `workflow.internal:registerWorkflow()` as needed.


## [0.2.0] - 2026-03-04

### Changed

- **[Breaking]** Renamed `@workflow:Process` annotation to `@workflow:Workflow`
- **[Breaking]** Renamed `workflow:createInstance()` function to `workflow:run()`
- **[Breaking]** Changed `workflow:sendData()` to require all parameters explicitly:
  `sendData(function workflow, string workflowId, string dataName, anydata data) returns error?`
  (previously used optional named parameters with `boolean|error` return)
- **[Breaking]** Removed automatic correlation-based signal routing from `sendData()`
- **[Breaking]** Workflow instance IDs are now plain UUID v7 strings (previously prefixed with process name)
- Removed compiler plugin error codes WORKFLOW_118, WORKFLOW_119, WORKFLOW_120 (no longer applicable with required sendData params)
- Changed WORKFLOW_112 (ambiguous signal types) from error to warning

### Added

- **[Breaking]** Redesigned `WorkflowConfig` as a union type supporting four deployment modes:
  - `LocalConfig` - Local development server (default, replaces previous flat config)
  - `CloudConfig` - Managed cloud deployment with mandatory authentication
  - `SelfHostedConfig` - Self-hosted server with optional authentication
  - `InMemoryConfig` - Lightweight in-memory engine (not yet implemented)
- Added `WorkerConfig` record type (replaces `TemporalParams`) with `taskQueue`, `maxConcurrentWorkflows`, `maxConcurrentActivities`
- Added mTLS and API key authentication support for cloud and self-hosted deployments
- Config.toml now uses `mode` field instead of `provider`, and `worker` section instead of `params`

### Removed

- Removed `Provider` enum and `TemporalParams` record type (replaced by union-based `WorkflowConfig`)
- Removed provider-specific terminology from public API documentation

## [0.1.0] - 2025-02-05

### Added

- Initial implementation of the Ballerina Workflow module ([#8424](https://github.com/ballerina-platform/ballerina-library/issues/8424))
- Temporal SDK integration for durable workflow orchestration
- `@Process` annotation to define workflow entry points
- `@Activity` annotation to mark activity functions for external interactions
- `workflow:Context` client class with:
  - `callActivity()` remote method for invoking activities
  - `sleep()` for deterministic delays
  - `isReplaying()` for replay detection
  - `getWorkflowId()` and `getWorkflowType()` for workflow metadata
- `createInstance()` function to start workflow instances
- `sendData()` function for signal-based communication
- `registerProcess()` function for singleton worker registration
- Compiler plugin with validator and code modifier:
  - Validates `@Activity` functions are called via `ctx->callActivity()`
  - Prevents direct calls to `@Activity` functions inside `@Process` functions
  - Auto-generates `registerProcess()` calls at module level
- Future-based event handling with correlation support
- Event timeout support for signal waiting

