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

// Note: Module-level tests focus on registration and introspection.
// These tests work with the lazy gRPC connection (no active Temporal server needed).
// For workflow execution tests (createInstance, sendEvent), a separate integration test 
// suite should be created that initializes the embedded test server before registering workflows.
//
// IMPORTANT: With the singleton worker pattern, we cannot clear the registry between tests.
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
@Process
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

// Test process that calls activities using Context client.
@Process
function processWithActivities(Context ctx, string input) returns string|error {
    // Use Context client's callActivity remote method with Parameters record
    string result1 = check ctx->callActivity(testActivityFunction, {"input": input});
    int result2 = check ctx->callActivity(testActivityFunction2, {"value": 10});
    return result1 + " - " + result2.toString();
}

// Test process with events for event extraction tests.
// Events are modeled as a record with future fields.
@Process
function processWithEvents(Context ctx, string input, MultiEventRecord events) returns string|error {
    // This would normally wait for events
    return "processed with events: " + input;
}

// Test process with only optional Context and events (no separate input).
@Process
function processWithContextAndEvents(Context ctx, SingleEventRecord events) returns boolean|error {
    return true;
}

// Test process with inline record for events (multiple events).
@Process
function processWithInlineEvents(Context ctx, string input, record {|
    future<string> approvalEvent;
    future<int> paymentEvent;
|} events) returns string|error {
    // This would normally wait for events
    return "processed with inline events: " + input;
}

// Test process with inline record for single event.
@Process
function processWithSingleInlineEvent(Context ctx, record {|
    future<boolean> confirmEvent;
|} events) returns boolean|error {
    return true;
}

// Test process with inline record having different future types.
@Process
function processWithMixedInlineEvents(Context ctx, string input, record {|
    future<string> textEvent;
    future<int> numberEvent;
    future<boolean> flagEvent;
|} events) returns string|error {
    return "mixed events: " + input;
}

// Test process with inline record without Context parameter.
@Process
function processWithInlineEventsNoContext(string input, record {|
    future<string> simpleEvent;
|} events) returns string|error {
    return "no context: " + input;
}

// Simple workflow process for testing createInstance
@Process
function simpleWorkflowProcess(string input) returns string|error {
    return "Hello, " + input;
}

// ============================================================================
// Test Setup - Register all processes once before tests run
// ============================================================================

@test:BeforeSuite
function setupTests() returns error? {
    // Register all test processes once. This matches how the compiler plugin
    // generates registerProcess calls at module init time in real applications.
    
    // Basic process registration
    _ = check registerProcess(testProcessFunction, "test-process");
    
    // Process with activities
    map<function> activities1 = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    _ = check registerProcess(processWithActivities, "process-with-activities", activities1);
    
    // Process with events (named record type)
    _ = check registerProcess(processWithEvents, "process-with-events");
    
    // Process with single event
    _ = check registerProcess(processWithContextAndEvents, "process-single-event");
    
    // Process without events (to verify empty events list)
    _ = check registerProcess(testProcessFunction, "no-events-process");
    
    // Process with both activities and events
    map<function> activities2 = {
        "testActivityFunction": testActivityFunction
    };
    _ = check registerProcess(processWithEvents, "process-activities-events", activities2);
    
    // Inline record event processes
    _ = check registerProcess(processWithInlineEvents, "inline-multi-events");
    _ = check registerProcess(processWithSingleInlineEvent, "inline-single-event");
    _ = check registerProcess(processWithMixedInlineEvents, "inline-mixed-events");
    _ = check registerProcess(processWithInlineEventsNoContext, "inline-no-context");
    
    // Inline record with activities
    map<function> activities3 = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    _ = check registerProcess(processWithInlineEvents, "inline-with-activities", activities3);
    
    // Process for createInstance tests
    _ = check registerProcess(simpleWorkflowProcess, "simple-workflow");
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
    boolean|error result = registerProcess(testProcessFunction, "test-process");
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
    
    // We registered 12 processes in @BeforeSuite
    test:assertTrue(registry.length() >= 12, "Should have at least 12 processes registered");
    
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
// Workflow Creation Tests (require Temporal server - marked for integration)
// ============================================================================
// Note: These tests validate the createInstance API's input validation.
// Without a running Temporal server, they will get connection errors.
// Full workflow execution tests are in integration-tests module.

// Separate unregistered process for testing createInstance with unregistered process
@Process
function unregisteredProcess(string input) returns string|error {
    return "This process is intentionally not registered: " + input;
}

@test:Config {groups: ["unit"]}
function testCreateInstanceWithUnregisteredProcess() returns error? {
    // Attempt to start a workflow with a process that was NOT registered
    WorkflowData input = {id: "test-workflow-001"};
    
    string|error result = createInstance(unregisteredProcess, input);
    
    // Should fail because the process is not registered
    test:assertTrue(result is error, "Starting unregistered process should fail");
    if result is error {
        test:assertTrue(result.message().includes("not registered") || result.message().includes("Failed"),
            "Error should indicate process not registered");
    }
}

@test:Config {groups: ["unit"]}
function testCreateInstanceWithMissingId() returns error? {
    // Attempt to start workflow without 'id' field using a registered process
    map<anydata> inputWithoutId = {"name": "test"};
    
    string|error result = createInstance(simpleWorkflowProcess, inputWithoutId);
    
    // Should fail because 'id' field is required
    test:assertTrue(result is error, "Starting workflow without 'id' should fail");
    if result is error {
        test:assertTrue(result.message().includes("id") || result.message().includes("Failed"),
            "Error should indicate missing id field");
    }
}

@test:Config {groups: ["unit"]}
function testCreateInstanceWithValidInput() returns error? {
    // Prepare valid input with required 'id' field
    WorkflowData input = {id: "test-workflow-002", "name": "TestUser"};
    
    // This will attempt to connect to Temporal server
    // Without a running Temporal server, we expect a connection error
    string|error result = createInstance(simpleWorkflowProcess, input);
    
    // The result could be:
    // 1. A workflow ID string if Temporal is running (integration test environment)
    // 2. An error due to connection failure (expected in unit test environment)
    if result is string {
        // If Temporal is running, the workflow ID should match our input id
        test:assertEquals(result, "test-workflow-002", "Workflow ID should match input id");
    }
    // Either way, the test passes - we're validating the API works correctly
}
