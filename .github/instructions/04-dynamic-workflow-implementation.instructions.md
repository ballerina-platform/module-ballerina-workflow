# Dynamic Workflow Implementation (Temporal Wrapper)

applyTo: "**/native/**/*.java"

---

## Overview

This module provides a Ballerina integration for Temporal using Dynamic Workflow and Dynamic Activity patterns to route workflow executions to Ballerina functions.

## Current Implementation

### 1. Design Principles

1. **Dynamic Workflow Pattern**: Uses Temporal's `DynamicWorkflow` interface to route all workflow types through a single adapter (`BallerinaWorkflowAdapter`)
2. **Dynamic Activity Pattern**: Uses Temporal's `DynamicActivity` interface to route all activity calls through `BallerinaActivityAdapter`
3. **Per-Instance Isolation**: Creates fresh workflow context for each execution
4. **Context Injection**: Workflow `Context` is automatically created and injected as the first parameter
5. **Signal/Query Routing**: Signal and query handlers are dynamically routed

### 2. Native Layer Implementation

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Registries** — static `ConcurrentHashMap` fields:
- `PROCESS_REGISTRY` — maps workflow type name → `BFunctionPointer`
- `ACTIVITY_REGISTRY` — maps activity name → `BFunctionPointer`
- `EVENT_REGISTRY` — maps process name → list of event field names

**`BallerinaWorkflowAdapter`** (inner class, implements `DynamicWorkflow`):
- Constructor registers `DynamicSignalHandler` and `DynamicQueryHandler` with Temporal via `Workflow.registerListener()`
- `execute(EncodedValues args)` performs these steps:
  1. Gets `workflowType` from `Workflow.getInfo()`
  2. Looks up process function from `PROCESS_REGISTRY`
  3. Extracts workflow arguments from Temporal's `EncodedValues`
  4. Upserts correlation keys as search attributes (for workflow discovery)
  5. Checks function signature for `Context` and events parameters via `EventExtractor`
  6. Builds args array: optional `Context` + input + optional events record (via `EventFutureCreator.createEventsRecord()`)
  7. Invokes the Ballerina function via `BFunctionPointer.call()`
  8. Converts `BError` to `ApplicationFailure`, converts result to Java type

**`BallerinaActivityAdapter`** (inner class, implements `DynamicActivity`):
- `execute(EncodedValues args)` receives `[namedArgsMap, callConfigMap]` from Temporal
- Reconstructs positional args from the named map using `FunctionType.getParameters()` order
- Sets `StrandMetadata` on the `FPValue` before calling
- Converts result back to Java type for Temporal persistence

#### WorkflowContextNative.java
Location: [WorkflowContextNative.java](native/src/main/java/io/ballerina/stdlib/workflow/context/WorkflowContextNative.java)

Implements the `workflow:Context` client class remote methods:
- `callActivity(BObject, BFunctionPointer, BMap<BString, Object>, Object, BTypedesc)` — builds full activity name (`workflowType.activityName`), converts `BMap` args to a `Map<String, Object>` for Temporal, parses `ActivityOptions` (failOnError, retryPolicy), executes via `ActivityStub`, converts result back using `typedesc`
- `currentTimeMillis(Object)` — delegates to `Workflow.currentTimeMillis()` for deterministic time
- `isReplaying(Object)` — delegates to `Workflow.isReplaying()`
- `sleep(BObject, BMap)` — delegates to `Workflow.sleep()` for deterministic sleep
- `getWorkflowId(BObject)` — returns `Workflow.getInfo().getWorkflowId()`
- `getWorkflowType(BObject)` — returns `Workflow.getInfo().getWorkflowType()`

### 3. Compiler Plugin Layer

The compiler plugin has **limited involvement** in dynamic workflow implementation:
- Validates `@Workflow` and `@Activity` function signatures
- Auto-generates `registerWorkflow()` calls
- Validates `ctx->callActivity()` calls use `@Activity` functions (WORKFLOW_107)
- Prevents direct activity calls (WORKFLOW_108)

## Execution Flow

