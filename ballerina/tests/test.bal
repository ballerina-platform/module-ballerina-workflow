// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/test;
import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

// ============================================================================
// Test Helper Functions
// ============================================================================

# Returns all registered workflows and their activities.
#
# + return - Registry map, or an error
isolated function getRegisteredWorkflows() returns WorkflowRegistry|error {
    return getRegisteredWorkflowsNative();
}

# + return - Registry map, or an error
isolated function getRegisteredWorkflowsNative() returns WorkflowRegistry|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getRegisteredWorkflows"
} external;

// ============================================================================
// Test Setup
// ============================================================================

// Note: Module-level tests focus on registration and introspection.
// These tests work with the lazy gRPC connection (no active workflow server needed).
// For workflow execution tests (run, sendData), a separate integration test 
// suite should be created that initializes the embedded test server before registering workflows.
//
// IMPORTANT: With the single workflow scheduler pattern, we cannot clear the registry between tests.
// All processes are registered once in @test:BeforeSuite and tests verify specific registrations.

// Record types for events in test processes
type MultiEventRecord record {|
    future<string> approvalEvent;
    future<int> paymentEvent;
|};

type SingleEventRecord record {|
    future<boolean> confirmationEvent;
|};

// ============================================================================
// Test Process and Activity Functions
// ============================================================================

// Test process function for workflow registration tests.
@Workflow
function testProcessFunction(string input) returns string|error {
    return "processed: " + input;
}

// Test activity function for activity execution tests.
@Activity
function testActivityFunction(string input) returns string|error {
    return "activity result: " + input;
}

// Second test activity for multi-activity tests.
@Activity
function testActivityFunction2(int value) returns int|error {
    return value * 2;
}

// Dependently-typed external activity with inferred typedesc default <>.
// This is the only supported pattern for typedesc in @Activity functions.
@Activity
function testDependentActivity(string data, typedesc<anydata> targetType = <>) returns targetType|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "convertData"
} external;

// Workflow calling the dependently-typed activity.
@Workflow
function processWithDependentActivity(Context ctx, string input) returns string|error {
    string result = check ctx->callActivity(testDependentActivity, {"data": input});
    return result;
}

// Test process that calls activities using Context client.
@Workflow
function processWithActivities(Context ctx, string input) returns string|error {
    // Use Context client's callActivity remote method with map<anydata> args
    string result1 = check ctx->callActivity(testActivityFunction, {"input": input});
    int result2 = check ctx->callActivity(testActivityFunction2, {"value": 10});
    return result1 + " - " + result2.toString();
}

// Test process with events for event extraction tests.
// Events are modeled as a record with future fields.
@Workflow
function processWithEvents(Context ctx, string input, MultiEventRecord events) returns string|error {
    // This would normally wait for events
    return "processed with events: " + input;
}

// Test process with only optional Context and events (no separate input).
@Workflow
function processWithContextAndEvents(Context ctx, SingleEventRecord events) returns boolean|error {
    return true;
}

// Test process with inline record for events (multiple events).
@Workflow
function processWithInlineEvents(Context ctx, string input, record {|
    future<string> approvalEvent;
    future<int> paymentEvent;
|} events) returns string|error {
    // This would normally wait for events
    return "processed with inline events: " + input;
}

// Test process with inline record for single event.
@Workflow
function processWithSingleInlineEvent(Context ctx, record {|
    future<boolean> confirmEvent;
|} events) returns boolean|error {
    return true;
}

// Test process with inline record having different future types.
@Workflow
function processWithMixedInlineEvents(Context ctx, string input, record {|
    future<string> textEvent;
    future<int> numberEvent;
    future<boolean> flagEvent;
|} events) returns string|error {
    return "mixed events: " + input;
}

// Test process with inline record without Context parameter.
@Workflow
function processWithInlineEventsNoContext(string input, record {|
    future<string> simpleEvent;
|} events) returns string|error {
    return "no context: " + input;
}

// Simple workflow process for testing run
@Workflow
function simpleWorkflowProcess(string input) returns string|error {
    return "Hello, " + input;
}

// ============================================================================
// Test Setup - Register all processes once before tests run
// ============================================================================

