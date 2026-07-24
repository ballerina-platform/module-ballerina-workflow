# Changelog

This file contains all the notable changes done to the Ballerina Workflow package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  tied to the caller). Non-blocking reads (`getResult`/`getEventResult`) return the
  value or a `workflow:AgentBusyError` while the agent is still working; blocking
  reads (`waitForResult`/`waitForEventResult`) suspend durably inside workflows and
  are crash-resumable from services. `sendEvent(instanceId, eventName, data)` sends
  one turn and returns a correlation token — a Temporal Update from services
  (rediscoverable via `getPendingAgentUpdates`), a deterministic reply-correlated
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
- `sendEvent()` function for signal-based communication
- `registerProcess()` function for singleton worker registration
- Compiler plugin with validator and code modifier:
  - Validates `@Activity` functions are called via `ctx->callActivity()`
  - Prevents direct calls to `@Activity` functions inside `@Process` functions
  - Auto-generates `registerProcess()` calls at module level
- Future-based event handling with correlation support
- Event timeout support for signal waiting

