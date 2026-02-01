# Ballerina Workflow Module - AI Coding Instructions

## Project Overview
A Ballerina standard library module providing durable workflow orchestration via Temporal SDK. The module uses a **singleton worker pattern** with a compiler plugin that transforms annotated functions.

## Architecture

### Module Structure
- `ballerina/` - Core Ballerina module (types, annotations, context, public API)
- `native/` - Java native implementation (Temporal SDK integration, worker management)
- `native-test/` - Embedded Temporal test server for integration tests
- `compiler-plugin/` - Validates `@Process`/`@Activity` annotations, transforms activity calls
- `compiler-plugin-tests/` - Compiler plugin test suite

### Key Design Patterns

**Dynamic Workflow/Activity Adapters**: All workflows route through `BallerinaWorkflowAdapter` (implements `DynamicWorkflow`), all activities through `BallerinaActivityAdapter` (implements `DynamicActivity`). See [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java).

**Singleton Worker**: One Temporal SDK instance per JVM, initialized at module load via configurable variables. No Listener pattern - use `registerProcess()` + `startWorker()`.

**Context Client Class**: `workflow:Context` is a client class with `callActivity` as a remote method. Users **must** call activities via `ctx->callActivity(activityFunc, args...)`. Direct activity function calls are not allowed.

**Compiler Plugin Validation**: The plugin at [WorkflowCompilerPlugin.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCompilerPlugin.java) performs validation:
1. Validates that `ctx->callActivity()` calls use functions with `@Activity` annotation (produces `WORKFLOW_107` error otherwise)
2. Validates that `@Activity` functions are not called directly inside `@Process` functions (produces `WORKFLOW_108` error)
3. Auto-generates `registerProcess()` call at module level for each `@Process` function

## Key Conventions

### Process Function Signature
```ballerina
@workflow:Process
function processName(
    workflow:Context ctx,           // Required for calling activities
    T input,                        // Input data (anydata subtype)
    record { future<U> event1; future<V> event2; } events  // Optional event futures
) returns R|error { }
```

- **Context** (`workflow:Context`): Client class as first parameter. **Required** if calling activities. Provides `callActivity` remote method.
- **Input**: Workflow input data, must include `id` field for correlation
- **Events**: Optional record with `future<T>` fields for receiving signals. Wait using `check events.event1`

### Activity Functions
```ballerina
@workflow:Activity
function sendEmailActivity(string email) returns boolean|error {
    // I/O operations, external API calls, database access
}
```
- Parameters and return types must be `anydata` subtypes
- Activities are non-deterministic - executed once, results cached during replay
- **Must** be called via `ctx->callActivity()` within `@Process` functions (direct calls produce `WORKFLOW_108` error)

### Calling Activities
Activities **must** be called using `ctx->callActivity()` within `@Process` functions:

```ballerina
@workflow:Process
function orderProcess(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // Call activity using Context client remote method
    string result = check ctx->callActivity(sendEmailActivity, input.email);
    return {status: result};
}
```

**Important**: Direct activity function calls are **not allowed** and will produce a compiler error:
```ballerina
// ERROR: WORKFLOW_108 - Direct calls to @Activity functions are not allowed
string result = check sendEmailActivity(input.email);  // ❌ Not allowed
```

The compiler plugin validates:
1. All `ctx->callActivity()` calls use functions with `@Activity` annotation
2. No direct calls to `@Activity` functions inside `@Process` functions

### Error Handling
Temporal errors are valid Ballerina errors. Use standard error handling:
```ballerina
@workflow:Process
function processWithErrorHandling(workflow:Context ctx, Input input) returns Output|error {
    Output|error result = ctx->callActivity(riskyActivity, input.data);
    if result is error {
        // Handle activity failure - workflow can retry, compensate, or fail
        return error("Activity failed", result);
    }
    return result;
}
```

### Type Mappings (Ballerina ↔ Java ↔ Temporal)
- `map<anydata>` → `BMap<BString, Object>` (use `PredefinedTypes.TYPE_ANYDATA`)
- `function` → `BFunctionPointer` / `FPValue` (set `StrandMetadata` before calling)
- Workflow inputs must include `id` field for correlation

### Native Code Patterns
When calling Ballerina methods from Java:
```java
Object result = ballerinaRuntime.callMethod(
    serviceObject, "execute",
    new StrandMetadata(true, Collections.emptyMap()), args);
```

For activity functions use `FPValue`:
```java
FPValue fpValue = (FPValue) activityFunction;
fpValue.metadata = new StrandMetadata(true, fpValue.metadata.properties());
```

## Build & Test Commands

```bash
# Full build
./gradlew build

# Run unit tests only (no Temporal server needed)
./gradlew :workflow-ballerina:test

# Run integration tests (starts embedded Temporal server on port 7231)
./gradlew :workflow-integration-tests:test

# Run compiler plugin tests
./gradlew :workflow-compiler-plugin-tests:test

# Build specific modules
./gradlew :workflow-native:build
./gradlew :workflow-compiler-plugin:build

# Update Ballerina.toml with version placeholders
./gradlew :workflow-ballerina:updateTomlFiles
```

### Test Infrastructure

**Module Structure:**
- `ballerina/tests/` - Unit tests (registration, introspection, no Temporal server needed)
- `integration-tests/tests/` - Integration tests with actual Temporal workflow execution
- `compiler-plugin-tests/` - Compiler plugin validation tests

**Integration Tests** use an embedded Temporal server managed by Gradle:
1. `startTestServer` task launches shadow JAR from `native-test/` on port **7231**
2. Writes `tests/Config.toml` with server URL
3. Ballerina tests connect via configurable
4. `stopTestServer` runs on completion (even on failure via `buildFinished` listener)

## Configuration

Via `Config.toml` or programmatic defaults:
```toml
[ballerina.workflow.workflowConfig]
provider = "TEMPORAL"
url = "localhost:7231"
namespace = "default"

[ballerina.workflow.workflowConfig.params]
taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE"
maxConcurrentWorkflows = 100
```

## Version Requirements
- **Ballerina**: 2201.13.0
- **Java**: 21
- **Temporal SDK**: 1.32.0 (with matching gRPC 1.58.1)

## Common Pitfalls
- Always clear registry in test `@BeforeEach` with `clearRegistry()` to avoid state leakage
- Process functions must be deterministic - no I/O, use activities instead
- The `id` field in workflow input is mandatory for correlation
- Don't mix Listener pattern (deprecated) with singleton pattern
- **Never use Java blocking calls** in workflow code (causes `PotentialDeadlockException`)
- Signal waiting uses `TemporalFutureValue.getAndSetWaited()` to intercept Ballerina's `wait` and use `Workflow.await()` instead of blocking `CompletableFuture.get()`