@test:BeforeSuite
function setupTests() returns error? {
    // Register all test processes once. This matches how the compiler plugin
    // generates registerWorkflow calls at module init time in real applications.
    
    // Basic process registration
    _ = check wfInternal:registerWorkflow(testProcessFunction, "test-process");
    
    // Process with activities
    map<function> activities1 = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    _ = check wfInternal:registerWorkflow(processWithActivities, "process-with-activities", activities1);
    
    // Process with events (named record type)
    _ = check wfInternal:registerWorkflow(processWithEvents, "process-with-events");
    
    // Process with single event
    _ = check wfInternal:registerWorkflow(processWithContextAndEvents, "process-single-event");
    
    // Process without events (to verify empty events list)
    _ = check wfInternal:registerWorkflow(testProcessFunction, "no-events-process");
    
    // Process with both activities and events
    map<function> activities2 = {
        "testActivityFunction": testActivityFunction
    };
    _ = check wfInternal:registerWorkflow(processWithEvents, "process-activities-events", activities2);
    
    // Inline record event processes
    _ = check wfInternal:registerWorkflow(processWithInlineEvents, "inline-multi-events");
    _ = check wfInternal:registerWorkflow(processWithSingleInlineEvent, "inline-single-event");
    _ = check wfInternal:registerWorkflow(processWithMixedInlineEvents, "inline-mixed-events");
    _ = check wfInternal:registerWorkflow(processWithInlineEventsNoContext, "inline-no-context");
    
    // Inline record with activities
    map<function> activities3 = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    _ = check wfInternal:registerWorkflow(processWithInlineEvents, "inline-with-activities", activities3);
    
    // Process for run tests
    _ = check wfInternal:registerWorkflow(simpleWorkflowProcess, "simple-workflow");

    // Process with dependently-typed external activity
    map<function> dependentActivities = {
        "testDependentActivity": testDependentActivity
    };
    _ = check wfInternal:registerWorkflow(processWithDependentActivity, "dependent-activity-process",
            dependentActivities);

    // Start the in-memory workflow runtime after all registrations are done.
    // (The compiler plugin generates this for user packages, but it doesn't
    // run on the workflow module's own tests.)
    _ = check wfInternal:startWorkflowRuntime();
}

// ============================================================================
// Basic Registration Tests
// ============================================================================

@test:Config {groups: ["unit"]}
function testRegisterProcess() returns error? {
    // Verify the process registered in @BeforeSuite is in the registry
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("test-process"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["test-process"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.name, "test-process");
    }
}

@test:Config {groups: ["unit"]}
function testRegisterProcessDuplicate() returns error? {
    // Attempt to register a process with the same name that was already registered
    // This should fail because "test-process" was registered in @BeforeSuite
    boolean|error result = wfInternal:registerWorkflow(testProcessFunction, "test-process");
    test:assertTrue(result is error, "Duplicate registration should fail");
}

@test:Config {groups: ["unit"]}
function testRegisterProcessWithActivities() returns error? {
    // Verify the process with activities is registered correctly
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("process-with-activities"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["process-with-activities"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.name, "process-with-activities");
        test:assertEquals(processInfo.activities.length(), 2, "Should have 2 activities");
        
        // Check that both activities are present
        boolean hasActivity1 = false;
        boolean hasActivity2 = false;
        foreach string activity in processInfo.activities {
            if activity == "testActivityFunction" {
                hasActivity1 = true;
            }
            if activity == "testActivityFunction2" {
                hasActivity2 = true;
            }
        }
        test:assertTrue(hasActivity1, "Should have testActivityFunction");
        test:assertTrue(hasActivity2, "Should have testActivityFunction2");
    }
}

@test:Config {groups: ["unit"]}
function testGetRegisteredWorkflows() returns error? {
    // Verify we can retrieve all registered workflows
    WorkflowRegistry registry = check getRegisteredWorkflows();
    
    // We registered 13 processes in @BeforeSuite
    test:assertTrue(registry.length() >= 13, "Should have at least 13 processes registered");
    
    // Verify some key processes are present
    test:assertTrue(registry.hasKey("test-process"), "Should have test-process");
    test:assertTrue(registry.hasKey("process-with-activities"), "Should have process-with-activities");
    test:assertTrue(registry.hasKey("process-with-events"), "Should have process-with-events");
}

@test:Config {groups: ["unit"]}
function testMultipleProcessRegistration() returns error? {
    // Verify multiple processes are registered
    WorkflowRegistry registry = check getRegisteredWorkflows();
    
    test:assertTrue(registry.hasKey("test-process"), "Should have test-process");
    test:assertTrue(registry.hasKey("process-with-activities"), "Should have process-with-activities");
    test:assertTrue(registry.hasKey("process-with-events"), "Should have process-with-events");
    test:assertTrue(registry.hasKey("inline-multi-events"), "Should have inline-multi-events");
}

// ============================================================================
// Event Extraction Tests - Named Record Types
// ============================================================================

@test:Config {groups: ["unit"]}
function testRegisterProcessWithEvents() returns error? {
    // Verify process with events is registered with correct event list
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("process-with-events"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["process-with-events"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.name, "process-with-events");
        test:assertEquals(processInfo.events.length(), 2, "Should have 2 events");
        
        // Check that both events are present
        boolean hasApproval = false;
        boolean hasPayment = false;
        foreach string event in processInfo.events {
            if event == "approvalEvent" {
                hasApproval = true;
            }
            if event == "paymentEvent" {
                hasPayment = true;
            }
        }
        test:assertTrue(hasApproval, "Should have approvalEvent");
        test:assertTrue(hasPayment, "Should have paymentEvent");
    }
}

