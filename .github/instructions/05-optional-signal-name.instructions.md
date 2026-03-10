# Explicit sendData and searchWorkflow API

applyTo: "**/SendEventValidatorTask.java,**/WorkflowNative.java"

---

## Overview

The `sendData()` function requires all parameters explicitly: the workflow function, the target workflow ID, the data name (matching an event field name), and the data payload. There is no signal name inference or automatic correlation-based routing.

For finding workflows by correlation keys, a separate `searchWorkflow()` function is provided. This clean separation gives users full control over workflow identification and data delivery.

## Current Implementation

### 1. Ballerina Layer

#### API Signatures
Defined in [functions.bal](ballerina/functions.bal):
- `sendData(function workflow, string workflowId, string dataName, anydata data) returns error?` — send data to a running workflow by ID


### 2. Compiler Plugin Layer

#### SendEventValidatorTask.java
Location: [SendEventValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/SendEventValidatorTask.java)

Minimal validation for `sendData()` calls. Since all parameters are required, the Ballerina type system enforces correct usage. The task exists as a placeholder for potential future validations (e.g., WORKFLOW_112 warning for ambiguous signal types).

### 3. Native Layer

#### WorkflowNative.java
Location: [WorkflowNative.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/nativeimpl/WorkflowNative.java)

- `sendData(Environment, BFunctionPointer, BString workflowId, BString dataName, Object data)` — converts data via `TypesUtil.convertBallerinaToJavaType()`, calls `WorkflowRuntime.getInstance().sendSignalToWorkflow()`


## Usage Patterns

### Direct Workflow ID (Most Common)
Start a workflow with `workflow:run()`, get the ID, then send data using the ID. See examples in [integration-tests/](integration-tests/).

### With Correlation-Based Lookup
When you don't have the workflow ID, use `workflow:searchWorkflow()` with correlation key values, then send data using the found ID. See examples in [integration-tests/](integration-tests/).

## Success Criteria

- All `sendData()` parameters are required — enforced by type system
- `searchWorkflow()` parameters are required — enforced by type system
- WORKFLOW_112 warning for ambiguous signal types (same structure) in workflow definition
- `sendData()` delivers data to the correct workflow by ID
- `searchWorkflow()` finds workflows by correlation key values
- Clear error when workflow ID is invalid or not found
- Clear error when no workflow matches correlation keys

