# Changelog

This file contains all the notable changes done to the Ballerina Workflow package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- New `ballerina/workflow.'internal` submodule containing internal registration APIs used by the
  compiler plugin. Not intended for direct use by application code.
- New `ballerina/workflow.activity` submodule with workflow-safe utility functions:
  - `activity:sleep(time:Duration duration)` — Durable, deterministic sleep that replaces
    `Context.sleep()`. Persisted in workflow history and survives program restarts.
  - `activity:currentTime() returns time:Utc` — Returns the current workflow time as
    reported by the workflow engine. This value is deterministic across replays and is
    **not** the same as `time:utcNow()` from `ballerina/time`, which reads the OS clock
    and must not be used inside `@workflow:Workflow` functions.
- Both `workflow.internal` and `workflow.activity` submodules are now exported in `Ballerina.toml`.
- Added `WORKFLOW_113` compiler diagnostic (warning) for usage of `time:utcNow()` inside
  `@workflow:Workflow` functions — suggests using `activity:currentTime()` instead.

### Changed

- **[Breaking]** Renamed `WorkerConfig` to `SchedulerConfig` — all references to "worker" in the
  public API have been replaced with "scheduler".

### Removed

- **[Breaking]** Removed `workflow:registerProcess()` from the public API — this function was an
  internal API used by the compiler plugin code generation. It has been moved to the
  `ballerina/workflow.'internal` submodule as `registerWorkflow()`. User code
  and compiler plugin-generated code should not call this function directly.
- **[Breaking]** Removed `Context.sleep()` method — use the new `activity:sleep()` function
  instead.


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

- New `workflow:searchWorkflow()` function to find workflows by correlation keys:
  `searchWorkflow(function workflow, map<anydata> correlationKeys) returns string|error`
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