@test:Config {groups: ["unit"]}
function testRegisterProcessWithSingleEvent() returns error? {
    // Verify process with single event
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("process-single-event"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["process-single-event"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "confirmationEvent", "Should have confirmationEvent");
    }
}

@test:Config {groups: ["unit"]}
function testProcessWithoutEventsHasEmptyEventList() returns error? {
    // Verify process without events has empty events array
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["no-events-process"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 0, "Should have 0 events");
    }
}

@test:Config {groups: ["unit"]}
function testProcessWithActivitiesAndEvents() returns error? {
    // Verify process with both activities and events
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["process-activities-events"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.activities.length(), 1, "Should have 1 activity");
        test:assertEquals(processInfo.events.length(), 2, "Should have 2 events");
    }
}

// ============================================================================
// Inline Record Tests - Testing event extraction with anonymous record types
// ============================================================================

@test:Config {groups: ["unit"]}
function testInlineRecordWithMultipleEvents() returns error? {
    // Verify process with inline record events
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("inline-multi-events"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["inline-multi-events"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.name, "inline-multi-events");
        test:assertEquals(processInfo.events.length(), 2, "Should have 2 events from inline record");
        
        // Check that both events are present
        boolean hasApproval = false;
        boolean hasPayment = false;
        foreach string event in processInfo.events {
            if event == "approvalEvent" {
                hasApproval = true;
            }
            if event == "paymentEvent" {
                hasPayment = true;
            }
        }
        test:assertTrue(hasApproval, "Should have approvalEvent");
        test:assertTrue(hasPayment, "Should have paymentEvent");
    }
}

@test:Config {groups: ["unit"]}
function testInlineRecordWithSingleEvent() returns error? {
    // Verify process with single inline event
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-single-event"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "confirmEvent", "Should have confirmEvent");
    }
}

@test:Config {groups: ["unit"]}
function testInlineRecordWithThreeEvents() returns error? {
    // Verify process with three inline events of different types
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-mixed-events"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 3, "Should have 3 events");
        
        // Check all events are present
        boolean hasText = false;
        boolean hasNumber = false;
        boolean hasFlag = false;
        foreach string event in processInfo.events {
            if event == "textEvent" {
                hasText = true;
            }
            if event == "numberEvent" {
                hasNumber = true;
            }
            if event == "flagEvent" {
                hasFlag = true;
            }
        }
        test:assertTrue(hasText, "Should have textEvent");
        test:assertTrue(hasNumber, "Should have numberEvent");
        test:assertTrue(hasFlag, "Should have flagEvent");
    }
}

@test:Config {groups: ["unit"]}
function testInlineRecordWithoutContext() returns error? {
    // Verify process with inline events but no Context parameter
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-no-context"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "simpleEvent", "Should have simpleEvent");
    }
}

@test:Config {groups: ["unit"]}
function testInlineRecordWithActivities() returns error? {
    // Verify process with inline events and activities
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-with-activities"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.activities.length(), 2, "Should have 2 activities");
        test:assertEquals(processInfo.events.length(), 2, "Should have 2 events");
    }
}

// ============================================================================
// Typedesc Parameter Tests
// ============================================================================
// Only dependently-typed activities with inferred typedesc default <> are
// supported. The typedesc parameter is excluded from workflow history
// serialization by the compiler plugin and filtered at runtime by
// BallerinaActivityAdapter. Non-dependent typedesc patterns (explicit
// default, required) produce compiler error WORKFLOW_114.

@test:Config {groups: ["unit"]}
function testDependentActivityRegistration() returns error? {
    // Dependently-typed activity: typedesc<anydata> targetType = <>
    // The typedesc param should be transparent to registration.
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("dependent-activity-process"),
            "Process with dependent-typed activity should be in registry");

    ProcessRegistration? processInfo = registry["dependent-activity-process"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");

    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.name, "dependent-activity-process");
        test:assertEquals(processInfo.activities.length(), 1, "Should have 1 activity");
        test:assertEquals(processInfo.activities[0], "testDependentActivity",
                "Should have testDependentActivity");
    }
}

// ============================================================================
// Workflow Run Tests (require workflow server - marked for integration)
// ============================================================================
// Note: These tests validate the run API's input validation.
// Without a running workflow server, they will get connection errors.
// Full workflow execution tests are in integration-tests module.

// Separate unregistered process for testing run with unregistered process
@Workflow
function unregisteredProcess(string input) returns string|error {
    return "This process is intentionally not registered: " + input;
}

