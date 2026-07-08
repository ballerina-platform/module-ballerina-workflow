# Changelog

This file contains all the notable changes done to the Ballerina Workflow package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

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

