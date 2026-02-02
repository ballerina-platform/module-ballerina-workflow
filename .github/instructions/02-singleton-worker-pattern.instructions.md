# Singleton Worker Pattern (v2)

applyTo: "**/*.bal,**/WorkflowWorkerNative.java"

---

## Overview

The workflow module uses a singleton worker pattern ensuring:
- Only one Temporal SDK instance per JVM
- Worker created at module initialization time
- Configuration via Ballerina configurable variables
- Provider-agnostic configuration structure

## Current Implementation

### 1. Ballerina Layer

#### Configuration Types ([types.bal](ballerina/types.bal))
```ballerina
# Supported workflow providers
public enum Provider {
    TEMPORAL
}

# Temporal-specific configuration parameters
public type TemporalParams record {|
    string taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";
    int maxConcurrentWorkflows = 100;
    int maxConcurrentActivities = 100;
    AuthConfig? authentication = ();
|};

# Workflow module configuration (generic structure)
public type WorkflowConfig record {|
    Provider provider = TEMPORAL;
    string url = "localhost:7233";
    string namespace = "default";
    TemporalParams params = {};
|};
```

#### Configurable Variable ([config.bal](ballerina/config.bal))
```ballerina
# The workflow module configuration
# Read from Config.toml or set programmatically
configurable WorkflowConfig workflowConfig = {};
```

#### Module Initialization ([module.bal](ballerina/module.bal))
```ballerina
isolated boolean workerStarted = false;

# Module initialization - called automatically at module load
function init() returns error? {
    initModule();                    // Capture module reference
    check initSingletonWorker();     // Initialize worker
}

# Start lifecycle hook - called after all modules loaded
function 'start() returns error? {
    check startWorker();             // Start polling for tasks
}

# Stop lifecycle hook - called on graceful shutdown
function stop() returns error? {
    check stopWorker();              // Stop worker gracefully
}

# Initialize the singleton worker with configured settings
isolated function initSingletonWorker() returns error? {
    lock {
        if workerStarted {
            return;
        }
        check initWorkerNative(
            workflowConfig.url,
            workflowConfig.namespace,
            workflowConfig.params.taskQueue,
            workflowConfig.params.maxConcurrentWorkflows,
            workflowConfig.params.maxConcurrentActivities
        );
        workerStarted = true;
    }
}
```

#### Configuration Example (Config.toml)
```toml
[ballerina.workflow.workflowConfig]
provider = "TEMPORAL"
url = "localhost:7233"
namespace = "default"

[ballerina.workflow.workflowConfig.params]
taskQueue = "my-task-queue"
maxConcurrentWorkflows = 50
maxConcurrentActivities = 50
```

### 2. Native Layer

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Singleton State:**
```java
// Singleton worker components
private static volatile WorkflowServiceStubs serviceStubs;
private static volatile WorkflowClient workflowClient;
private static volatile WorkerFactory workerFactory;
private static volatile Worker singletonWorker;
private static volatile String taskQueue;

// Initialization flags
private static final AtomicBoolean initialized = new AtomicBoolean(false);
private static final AtomicBoolean started = new AtomicBoolean(false);
private static final AtomicBoolean dynamicWorkflowRegistered = new AtomicBoolean(false);
private static final AtomicBoolean dynamicActivityRegistered = new AtomicBoolean(false);

// Runtime context
private static Module workflowModule;
private static Runtime ballerinaRuntime;
```

**Worker Initialization:**
```java
public static Object initSingletonWorker(
        BString url,
        BString namespace,
        BString workerTaskQueue,
        long maxConcurrentWorkflows,
        long maxConcurrentActivities) {
    
    if (!initialized.compareAndSet(false, true)) {
        LOGGER.debug("Singleton worker already initialized");
        return null;
    }

    try {
        // 1. Create WorkflowServiceStubs (gRPC connection)
        WorkflowServiceStubsOptions stubsOptions = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(url.getValue())
            .build();
        serviceStubs = WorkflowServiceStubs.newServiceStubs(stubsOptions);

        // 2. Create WorkflowClient
        workflowClient = WorkflowClient.newInstance(serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace.getValue())
                .build());

        // 3. Create WorkerFactory
        workerFactory = WorkerFactory.newInstance(workflowClient);

        // 4. Create Worker with task queue
        taskQueue = workerTaskQueue.getValue();
        WorkerOptions options = WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskExecutionSize((int) maxConcurrentWorkflows)
            .setMaxConcurrentActivityExecutionSize((int) maxConcurrentActivities)
            .build();
        singletonWorker = workerFactory.newWorker(taskQueue, options);

        LOGGER.info("Singleton worker initialized - TaskQueue: {}", taskQueue);
        return null;
    } catch (Exception e) {
        initialized.set(false);
        return createError("Failed to initialize singleton worker", e);
    }
}
```

