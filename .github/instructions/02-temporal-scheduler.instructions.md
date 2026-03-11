# Temporal Scheduler

applyTo: "ballerina/*.bal,ballerina/config.bal,ballerina/module.bal,**/WorkflowWorkerNative.java"

---

## Overview

The workflow module manages a Temporal scheduler ensuring:
- One workflow SDK instance per JVM
- Scheduler created at module initialization time
- Configuration via Ballerina configurable variables
- Enum-based mode selection (LOCAL, CLOUD, SELF_HOSTED, IN_MEMORY)

## Current Implementation

### 1. Ballerina Layer

#### Configuration Variables
Defined in [config.bal](ballerina/config.bal) as flat `configurable` variables:
- `mode` — deployment mode enum: `LOCAL`, `CLOUD`, `SELF_HOSTED`, or `IN_MEMORY`
- `url`, `namespace` — server connection parameters
- `authApiKey`, `authMtlsCert`, `authMtlsKey`, `authCaCert` — authentication (`string?` optional, used by CLOUD/SELF_HOSTED modes)
- `taskQueue`, `maxConcurrentWorkflows`, `maxConcurrentActivities` — scheduler parameters
- `activityRetryInitialInterval`, `activityRetryBackoffCoefficient`, `activityRetryMaximumInterval`, `activityRetryMaximumAttempts` — default activity retry policy

#### Configurable Variable
Declared in [config.bal](ballerina/config.bal):
- All variables are flat `configurable` values under `[ballerina.workflow]` in Config.toml

#### Module Initialization
Implemented in [module.bal](ballerina/module.bal):
- `init()` — calls `initModule()` to capture module reference, then `initWorkflowRuntime()`
- `initWorkflowRuntime()` — validates mode-specific constraints (e.g., CLOUD requires auth), validates positive integer fields, dispatches to `initProgramNative()` or `initInMemoryProgramNative()`
- `startWorkflowRuntime()` — starts the Temporal scheduler (begins polling for tasks)
- `stopWorkflowRuntime()` — shuts down the scheduler gracefully

#### Configuration Examples (Config.toml)

**Local (default):**
```toml
[ballerina.workflow]
mode = "LOCAL"
url = "localhost:7233"
namespace = "default"
taskQueue = "my-task-queue"
maxConcurrentWorkflows = 50
maxConcurrentActivities = 50
```

**Cloud with API key:**
```toml
[ballerina.workflow]
mode = "CLOUD"
url = "my-ns.my-account.tmprl.cloud:7233"
namespace = "my-ns.my-account"
authApiKey = "my-api-key"
taskQueue = "my-task-queue"
```

**Self-hosted with mTLS:**
```toml
[ballerina.workflow]
mode = "SELF_HOSTED"
url = "temporal.mycompany.com:7233"
namespace = "production"
authMtlsCert = "/path/to/client.pem"
authMtlsKey = "/path/to/client.key"
taskQueue = "my-task-queue"
```

### 2. Native Layer

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Scheduler state** — static volatile fields for `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory`, `Worker`, and `taskQueue`. Thread safety via `AtomicBoolean` flags (`initialized`, `started`, `dynamicWorkflowRegistered`, `dynamicActivityRegistered`).

**Key methods:**
- `initSingletonWorker(BString url, BString namespace, BString taskQueue, long maxWorkflows, long maxActivities, BString apiKey, BString mtlsCert, BString mtlsKey, BString caCert, BMap retryPolicy)` — creates gRPC connection, `WorkflowClient`, `WorkerFactory`, and `Worker` with the configured task queue and concurrency limits. Auth strings are empty when not configured (Ballerina layer coalesces `()` → `""` before calling native). Configures mTLS or API key auth when provided; `caCert` path is used to build a custom SSL trust store when the server's certificate is from a private CA.
- `initInMemoryWorker()` — creates an in-memory test scheduler (no external Temporal server needed)
- `registerWorkflow(Environment, BFunctionPointer workflowFunc, BString workflowName, Object activities)` — stores workflow in `PROCESS_REGISTRY`, activities in `ACTIVITY_REGISTRY`, registers `BallerinaWorkflowAdapter` and `BallerinaActivityAdapter` (once each)
- `startSingletonWorker()` — calls `workerFactory.start()` to begin polling the task queue
- `stopSingletonWorker()` — calls `workerFactory.shutdown()` (graceful drain) and `serviceStubs.shutdown()`
- `stopSingletonWorkerNow()` — calls `workerFactory.shutdownNow()` (forceful interrupt) followed by `awaitTermination()`

### 3. Compiler Plugin Layer

The compiler plugin has **no direct involvement** in the scheduler lifecycle. It auto-generates `registerWorkflow()` calls via [WorkflowSourceModifier.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowSourceModifier.java), but the scheduler is entirely managed by the runtime (module init + native code).

## Lifecycle Sequence

```text
1. Module Load
   └─> init() called
       ├─> initModule() — capture module reference
       └─> initWorkflowRuntime()
           ├─> Validate mode-specific constraints (enum-based)
           ├─> Coalesce optional auth (nil → "") for native layer
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
- The workflow scheduler respects concurrency limits from configuration
