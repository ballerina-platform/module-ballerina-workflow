# Changelog

This file contains all the notable changes done to the Ballerina Workflow package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- [Fix#8737](https://github.com/ballerina-platform/ballerina-library/issues/8737) - `ctx->await()`: Fix compile-time type validation and partial-wait runtime semantics  

## [0.3.2] - 2026-03-26

- Minor Bug Fixes and Improvements. [1](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.1...release-0.3.2)

## [0.3.1] - 2026-03-24

- Minor Bug Fixes and Improvements. [1](https://github.com/ballerina-platform/module-ballerina-workflow/compare/release-0.3.0...release-0.3.1)

## [0.3.0] - 2026-03-30

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