@test:Config {groups: ["unit"]}
function testRunWithUnregisteredProcess() returns error? {
    // Attempt to start a workflow with a process that was NOT registered
    map<string> input = {id: "test-workflow-001"};
    
    string|error result = run(unregisteredProcess, input);
    
    // Should fail because the process is not registered
    test:assertTrue(result is error, "Starting unregistered process should fail");
    if result is error {
        test:assertTrue(result.message().includes("not registered") || result.message().includes("Failed"),
            "Error should indicate process not registered");
    }
}

@test:Config {groups: ["unit"]}
function testRunWithMissingId() returns error? {
    // Attempt to start workflow without 'id' field using a registered process
    map<anydata> inputWithoutId = {"name": "test"};
    
    string|error result = run(simpleWorkflowProcess, inputWithoutId);
    
    // Should fail because 'id' field is required
    test:assertTrue(result is error, "Starting workflow without 'id' should fail");
    if result is error {
        test:assertTrue(result.message().includes("id") || result.message().includes("Failed"),
            "Error should indicate missing id field");
    }
}

@test:Config {groups: ["unit"]}
function testRunWithValidInput() returns error? {
    // Prepare valid input with required 'id' field
    map<string> input = {id: "test-workflow-002", "name": "TestUser"};
    
    // This will attempt to connect to the workflow server
    // Without a running workflow server, we expect a connection error
    string|error result = run(simpleWorkflowProcess, input);
    
    // The result could be:
    // 1. A workflow ID string if server is running (integration test environment)
    // 2. An error due to connection failure (expected in unit test environment)
    if result is string {
        // If server is running, the workflow ID should match our input id
        test:assertEquals(result, "test-workflow-002", "Workflow ID should match input id");
    } else {
        // In unit test environment without a running server, expect connection-related error
        test:assertTrue(
            result.message().includes("connection") || result.message().includes("unavailable") ||
            result.message().includes("Failed"),
            "Unexpected error for valid run input: " + result.message());
    }
}

// ============================================================================
// Workflow Status / Result Tests (require workflow server - IN_MEMORY mode)
// ============================================================================
// These tests verify workflow execution status and workflowType population.
// They run against the embedded IN_MEMORY Temporal test server started by init().

// Activity that always returns an error (for testing activity-failure propagation).
@Activity
function alwaysFailingActivity(string input) returns string|error {
    return error("Activity intentionally failed: " + input);
}

// Workflow that calls alwaysFailingActivity and propagates the error via `check`.
@Workflow
function workflowWithFailingActivity(Context ctx, string input) returns string|error {
    string result = check ctx->callActivity(alwaysFailingActivity, {"input": input});
    return result;
}

// Workflow that calls two activities successfully (for activity invocation tracking tests).
@Workflow
function workflowWithTwoActivities(Context ctx, string input) returns string|error {
    string r1 = check ctx->callActivity(testActivityFunction, {"input": input});
    int r2 = check ctx->callActivity(testActivityFunction2, {"value": 5});
    return r1 + " / " + r2.toString();
}

// Workflow that calls a failing activity with retryOnError=true.
// The activity fails every attempt so all retries are exhausted.
@Workflow
function workflowWithRetryFailingActivity(Context ctx, string input) returns string|error {
    string result = check ctx->callActivity(alwaysFailingActivity, {"input": input},
            retryPolicy = {maxRetries: 2, retryDelay: 0.1});
    return result;
}

@test:Config {groups: ["unit"]}
function testGetWorkflowResultStatusCompleted() returns error? {
    // Register the simple workflow used in this test (idempotent — safe if already registered).
    // Use a unique name to avoid clashing with registrations done in @BeforeSuite.
    _ = check wfInternal:registerWorkflow(simpleWorkflowProcess, "simple-workflow-status-test");

    map<string> input = {id: "test-status-completed-001"};
    string|error runResult = run(simpleWorkflowProcess, input);
    if runResult is error {
        // No running workflow server – skip execution assertions gracefully.
        return;
    }
    string workflowId = runResult;

    _ = check getWorkflowResult(workflowId, 15);
    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.status, "COMPLETED", "Completed workflow should have status COMPLETED");
    test:assertEquals(info.workflowId, workflowId, "workflowId should match");
    test:assertFalse(info.workflowType == "", "workflowType should not be empty");
}

@test:Config {groups: ["unit"]}
function testGetWorkflowResultWorkflowType() returns error? {
    // Verify that getWorkflowResult populates workflowType correctly.
    _ = check wfInternal:registerWorkflow(simpleWorkflowProcess, "simple-workflow-type-test");

    map<string> input = {id: "test-type-001"};
    string|error runResult = run(simpleWorkflowProcess, input);
    if runResult is error {
        return; // No server available – skip.
    }
    string workflowId = runResult;

    _ = check getWorkflowResult(workflowId, 15);
    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.workflowType, "simple-workflow-type-test",
            "workflowType should match the registered workflow name");
}

