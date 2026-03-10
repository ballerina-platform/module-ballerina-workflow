# Temporal Scheduler

applyTo: "**/*.bal,**/WorkflowWorkerNative.java"

---

## Overview

The workflow module manages a Temporal scheduler ensuring:
- One workflow SDK instance per JVM
- Scheduler created at module initialization time
- Configuration via Ballerina configurable variables
- Union-type configuration supporting multiple deployment modes

## Current Implementation

### 1. Ballerina Layer

#### Configuration Types
Defined in [types.bal](ballerina/types.bal):
- `WorkflowConfig` — union type: `LocalConfig|CloudConfig|SelfHostedConfig|InMemoryConfig`
- Each variant is a closed record discriminated by a `mode` field (`"LOCAL"`, `"CLOUD"`, `"SELF_HOSTED"`, `"IN_MEMORY"`)
- `SchedulerConfig` — controls `taskQueue`, `maxConcurrentWorkflows`, `maxConcurrentActivities`, `defaultActivityRetryPolicy`
- `AuthConfig` — optional `apiKey`, `mtlsCert`, `mtlsKey`

#### Configurable Variable
Declared in [config.bal](ballerina/config.bal):
- `configurable WorkflowConfig workflowConfig = {mode: "LOCAL"};`

#### Module Initialization
Implemented in [module.bal](ballerina/module.bal):
- `init()` — calls `initModule()` to capture module reference, then `initWorkflowRuntime()`
- `initWorkflowRuntime()` — extracts connection parameters from the `WorkflowConfig` union type, dispatches to `initProgramNative()` or `initInMemoryProgramNative()`
- `startWorkflowRuntime()` — starts the Temporal scheduler (begins polling for tasks)
- `stopWorkflowRuntime()` — shuts down the scheduler gracefully

#### Configuration Examples (Config.toml)

**Local (default):**
```toml
[ballerina.workflow.workflowConfig]
mode = "LOCAL"
url = "localhost:7233"
namespace = "default"

[ballerina.workflow.workflowConfig.scheduler]
taskQueue = "my-task-queue"
maxConcurrentWorkflows = 50
maxConcurrentActivities = 50
```

**Cloud with API key:**
```toml
[ballerina.workflow.workflowConfig]
mode = "CLOUD"
url = "my-ns.my-account.tmprl.cloud:7233"
namespace = "my-ns.my-account"

[ballerina.workflow.workflowConfig.auth]
apiKey = "my-api-key"

[ballerina.workflow.workflowConfig.scheduler]
taskQueue = "my-task-queue"
```

**Self-hosted with mTLS:**
```toml
[ballerina.workflow.workflowConfig]
mode = "SELF_HOSTED"
url = "temporal.mycompany.com:7233"
namespace = "production"

[ballerina.workflow.workflowConfig.auth]
mtlsCert = "/path/to/client.pem"
mtlsKey = "/path/to/client.key"

[ballerina.workflow.workflowConfig.scheduler]
taskQueue = "my-task-queue"
```

### 2. Native Layer

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Scheduler state** — static volatile fields for `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory`, `Worker`, and `taskQueue`. Thread safety via `AtomicBoolean` flags (`initialized`, `started`, `dynamicWorkflowRegistered`, `dynamicActivityRegistered`).

**Key methods:**
- `initSingletonWorker(BString url, BString namespace, BString taskQueue, long maxWorkflows, long maxActivities, BString apiKey, BString mtlsCert, BString mtlsKey, BMap retryPolicy)` — creates gRPC connection, `WorkflowClient`, `WorkerFactory`, and `Worker` with the configured task queue and concurrency limits. Configures mTLS or API key auth when provided.
- `initInMemoryWorker()` — creates an in-memory test scheduler (no external Temporal server needed)
- `registerWorkflow(Environment, BFunctionPointer workflowFunc, BString workflowName, Object activities)` — stores workflow in `PROCESS_REGISTRY`, activities in `ACTIVITY_REGISTRY`, registers `BallerinaWorkflowAdapter` and `BallerinaActivityAdapter` (once each)
- `startSingletonWorker()` — calls `workerFactory.start()` to begin polling the task queue
- `stopSingletonWorker()` — calls `workerFactory.shutdown()` and `serviceStubs.shutdown()`

### 3. Compiler Plugin Layer

The compiler plugin has **no direct involvement** in the scheduler lifecycle. It auto-generates `registerWorkflow()` calls via [WorkflowSourceModifier.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowSourceModifier.java), but the scheduler is entirely managed by the runtime (module init + native code).

## Lifecycle Sequence

```
1. Module Load
   └─> init() called
       ├─> initModule() — capture module reference
       └─> initWorkflowRuntime()
           ├─> Extract config from WorkflowConfig union type
           ├─> Configure auth (mTLS/API key) if provided
           └─> initProgramNative() → creates WorkflowServiceStubs, WorkflowClient, WorkerFactory, Worker

2. Compiler Plugin Code Generation
   └─> For each @Workflow function
       └─> Generates: wfInternal:registerWorkflow(myWorkflow, "myWorkflow", activities)
           └─> registerWorkflow() called
               ├─> PROCESS_REGISTRY.put(name, function)
               ├─> ACTIVITY_REGISTRY.put(name, function) for each activity
               ├─> Register BallerinaWorkflowAdapter (once)
               └─> Register BallerinaActivityAdapter (once)

3. Module Start
   └─> startWorkflowRuntime()
       └─> workerFactory.start() — begin polling for tasks

4. Runtime Execution
   ├─> run() → WorkflowClient.start() → creates workflow
   └─> Temporal dispatches task → BallerinaWorkflowAdapter.execute()
       └─> PROCESS_REGISTRY.get(workflowType) → calls Ballerina function

5. Module Stop
   └─> stopWorkflowRuntime()
       ├─> workerFactory.shutdown()
       └─> serviceStubs.shutdown()
```

## Key Design Points

1. **One Instance Per JVM**: Only one Temporal scheduler exists per JVM
2. **Lazy Registration**: Workflows/activities are registered during code generation phase
3. **Eager Initialization**: Scheduler is created at module init (before `start()`)
4. **Late Start**: Scheduler only starts polling after all registrations complete
5. **No Listener Pattern**: No `workflow:Listener` — everything is automatic
6. **Thread-Safe**: Uses `AtomicBoolean` and `ConcurrentHashMap` for thread safety

## Success Criteria

- Module init successfully creates `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory`
- Scheduler is created with configured task queue
- Configuration is correctly read from `Config.toml`
- Default configuration works without `Config.toml`
- `registerWorkflow()` successfully stores workflow functions in `PROCESS_REGISTRY`
- Activity functions are stored in `ACTIVITY_REGISTRY`
- Dynamic adapters are registered exactly once
- Scheduler starts polling only after `startWorkflowRuntime()` is called
- Scheduler gracefully shuts down on `stopWorkflowRuntime()`
- Thread-safe initialization prevents race conditions
- Workflows execute successfully via `BallerinaWorkflowAdapter`
- Activities execute successfully via `BallerinaActivityAdapter`

✅ **Configuration:**
- Config.toml overrides work correctly
- Invalid configuration produces clear error messages
- Worker respects concurrency limits from configuration
