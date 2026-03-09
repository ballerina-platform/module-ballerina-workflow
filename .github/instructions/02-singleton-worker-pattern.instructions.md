# Singleton Worker Pattern (v2)

applyTo: "**/*.bal,**/WorkflowWorkerNative.java"

---

## Overview

The workflow module uses a singleton worker pattern ensuring:
- Only one workflow SDK instance per JVM
- Worker created at module initialization time
- Configuration via Ballerina configurable variables
- Union-type configuration supporting multiple deployment modes

## Current Implementation

### 1. Ballerina Layer

#### Configuration Types ([types.bal](ballerina/types.bal))
```ballerina
# Deployment-specific configuration records, discriminated by `mode` field
public type WorkflowConfig LocalConfig|CloudConfig|SelfHostedConfig|InMemoryConfig;

public type LocalConfig record {|
    "LOCAL" mode = "LOCAL";
    string url = "localhost:7233";
    string namespace = "default";
    SchedulerConfig scheduler = {};
|};

public type CloudConfig record {|
    "CLOUD" mode;
    string url;
    string namespace;
    AuthConfig auth;
    SchedulerConfig scheduler = {};
|};

public type SelfHostedConfig record {|
    "SELF_HOSTED" mode;
    string url;
    string namespace = "default";
    AuthConfig? auth = ();
    SchedulerConfig scheduler = {};
|};

public type InMemoryConfig record {|
    "IN_MEMORY" mode = "IN_MEMORY";
|};

public type SchedulerConfig record {|
    string taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";
    int maxConcurrentWorkflows = 100;
    int maxConcurrentActivities = 100;
|};

public type AuthConfig record {|
    string? apiKey = ();
    string? mtlsCert = ();
    string? mtlsKey = ();
|};
```

#### Configurable Variable ([config.bal](ballerina/config.bal))
```ballerina
# The workflow module configuration
# Read from Config.toml or set programmatically
configurable WorkflowConfig workflowConfig = {mode: "LOCAL"};
```

#### Module Initialization ([module.bal](ballerina/module.bal))
```ballerina
isolated boolean programStarted = false;

# Module initialization - called automatically at module load
function init() returns error? {
    initModule();                    // Capture module reference
    check initWorkflowRuntime();     // Initialize workflow runtime
}

# Initialize the workflow runtime with configured settings
isolated function initWorkflowRuntime() returns error? {
    lock {
        if programStarted {
            return;
        }
        WorkflowConfig config = workflowConfig;
        if config is InMemoryConfig {
            check initInMemoryProgramNative();
            programStarted = true;
            return;
        }
        // Extract connection parameters based on deployment mode
        string url;
        string namespace;
        SchedulerConfig schedulerCfg;
        string apiKey = "";
        string mtlsCert = "";
        string mtlsKey = "";
        if config is CloudConfig {
            url = config.url;
            namespace = config.namespace;
            schedulerCfg = config.scheduler;
            apiKey = config.auth.apiKey ?: "";
            mtlsCert = config.auth.mtlsCert ?: "";
            mtlsKey = config.auth.mtlsKey ?: "";
        } else if config is SelfHostedConfig {
            url = config.url;
            namespace = config.namespace;
            schedulerCfg = config.scheduler;
            if config.auth is AuthConfig {
                AuthConfig auth = <AuthConfig>config.auth;
                apiKey = auth.apiKey ?: "";
                mtlsCert = auth.mtlsCert ?: "";
                mtlsKey = auth.mtlsKey ?: "";
            }
        } else {
            LocalConfig localCfg = <LocalConfig>config;
            url = localCfg.url;
            namespace = localCfg.namespace;
            schedulerCfg = localCfg.scheduler;
        }
        check initProgramNative(url, namespace, schedulerCfg.taskQueue,
                schedulerCfg.maxConcurrentWorkflows, schedulerCfg.maxConcurrentActivities,
                apiKey, mtlsCert, mtlsKey,
                schedulerCfg.defaultActivityRetryPolicy);
        programStarted = true;
    }
}
```

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
        long maxConcurrentActivities,
        BString apiKey,
        BString mtlsCert,
        BString mtlsKey) {
    
    if (!initialized.compareAndSet(false, true)) {
        LOGGER.debug("Singleton worker already initialized");
        return null;
    }

    try {
        // 1. Create WorkflowServiceStubs (gRPC connection)
        WorkflowServiceStubsOptions.Builder stubsBuilder = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(url.getValue());

        // Configure mTLS if certificate and key are provided
        if (!mtlsCert.getValue().isEmpty() && !mtlsKey.getValue().isEmpty()) {
            SslContext sslContext = SslContextBuilder.forClient()
                .keyManager(new FileInputStream(mtlsCert.getValue()),
                            new FileInputStream(mtlsKey.getValue()))
                .build();
            stubsBuilder.setSslContext(sslContext);
            stubsBuilder.setEnableHttps(true);
        }

        // Configure API key authentication if provided
        if (!apiKey.getValue().isEmpty()) {
            stubsBuilder.addApiKey(() -> apiKey.getValue());
            stubsBuilder.setEnableHttps(true);
        }

        serviceStubs = WorkflowServiceStubs.newServiceStubs(stubsBuilder.build());

        // 2. Create WorkflowClient
        workflowClient = WorkflowClient.newInstance(serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace.getValue())
                .build());

        // 3. Create WorkerFactory and Worker
        workerFactory = WorkerFactory.newInstance(workflowClient);
        taskQueue = workerTaskQueue.getValue();
        WorkerOptions options = WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskExecutionSize((int) maxConcurrentWorkflows)
            .setMaxConcurrentActivityExecutionSize((int) maxConcurrentActivities)
            .build();
        singletonWorker = workerFactory.newWorker(taskQueue, options);

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
           ├─> Extract config from WorkflowConfig union type
           ├─> Configure auth (mTLS/API key) if provided
           ├─> initWorkerNative()
           └─> Creates: WorkflowServiceStubs, WorkflowClient, WorkerFactory, Worker

2. Compiler Plugin Code Generation
   └─> For each @Workflow function
       └─> Generates: registerProcess(myProcess, "myProcess", activities)
           └─> registerProcessWithWorker() called
               ├─> PROCESS_REGISTRY.put(name, function)
               ├─> ACTIVITY_REGISTRY.put(name, function) for each activity
               ├─> Register BallerinaWorkflowAdapter (once)
               └─> Register BallerinaActivityAdapter (once)

3. Module Start
   └─> 'start() called
       └─> startWorker()
           └─> workerFactory.start() - begin polling for tasks

4. Runtime Execution
   ├─> run() → WorkflowClient.start() → creates workflow
   └─> Worker polls task → BallerinaWorkflowAdapter.execute()
       └─> PROCESS_REGISTRY.get(workflowType) → calls Ballerina function

5. Module Stop
   └─> stop() called
       └─> stopWorker()
           ├─> workerFactory.shutdown()
           └─> serviceStubs.shutdown()
```

## Key Design Points

1. **One Worker Per JVM**: The singleton pattern ensures only one workflow worker exists
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