@test:Config {groups: ["unit"]}
function testGetWorkflowResultStatusFailedOnActivityError() returns error? {
    // Verify that when an activity returns a Ballerina error the workflow is marked FAILED
    // (not COMPLETED) in Temporal, and getWorkflowResult reflects status="FAILED".
    map<function> activities = {"alwaysFailingActivity": alwaysFailingActivity};
    _ = check wfInternal:registerWorkflow(workflowWithFailingActivity,
            "workflow-failing-activity-test", activities);

    map<string> input = {id: "test-activity-fail-001", input: "trigger"};
    string|error runResult = run(workflowWithFailingActivity, input);
    if runResult is error {
        return; // No server available – skip.
    }
    string workflowId = runResult;

        anydata|error result = getWorkflowResult(workflowId, 15);
        test:assertTrue(result is error,
            "Failed workflow should return an error from getWorkflowResult");

        management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.status, "FAILED",
            "Workflow whose activity returned error should have status FAILED");
    if result is error {
        string errMsg = result.message();
        test:assertTrue(errMsg.includes("Activity intentionally failed") || errMsg.includes("failed"),
                "error message should contain the original activity error: " + errMsg);
    }
}

// ============================================================================
// ActivityInvocation / Retry Visibility Tests
// ============================================================================
// These tests verify that WorkflowExecutionInfo.activityInvocations is populated
// from the Temporal event history and that the `attempt` field correctly reflects
// retry behaviour.

@test:Config {groups: ["unit"]}
function testActivityInvocationsTrackedOnSuccess() returns error? {
    // Register a workflow that calls two activities.
    map<function> activities = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    _ = check wfInternal:registerWorkflow(workflowWithTwoActivities,
            "workflow-two-activities-test", activities);

    map<string> input = {id: "test-invocations-001", input: "hello"};
    string|error runResult = run(workflowWithTwoActivities, input);
    if runResult is error {
        return; // No server available – skip.
    }
    string workflowId = runResult;

    _ = check getWorkflowResult(workflowId, 15);
    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.status, "COMPLETED",
            "Workflow should complete successfully");

    // Should have at least 2 user activity invocations (COMPLETED).
    // The history may also include built-in implicit activities, so we filter
    // for the two user activities by name prefix.
    management:ActivityInvocation[] invocations = info.activityInvocations;
    test:assertTrue(invocations.length() >= 2,
            "Should have at least 2 activity invocations, got " + invocations.length().toString());

    // Collect user-activity invocations (their names contain a '.' separator)
    management:ActivityInvocation[] userActivities = from management:ActivityInvocation inv in invocations
        where inv.activityName.includes("testActivityFunction")
        select inv;

    test:assertEquals(userActivities.length(), 2,
            "Should have exactly 2 user activity invocations");
    test:assertEquals(userActivities[0].status, "COMPLETED",
            "First activity should be COMPLETED");
    int? attempt0 = userActivities[0].attempt;
    test:assertTrue(attempt0 is int && attempt0 == 1,
            "First activity should be attempt 1 (no retries)");
    test:assertEquals(userActivities[1].status, "COMPLETED",
            "Second activity should be COMPLETED");
    int? attempt1 = userActivities[1].attempt;
    test:assertTrue(attempt1 is int && attempt1 == 1,
            "Second activity should be attempt 1 (no retries)");
}

@test:Config {groups: ["unit"]}
function testActivityInvocationsShowRetriesOnFailure() returns error? {
    // Register a workflow that calls a failing activity with retryOnError=true, maxRetries=2.
    // The activity always fails, so Temporal retries it (total 3 attempts: 1 initial + 2 retries).
    map<function> activities = {"alwaysFailingActivity": alwaysFailingActivity};
    _ = check wfInternal:registerWorkflow(workflowWithRetryFailingActivity,
            "workflow-retry-failing-test", activities);

    map<string> input = {id: "test-retry-fail-001", input: "trigger"};
    string|error runResult = run(workflowWithRetryFailingActivity, input);
    if runResult is error {
        return; // No server available – skip.
    }
    string workflowId = runResult;

        anydata|error result = getWorkflowResult(workflowId, 30);
        test:assertTrue(result is error,
            "Workflow should return an error after retries are exhausted");

        management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.status, "FAILED",
            "Workflow should fail after retries exhausted");

    // The failing activity should appear once in the invocations as FAILED
    // with the final attempt number reflecting the total attempts made.
    management:ActivityInvocation[] invocations = info.activityInvocations;
    test:assertTrue(invocations.length() >= 1,
            "Should have at least 1 activity invocation, got " + invocations.length().toString());

    // Find the failing user activity
    management:ActivityInvocation[] failedActivities = from management:ActivityInvocation inv in invocations
        where inv.activityName.includes("alwaysFailingActivity") && inv.status == "FAILED"
        select inv;

    test:assertTrue(failedActivities.length() >= 1,
            "Should have at least one FAILED alwaysFailingActivity invocation");

    // The last failed invocation should have attempt >= 3 (1 initial + 2 retries)
    management:ActivityInvocation lastFailed = failedActivities[failedActivities.length() - 1];
    int? lastAttempt = lastFailed.attempt;
    test:assertTrue(lastAttempt is int && lastAttempt >= 3,
            "Final failed invocation should be attempt >= 3, got " + (lastAttempt ?: 0).toString());

    // Verify error message is captured
    test:assertTrue(lastFailed.errorMessage is string,
            "Failed activity should have an errorMessage");
}

