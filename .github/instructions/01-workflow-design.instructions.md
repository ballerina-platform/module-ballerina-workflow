# Workflow Design Overview

applyTo: "**/*.bal"

---

## Core Concepts

The Ballerina Workflow module provides durable workflow orchestration via Temporal SDK integration.

## Current Implementation

### 1. Ballerina Layer ([ballerina/](ballerina/))

#### Annotations ([annotations.bal](ballerina/annotations.bal))
- `@Workflow` — marks a function as a workflow process
- `@Activity` — marks a function as a workflow activity

#### Public API Functions ([functions.bal](ballerina/functions.bal))
- `run(function, map<anydata>?)` → `string|error` — start a new workflow instance
- `sendData(function, string, string, anydata)` → `error?` — send data to a running workflow
- `getWorkflowResult(string, int)` → `WorkflowExecutionInfo|error` — get workflow result
- `getWorkflowInfo(string)` → `WorkflowExecutionInfo|error` — get workflow execution info
- `getRegisteredWorkflows()` → `WorkflowRegistry|error` — list registered workflows

#### Internal Registration ([modules/internal/register.bal](ballerina/modules/internal/register.bal))
- `registerWorkflow(function, string, map<function>?)` → `boolean|error` — called by compiler-generated code to register workflows

#### Context Client Class ([context.bal](ballerina/context.bal))
- `callActivity(function, map<anydata>, ActivityOptions?, typedesc<anydata>)` → `T|error` — remote method to call an activity
- `sleep(time:Duration)` → `error?` — deterministic sleep (survives restarts)
- `currentTime()` → `time:Utc` — deterministic current time (same value during replays)
- `isReplaying()` → `boolean` — check if workflow is currently replaying
- `getWorkflowId()` → `string|error` — get the workflow ID
- `getWorkflowType()` → `string|error` — get the workflow type name

### 2. Compiler Plugin Layer ([compiler-plugin/](compiler-plugin/))

#### WorkflowCompilerPlugin ([WorkflowCompilerPlugin.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCompilerPlugin.java))
- Registers analysis and code modification tasks
- Validates `@Workflow` and `@Activity` function signatures

#### WorkflowValidatorTask ([WorkflowValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowValidatorTask.java))
- **WORKFLOW_107**: Validates `ctx->callActivity()` calls use `@Activity` functions
- **WORKFLOW_108**: Prevents direct calls to `@Activity` functions inside `@Workflow`
- Validates process function signature: `(Context?, anydata, record{future<T>...}?)`
- Validates activity function parameters and return types are `anydata` subtypes

#### WorkflowSourceModifier ([WorkflowSourceModifier.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowSourceModifier.java))
- Auto-generates `wfInternal:registerWorkflow()` calls for each `@Workflow` function at module level
- Generates `import ballerina/workflow.internal as wfInternal;` import
- Extracts activity functions used in each process

### 3. Native Layer ([native/](native/))

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Key registries** — `PROCESS_REGISTRY` (workflow type → `BFunctionPointer`), `ACTIVITY_REGISTRY` (activity name → `BFunctionPointer`), `EVENT_REGISTRY` (process name → event names list)

**Scheduler management** — `initSingletonWorker()`, `registerWorkflow()`, `startSingletonWorker()`, `stopSingletonWorker()` (see [02-temporal-scheduler.instructions.md](.github/instructions/02-temporal-scheduler.instructions.md))

**Dynamic adapters:**
- `BallerinaWorkflowAdapter` (implements `DynamicWorkflow`) — routes all workflow types through a single adapter, injects `Context`, creates event futures, calls registered process functions
- `BallerinaActivityAdapter` (implements `DynamicActivity`) — reconstructs positional args from named map using `FunctionType.getParameters()`, calls registered activity functions

#### WorkflowNative.java
Location: [WorkflowNative.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/nativeimpl/WorkflowNative.java)
- Implements `run()`, `sendData()`, `searchWorkflow()` by interacting with Temporal's `WorkflowClient`

## Usage Patterns

### Process Function Signature
`@Workflow` functions follow this parameter order (see examples in [integration-tests/](integration-tests/)):
1. `workflow:Context ctx` — optional, must be first if calling activities
2. `T input` — input data (`anydata` subtype)
3. `record {| future<U> event1; ... |} events` — optional event futures

### Activity Function Signature
`@Activity` functions accept `anydata` parameters and return `anydata|error`. See examples in [integration-tests/](integration-tests/).

### Calling Activities (Required Pattern)
Activities **must** be called via `ctx->callActivity(activityFunc, args)` — direct calls produce `WORKFLOW_108` error.

### Waiting for Events
Events are received via `check wait events.fieldName` using the Ballerina `wait` keyword.

## Type Requirements

| Component | Requirement |
|-----------|-------------|
| Process input | Subtype of `anydata`, must have `@workflow:CorrelationKey` fields for correlation if using signals |
| Process return | Subtype of `anydata` or `error` |
| Activity params | Subtype of `anydata` |
| Activity return | Subtype of `anydata` or `error` |
| Signal futures | `future<T>` where `T` is subtype of `anydata` |
| Event data | Subtype of `anydata` |

## Success Criteria

- `@Workflow` functions compile with valid signatures
- `@Activity` functions compile with `anydata` parameters and return types
- `ctx->callActivity()` calls compile when targeting `@Activity` functions
- Direct activity calls produce WORKFLOW_108 compiler error
- Calls to non-activity functions via `callActivity()` produce WORKFLOW_107 error
- `run()` successfully starts workflows and returns workflow ID
- `sendData()` successfully sends data to running workflows
- `ctx->callActivity()` executes activities and returns results
- Compiler plugin auto-generates `wfInternal:registerWorkflow()` calls for each `@Workflow` function
