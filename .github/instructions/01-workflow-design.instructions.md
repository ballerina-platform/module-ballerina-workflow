# Workflow Design Overview

applyTo: "**/*.bal"

---

## Core Concepts

The Ballerina Workflow module provides durable workflow orchestration via Temporal SDK integration.

## Current Implementation

### 1. Ballerina Layer ([ballerina/](ballerina/))

#### Annotations ([annotations.bal](ballerina/annotations.bal))
```ballerina
# Marks a function as a workflow process
public annotation Process on function;

# Marks a function as a workflow activity
public annotation Activity on function;
```

#### Public API Functions ([functions.bal](ballerina/functions.bal))
```ballerina
# Start a new workflow instance
public isolated function createInstance(function processFunction, map<anydata> input) 
    returns string|error;

# Send an event/signal to a running workflow
public isolated function sendEvent(function processFunction, map<anydata> eventData, 
    string? signalName = ()) returns boolean|error;

# Register a process with the singleton worker
public isolated function registerProcess(function processFunction, string processName, 
    map<function>? activities = ()) returns boolean|error;
```

#### Context Client Class ([context.bal](ballerina/context.bal))
```ballerina
# Record type for activity parameters
public type Parameters record {|
    anydata...;
|};

# Workflow execution context
public client class Context {
    # Call an activity from within a workflow
    remote function callActivity(function activityFunction, Parameters args, 
        typedesc<anydata> T = <>) returns T|error;
    
    # Sleep for the specified duration (deterministic)
    function sleep(time:Duration duration) returns error?;
    
    # Check if workflow is currently replaying
    function isReplaying() returns boolean;
    
    # Get the workflow ID
    function getWorkflowId() returns string|error;
    
    # Get the workflow type name
    function getWorkflowType() returns string|error;
}
```

### 2. Compiler Plugin Layer ([compiler-plugin/](compiler-plugin/))

#### WorkflowCompilerPlugin ([WorkflowCompilerPlugin.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCompilerPlugin.java))
- Registers analysis and code modification tasks
- Validates `@Process` and `@Activity` function signatures

#### WorkflowValidatorTask ([WorkflowValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowValidatorTask.java))
- **WORKFLOW_107**: Validates `ctx->callActivity()` calls use `@Activity` functions
- **WORKFLOW_108**: Prevents direct calls to `@Activity` functions inside `@Process`
- Validates process function signature: `(Context?, anydata, record{future<T>...}?)`
- Validates activity function parameters and return types are `anydata` subtypes

#### WorkflowCodeModifier ([WorkflowCodeModifier.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCodeModifier.java))
- Auto-generates `registerProcess()` calls for each `@Process` function at module level
- Extracts activity functions used in each process

### 3. Native Layer ([native/](native/))

#### WorkflowWorkerNative.java
Location: [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java)

**Key registries:**
```java
private static final Map<String, BFunctionPointer> PROCESS_REGISTRY;
private static final Map<String, BFunctionPointer> ACTIVITY_REGISTRY;
private static final Map<String, List<String>> EVENT_REGISTRY;
```

**Singleton worker management:**
```java
public static Object initSingletonWorker(BString url, BString namespace, 
    BString taskQueue, long maxWorkflows, long maxActivities);
public static Object registerProcessWithWorker(BFunctionPointer processFunc, 
    BString processName, BMap activities);
public static Object startSingletonWorker();
```

**Dynamic adapters:**
```java
public static class BallerinaWorkflowAdapter implements DynamicWorkflow {
    @Override
    public Object execute(EncodedValues args) {
        // Routes to registered process function via PROCESS_REGISTRY
    }
}

public static class BallerinaActivityAdapter implements DynamicActivity {
    @Override
    public Object execute(EncodedValues args) {
        // Routes to registered activity function via ACTIVITY_REGISTRY
    }
}
```

#### WorkflowNative.java
Location: [WorkflowNative.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/nativeimpl/WorkflowNative.java)

Implements `createInstance()` and `sendEvent()` by interacting with Temporal's `WorkflowClient`.

## Usage Patterns

### Process Function Signature
```ballerina
@workflow:Process
function processName(
    workflow:Context ctx,           // Optional, must be first if calling activities
    T input,                        // Input data (anydata subtype)
    record { future<U> event1; future<V> event2; } events  // Optional signals
) returns R|error {
    // Workflow logic - must be deterministic
}
```

### Activity Function Signature
```ballerina
@workflow:Activity
function activityName(T param1, U param2) returns R|error {
    // Non-deterministic operations (I/O, external calls)
}
```

### Calling Activities (Required Pattern)
```ballerina
@workflow:Process
function myProcess(workflow:Context ctx, Input input) returns Result|error {
    // ✅ Correct: Use ctx->callActivity() with a Parameters record
    boolean result = check ctx->callActivity(sendEmail, {"email": input.email, "subject": "Subject"});
    
    // ❌ Error WORKFLOW_108: Direct calls not allowed
    // boolean result = check sendEmail(input.email, "Subject");
    
    return {status: result ? "sent" : "failed"};
}
```

### Waiting for Events
```ballerina
@workflow:Process
function processWithEvents(
    workflow:Context ctx,
    Input input,
    record { future<ApprovalData> approval; future<PaymentData> payment; } events
) returns Result|error {
    // Wait for approval signal
    ApprovalData approvalData = check wait events.approval;
    
    if approvalData.approved {
        // Wait for payment signal
        PaymentData paymentData = check wait events.payment;
        return {status: "completed", amount: paymentData.amount};
    }
    
    return {status: "rejected"};
}
```

## Type Requirements

| Component | Requirement |
|-----------|-------------|
| Process input | Subtype of `anydata`, must include `readonly` fields for correlation if using signals |
| Process return | Subtype of `anydata` or `error` |
| Activity params | Subtype of `anydata` |
| Activity return | Subtype of `anydata` or `error` |
| Signal futures | `future<T>` where `T` is subtype of `anydata` |
| Event data | Subtype of `anydata`, must include `id` field for correlation |

## Success Criteria

✅ **Compilation:**
- `@Process` functions compile with valid signatures
- `@Activity` functions compile with `anydata` parameters and return types
- `ctx->callActivity()` calls compile when targeting `@Activity` functions
- Direct activity calls produce WORKFLOW_108 compiler error
- Calls to non-activity functions via `callActivity()` produce WORKFLOW_107 error

✅ **Runtime Behavior:**
- `createInstance()` successfully starts workflows and returns workflow ID
- `sendEvent()` successfully sends signals to running workflows
- `ctx->callActivity()` executes activities and returns results
- Process functions execute deterministically (same inputs → same outputs)
- Activities execute exactly once (not repeated during replay)
- Signals are received and processed via `wait` keyword
- Workflows handle errors properly with Ballerina error semantics

✅ **Code Generation:**
- Compiler plugin auto-generates `registerProcess()` calls for each `@Process` function
- Generated code includes activity map for each process
- Build succeeds without manual registration code