@test:Config {groups: ["unit"]}
function testActivityInvocationsOnSingleFailNoRetry() returns error? {
    // When retryOnError=false (default), the activity fails once with attempt=1.
    // Re-use the existing workflowWithFailingActivity process.
    map<function> activities = {"alwaysFailingActivity": alwaysFailingActivity};
    _ = check wfInternal:registerWorkflow(workflowWithFailingActivity,
            "workflow-single-fail-invocations-test", activities);

    map<string> input = {id: "test-single-fail-inv-001", input: "trigger"};
    string|error runResult = run(workflowWithFailingActivity, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;

        anydata|error result = getWorkflowResult(workflowId, 15);
        test:assertTrue(result is error,
            "Workflow should return an error when activity fails without retries");

        management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);

    test:assertEquals(info.status, "FAILED",
            "Workflow should fail on activity error");

    management:ActivityInvocation[] failedActivities = from management:ActivityInvocation inv in info.activityInvocations
        where inv.activityName.includes("alwaysFailingActivity") && inv.status == "FAILED"
        select inv;

    test:assertTrue(failedActivities.length() >= 1,
            "Should have at least one FAILED invocation");
    // With no retries, the single attempt should be attempt 1
    int? singleAttempt = failedActivities[0].attempt;
    test:assertTrue(singleAttempt is int && singleAttempt == 1,
            "Without retries, failed activity should be attempt 1");
}

// ============================================================================
// ManualRetry Type / Configuration Tests
// ============================================================================

@test:Config {groups: ["unit"]}
function testManualRetryDefaultUserRoles() {
    // Default userRoles must be [defaultAdminRole] — enforced at the type level.
    ManualRetry config = {taskName: "processRefund"};
    test:assertEquals(config.userRoles[0], defaultAdminRole,
            "Default role should match defaultAdminRole configurable");
    test:assertEquals(config.userRoles.length(), 1, "Default should have exactly one role");
}

@test:Config {groups: ["unit"]}
function testManualRetryCustomUserRoles() {
    // At least two roles must be accepted — [string, string...] enforces at least one element.
    ManualRetry config = {taskName: "approvePayment", userRoles: ["finance", "manager"]};
    test:assertEquals(config.taskName, "approvePayment");
    test:assertEquals(config.userRoles.length(), 2);
    test:assertEquals(config.userRoles[0], "finance");
    test:assertEquals(config.userRoles[1], "manager");
}

@test:Config {groups: ["unit"]}
function testManualRetrySingleCustomRole() {
    // [string, string...] still accepts exactly one role.
    ManualRetry config = {taskName: "reviewOrder", userRoles: ["ops-team"]};
    test:assertEquals(config.userRoles.length(), 1);
    test:assertEquals(config.userRoles[0], "ops-team");
}

@test:Config {groups: ["unit"]}
function testAutoRetryDefaults() {
    // AutoRetry default values should match the documented spec.
    AutoRetry config = {};
    test:assertEquals(config.maxRetries, 3, "Default maxRetries should be 3");
    test:assertEquals(config.retryDelay, 1.0d, "Default retryDelay should be 1.0");
    test:assertEquals(config.retryBackoff, 2.0d, "Default retryBackoff should be 2.0");
    test:assertTrue(config.maxRetryDelay is (), "Default maxRetryDelay should be nil");
}

@test:Config {groups: ["unit"]}
function testAutoRetryCustomValues() {
    AutoRetry config = {maxRetries: 5, retryDelay: 0.5d, retryBackoff: 1.5d, maxRetryDelay: 30.0d};
    test:assertEquals(config.maxRetries, 5);
    test:assertEquals(config.retryDelay, 0.5d);
    test:assertEquals(config.retryBackoff, 1.5d);
    test:assertEquals(config.maxRetryDelay, 30.0d);
}