```text
1. Workflow Start (via run)
   └─> WorkflowClient.start(workflowType, input)
       └─> Temporal schedules workflow task on task queue

2. Worker Polls and Executes
   └─> Worker.poll() receives workflow task
       └─> BallerinaWorkflowAdapter (new instance per execution)
           ├─> Constructor registers signal/query handlers
           └─> execute(encodedArgs)
               ├─> Get workflow type from Workflow.getInfo()
               ├─> Lookup processFunction in PROCESS_REGISTRY
               ├─> Extract arguments from encodedArgs
               ├─> Build arguments array (Context?, input, Events?)
               │   ├─> hasContext? → createWorkflowContext()
               │   └─> hasEvents? → EventFutureCreator.createEventsRecord()
               ├─> processFunction.call(ballerinaRuntime, args)
               └─> Convert result to Java type

3. Activity Execution (from within workflow)
   └─> Ballerina: ctx->callActivity(myActivity, {"arg1": val1, "arg2": val2})
       └─> WorkflowContextNative.callActivity()
           ├─> Extract activity name (workflowType.activityName)
           ├─> Convert BMap args to named Map<String,Object>
           ├─> Build callConfig map with failOnError flag
           ├─> Create ActivityStub with options
           ├─> stub.execute(name, [namedArgs, callConfig])
           └─> Temporal schedules activity task

4. Activity Task Execution
   └─> Worker.poll() receives activity task
       └─> BallerinaActivityAdapter.execute(encodedArgs)
           ├─> Get activity name from Activity.getExecutionContext()
           ├─> Lookup activityFunction in ACTIVITY_REGISTRY
           ├─> Decode [namedArgsMap, callConfigMap] from Temporal
           ├─> Reconstruct positional args using FunctionType.getParameters()
           ├─> activityFunction.call(ballerinaRuntime, orderedArgs)
           └─> Convert result to Java type (or throw on error if failOnError)

5. Event Handling
   └─> Ballerina: wait events.approval
       ├─> TemporalFutureValue.getAndSetWaited()
       │   └─> Workflow.await(() -> completableFuture.isDone())
       └─> External: sendData(processFunc, workflowId, "approval", data)
           └─> Temporal delivers event
               └─> DynamicSignalHandler.signal()
                   └─> signalWrapper.recordSignal()
                       └─> promise.complete() → completableFuture.complete()
                           └─> Workflow.await() returns → wait completes
```

## Key Design Points

1. **Single Adapter Classes**: One `BallerinaWorkflowAdapter` and one `BallerinaActivityAdapter` handle all workflows/activities
2. **Registry-Based Routing**: Workflow/activity type → function mapping via concurrent hash maps
3. **Per-Execution Instances**: New `BallerinaWorkflowAdapter` instance per workflow execution (including replays)
4. **Signal Wrapper Per Instance**: Each workflow execution gets its own `SignalAwaitWrapper` for signal isolation
5. **Context Injection**: `workflow:Context` automatically created and injected as first parameter if signature has it
6. **Events Injection**: Events record with `TemporalFutureValue` objects automatically created if signature has it
7. **Error Handling**: Ballerina errors converted to Temporal `ApplicationFailure` (non-retryable). `failOnError` flag controls whether activity errors are retried or returned as values.
8. **Named Args Map Pattern**: Activity arguments are passed through Temporal as a named `Map<String, Object>` rather than a positional array. The adapter reconstructs positional args using `FunctionType.getParameters()` and `Parameter.name`. This avoids misalignment when optional parameters are omitted from the args map.
9. **Call Config Map**: Each activity call includes a separate call config map (`[namedArgs, callConfig]`) carrying the `failOnError` flag and a marker to distinguish it from user data.

## Success Criteria

- Process functions successfully registered in `PROCESS_REGISTRY`
- Activity functions registered in `ACTIVITY_REGISTRY` with qualified names (`workflowType.activityName`)
- Dynamic adapters registered exactly once per Temporal worker
- `BallerinaWorkflowAdapter` routes to correct process function
- Context and events parameters injected when signature requires them
- `BallerinaActivityAdapter` reconstructs positional args from named map correctly
- Activity errors propagate to workflow as exceptions (when `failOnError=true`)
- Signal handler receives all signals and records them in per-instance `SignalAwaitWrapper`
- Each replay creates a new `BallerinaWorkflowAdapter` instance (no shared state)
- Deterministic execution during replay