**Process Registration:**
```java
public static Object registerProcessWithWorker(
        BFunctionPointer processFunc,
        BString processName,
        BMap<BString, Object> activities) {
    
    if (!initialized.get()) {
        return createError("Cannot register process - worker not initialized");
    }

    String name = processName.getValue();
    
    // 1. Store in registry
    PROCESS_REGISTRY.put(name, processFunc);
    
    // 2. Register activities if provided
    if (activities != null) {
        for (Map.Entry<BString, Object> entry : activities.entrySet()) {
            String activityName = entry.getKey().getValue();
            BFunctionPointer activityFunc = (BFunctionPointer) entry.getValue();
            ACTIVITY_REGISTRY.put(activityName, activityFunc);
        }
    }
    
    // 3. Register dynamic workflow adapter (once)
    if (!dynamicWorkflowRegistered.getAndSet(true)) {
        singletonWorker.registerWorkflowImplementationTypes(
            BallerinaWorkflowAdapter.class);
    }
    
    // 4. Register dynamic activity adapter (once)
    if (!dynamicActivityRegistered.getAndSet(true)) {
        singletonWorker.registerActivitiesImplementations(
            new BallerinaActivityAdapter());
    }
    
    LOGGER.info("Registered process: {}", name);
    return true;
}
```

**Worker Lifecycle:**
```java
public static Object startSingletonWorker() {
    if (!initialized.get()) {
        return createError("Cannot start worker - not initialized");
    }
    
    if (started.getAndSet(true)) {
        LOGGER.debug("Worker already started");
        return null;
    }

    try {
        workerFactory.start();
        LOGGER.info("Singleton worker started - polling task queue: {}", taskQueue);
        return null;
    } catch (Exception e) {
        started.set(false);
        return createError("Failed to start worker", e);
    }
}

public static Object stopSingletonWorker() {
    if (!initialized.get() || !started.get()) {
        return null;
    }

    try {
        workerFactory.shutdown();
        workerFactory.awaitTermination(30, TimeUnit.SECONDS);
        serviceStubs.shutdown();
        LOGGER.info("Singleton worker stopped");
        return null;
    } catch (Exception e) {
        return createError("Failed to stop worker", e);
    }
}
```

### 3. Compiler Plugin Layer

The compiler plugin has **no direct involvement** in the singleton worker pattern. It auto-generates `registerProcess()` calls, but the singleton pattern is entirely managed by the runtime (module init + native code).

## Lifecycle Sequence

```
1. Module Load
   └─> init() called
       ├─> initModule() - capture module reference
       └─> initSingletonWorker()
           ├─> initWorkerNative()
           └─> Creates: WorkflowServiceStubs, WorkflowClient, WorkerFactory, Worker

2. Compiler Plugin Code Generation
   └─> For each @Process function
       └─> Generates: registerProcess(myProcess, "myProcess", activities)
           └─> registerProcessWithWorker() called
               ├─> PROCESS_REGISTRY.put(name, function)
               ├─> ACTIVITY_REGISTRY.put(name, function) for each activity
               ├─> Register BallerinaWorkflowAdapter (once)
               └─> Register BallerinaActivityAdapter (once)

3. Module Start
   └─> 'start() called
       └─> startWorker()
           └─> workerFactory.start() - begin polling Temporal

4. Runtime Execution
   ├─> startProcess() → WorkflowClient.start() → Temporal creates workflow
   └─> Worker polls task → BallerinaWorkflowAdapter.execute()
       └─> PROCESS_REGISTRY.get(workflowType) → calls Ballerina function

5. Module Stop
   └─> stop() called
       └─> stopWorker()
           ├─> workerFactory.shutdown()
           └─> serviceStubs.shutdown()
```

## Key Design Points

1. **One Worker Per JVM**: The singleton pattern ensures only one Temporal worker exists
2. **Lazy Registration**: Workflows/activities are registered during code generation phase
3. **Eager Initialization**: Worker is created at module init (before `start()`)
4. **Late Start**: Worker only starts polling after all registrations complete
5. **No Listener Pattern**: Unlike previous designs, no `workflow:Listener` - everything is automatic
6. **Thread-Safe**: Uses `AtomicBoolean` and `ConcurrentHashMap` for thread safety

## Success Criteria

✅ **Initialization:**
- Module init successfully creates WorkflowServiceStubs, WorkflowClient, WorkerFactory
- Worker is created with configured task queue
- Configuration is correctly read from Config.toml
- Default configuration works without Config.toml

✅ **Registration:**
- `registerProcess()` successfully stores process functions in PROCESS_REGISTRY
- Activity functions are stored in ACTIVITY_REGISTRY
- Dynamic adapters are registered exactly once
- Multiple process registrations work without conflicts

✅ **Worker Lifecycle:**
- Worker starts polling only after `start()` is called
- Worker gracefully shuts down on `stop()`
- No duplicate workers are created
- Thread-safe initialization prevents race conditions

✅ **Runtime Execution:**
- Workflows execute successfully via BallerinaWorkflowAdapter
- Activities execute successfully via BallerinaActivityAdapter
- Multiple concurrent workflows execute within maxConcurrentWorkflows limit
- Multiple concurrent activities execute within maxConcurrentActivities limit

✅ **Configuration:**
- Config.toml overrides work correctly
- Invalid configuration produces clear error messages
- Worker respects concurrency limits from configuration