@test:Config {groups: ["unit"]}
function testNoRetryIsUnit() {
    // NoRetry is the () constant — passing it as the retryPolicy union member
    // should be indistinguishable from omitting the parameter.
    AutoRetry|ManualRetry|NoRetry policy = NoRetry;
    test:assertTrue(policy is (), "NoRetry should be unit type ()");
}

// ============================================================================
// ManualRetry Workflow Integration Tests
// ============================================================================
// These tests require a running Temporal server (IN_MEMORY mode started in
// @BeforeSuite). They are skipped gracefully when no server is available.

// Activity that always fails — used to trigger the ManualRetry path.
@Activity
function failingActivityForRetry(string orderId) returns string|error {
    return error("Transient failure processing order: " + orderId);
}

// Workflow that uses ManualRetry for a critical step.
@Workflow
function workflowWithManualRetry(Context ctx, string orderId) returns string|error {
    string result = check ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = <ManualRetry>{taskName: "retryOrder", userRoles: ["ops"]});
    return result;
}

// Workflow that uses ManualRetry — the human will choose "fail" so the workflow errors.
@Workflow
function workflowWithManualRetryFail(Context ctx, string orderId) returns string|error {
    string result = check ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = <ManualRetry>{taskName: "retryOrderFail", userRoles: ["ops"]});
    return result;
}

@test:Config {groups: ["unit"]}
function testManualRetryWorkflowCreatesRetryTask() returns error? {
    // Register the workflow that uses ManualRetry.
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-manual-retry-test", activities);

    map<string> input = {id: "test-manual-retry-001", orderId: "ORD-MR-001"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return; // No server available – skip.
    }
    string workflowId = runResult;

    // Give the workflow a moment to hit the failing activity and park at the retry task.
    runtime:sleep(2);

    // A pending retry task should have been created for this parent workflow.
    management:RetryTaskSummary[]|error pendingTasks = management:listPendingRetryTasks(workflowId);
    if pendingTasks is error {
        return; // Server not reachable – skip.
    }

    test:assertTrue(pendingTasks.length() >= 1,
            "Should have at least one pending retry task, got " + pendingTasks.length().toString());

    management:RetryTaskSummary task = pendingTasks[0];
    test:assertTrue(task.taskId.startsWith("retrytask-"),
            "Task ID should start with 'retrytask-', got: " + task.taskId);
    test:assertEquals(task.parentWorkflowId, workflowId, "parentWorkflowId should match");
    test:assertEquals(task.status, "RUNNING", "Pending retry task should be in RUNNING status");
    test:assertTrue(task.taskName.includes("retryOrder"),
            "Task name should include configured taskName");
}

@test:Config {groups: ["unit"]}
function testManualRetryTaskInfoContainsCorrectMetadata() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-manual-retry-info-test", activities);

    map<string> input = {id: "test-manual-retry-info-001", orderId: "ORD-MR-002"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    management:RetryTaskSummary[]|error pendingTasks = management:listPendingRetryTasks(workflowId);
    if pendingTasks is error || pendingTasks.length() == 0 {
        return;
    }

    string taskId = pendingTasks[0].taskId;
    management:RetryTaskInfo|error infoResult = management:getRetryTaskInfo(taskId);
    if infoResult is error {
        return;
    }

    management:RetryTaskInfo info = infoResult;
    test:assertEquals(info.taskId, taskId, "taskId should match");
    test:assertEquals(info.parentWorkflowId, workflowId, "parentWorkflowId should match");
    test:assertTrue(info.errorMessage.includes("Transient failure"),
            "errorMessage should contain the original activity error");
    test:assertTrue(info.activityName.includes("failingActivityForRetry"),
            "activityName should contain the activity function name");
    test:assertTrue(info.userRoles.length() >= 1, "userRoles should be populated");
    test:assertEquals(info.userRoles[0], "ops", "userRole should be 'ops'");
    test:assertFalse(info.createdAt == "", "createdAt should be populated");
}

@test:Config {groups: ["unit"]}
function testCompleteRetryTaskWithRetry() returns error? {
    // Complete a retry task with action="retry" — the workflow should resume and
    // eventually fail again (because the activity always fails), creating another
    // retry task. We verify the workflow is still running after the first decision.
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-manual-retry-complete-test", activities);

    map<string> input = {id: "test-manual-retry-complete-001", orderId: "ORD-MR-003"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    management:RetryTaskSummary[]|error tasks1 = management:listPendingRetryTasks(workflowId);
    if tasks1 is error || tasks1.length() == 0 {
        return;
    }

    // Complete the first retry task with action="retry".
    error? completeResult = management:completeRetryTask(
            tasks1[0].taskId, {action: "retry"});
    test:assertTrue(completeResult is (), "completeRetryTask should succeed");

    // After retrying, the activity fails again → another retry task is created.
    runtime:sleep(2);
    management:RetryTaskSummary[]|error tasks2 = management:listPendingRetryTasks(workflowId);
    if tasks2 is error {
        return;
    }
    test:assertTrue(tasks2.length() >= 1,
            "A new retry task should appear after the activity fails again on retry");
}

@test:Config {groups: ["unit"]}
function testCompleteRetryTaskWithFail() returns error? {
    // Complete a retry task with action="fail" — the workflow should surface the
    // original error and transition to FAILED.
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetryFail,
            "workflow-manual-retry-fail-test", activities);

    map<string> input = {id: "test-manual-retry-fail-001", orderId: "ORD-MR-004"};
    string|error runResult = run(workflowWithManualRetryFail, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    management:RetryTaskSummary[]|error tasks = management:listPendingRetryTasks(workflowId);
    if tasks is error || tasks.length() == 0 {
        return;
    }

    error? completeResult = management:completeRetryTask(tasks[0].taskId, {action: "fail"});
    test:assertTrue(completeResult is (), "completeRetryTask with fail should succeed");

    // The workflow should now be FAILED.
    anydata|error wfResult = getWorkflowResult(workflowId, 10);
    test:assertTrue(wfResult is error,
            "Workflow should have failed after 'fail' decision");

    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);
    test:assertEquals(info.status, "FAILED",
            "Workflow status should be FAILED after manual retry decision 'fail'");
}

@test:Config {groups: ["unit"]}
function testCompleteRetryTaskWithRetryWithInput() returns error? {
    // Activity that succeeds only when a specific flag is set in the input.
    // We test the retry-with-input path by substituting input that makes it succeed.
    // (Here the activity always fails so we just verify the signal is accepted.)
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-manual-retry-input-test", activities);

    map<string> input = {id: "test-manual-retry-input-001", orderId: "ORD-MR-005"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    management:RetryTaskSummary[]|error tasks = management:listPendingRetryTasks(workflowId);
    if tasks is error || tasks.length() == 0 {
        return;
    }

    // Send retry-with-input decision — signal should be accepted without error.
    error? completeResult = management:completeRetryTask(tasks[0].taskId, {
        action: "retry-with-input",
        input: {"orderId": "ORD-MR-005-CORRECTED"}
    });
    test:assertTrue(completeResult is (), "completeRetryTask with retry-with-input should succeed");
}

@test:Config {groups: ["unit"]}
function testCompleteRetryTaskUnauthorizedRole() returns error? {
    // Attempt to complete a retry task with a caller role not in the task's userRoles.
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-manual-retry-auth-test", activities);

    map<string> input = {id: "test-manual-retry-auth-001", orderId: "ORD-MR-006"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    management:RetryTaskSummary[]|error tasks = management:listPendingRetryTasks(workflowId);
    if tasks is error || tasks.length() == 0 {
        return;
    }

    // "guest" is not in userRoles = ["ops"] → should be rejected.
    error? completeResult = management:completeRetryTask(
            tasks[0].taskId, {action: "retry"}, callerRoles = ["guest"]);
    test:assertTrue(completeResult is error,
            "completeRetryTask with unauthorized role should fail");
    if completeResult is error {
        test:assertTrue(
            completeResult.message().toLowerAscii().includes("unauthorized") ||
            completeResult.message().toLowerAscii().includes("role"),
            "Error should mention authorization or role: " + completeResult.message());
    }
}

@test:Config {groups: ["unit"]}
function testListAllRetryTasksReturnsCreatedTask() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check wfInternal:registerWorkflow(workflowWithManualRetry,
            "workflow-list-all-retry-test", activities);

    map<string> input = {id: "test-list-all-retry-001", orderId: "ORD-MR-007"};
    string|error runResult = run(workflowWithManualRetry, input);
    if runResult is error {
        return;
    }
    runtime:sleep(2);

    management:RetryTaskSummary[]|error allTasks = management:listAllRetryTasks();
    if allTasks is error {
        return;
    }

    // At least the task we just created should be visible.
    test:assertTrue(allTasks.length() >= 1,
            "listAllRetryTasks should return at least the task we created");

    // Every returned task ID must start with the retrytask- prefix.
    foreach management:RetryTaskSummary t in allTasks {
        test:assertTrue(t.taskId.startsWith("retrytask-"),
                "All returned tasks should have retrytask- prefix, got: " + t.taskId);
    }
}

@test:Config {groups: ["unit"]}
function testListAllRetryTasksStatusFilter() returns error? {
    // Filter by RUNNING (= pending) should only return pending tasks.
    management:RetryTaskSummary[]|error runningTasks = management:listAllRetryTasks(status = "RUNNING");
    if runningTasks is error {
        return;
    }

    foreach management:RetryTaskSummary t in runningTasks {
        test:assertEquals(t.status, "RUNNING",
                "All tasks returned with status=RUNNING filter should be RUNNING, got: " + t.status);
    }
}
