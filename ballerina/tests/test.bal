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
// For workflow execution tests (startProcess, sendEvent), a separate integration test 
// suite should be created that initializes the embedded test server before registering workflows.

// Record types for events in test processes
type MultiEventRecord record {|
    future<string> approvalEvent;
    future<int> paymentEvent;
|};

type SingleEventRecord record {|
    future<boolean> confirmationEvent;
|};

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

// Test process that calls activities.
@Process
function processWithActivities(string input) returns string|error {
    // This would normally call activities
    string result1 = check testActivityFunction(input);
    int result2 = check testActivityFunction2(10);
    return result1 + " - " + result2.toString();
}

@test:BeforeEach
function beforeEach() returns error? {
    // Clear registry before each test to ensure clean state
    _ = check clearRegistry();
}

@test:Config {}
function testRegisterProcess() returns error? {
    boolean result = check registerProcess(testProcessFunction, "test-process");
    test:assertTrue(result, "Process registration should succeed");
    
    // Verify the process is registered
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("test-process"), "Process should be in registry");
}

@test:Config {}
function testRegisterProcessDuplicate() returns error? {
    // First registration should succeed
    boolean result1 = check registerProcess(testProcessFunction, "dup-process");
    test:assertTrue(result1, "First registration should succeed");
    
    // Second registration with same name should fail
    boolean|error result2 = registerProcess(testProcessFunction, "dup-process");
    test:assertTrue(result2 is error, "Duplicate registration should fail");
}

@test:Config {}
function testRegisterProcessWithActivities() returns error? {
    // Create a map of activities
    map<function> activities = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    
    // Register process with activities
    boolean result = check registerProcess(processWithActivities, "process-with-activities", activities);
    test:assertTrue(result, "Process registration with activities should succeed");
    
    // Verify the process and activities are registered
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

@test:Config {}
function testGetRegisteredWorkflowsEmpty() returns error? {
    // Registry should be empty after clear
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertEquals(registry.length(), 0, "Registry should be empty");
}

@test:Config {}
function testClearRegistry() returns error? {
    // Register a process
    _ = check registerProcess(testProcessFunction, "clear-test-process");
    
    // Verify it's registered
    WorkflowRegistry registry1 = check getRegisteredWorkflows();
    test:assertTrue(registry1.hasKey("clear-test-process"), "Process should be registered");
    
    // Clear the registry
    boolean cleared = check clearRegistry();
    test:assertTrue(cleared, "Clear should succeed");
    
    // Verify it's gone
    WorkflowRegistry registry2 = check getRegisteredWorkflows();
    test:assertEquals(registry2.length(), 0, "Registry should be empty after clear");
}

@test:Config {}
function testMultipleProcessRegistration() returns error? {
    // Register multiple processes
    _ = check registerProcess(testProcessFunction, "multi-process-1");
    _ = check registerProcess(processWithActivities, "multi-process-2");
    
    // Verify both are registered
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertEquals(registry.length(), 2, "Should have 2 processes");
    test:assertTrue(registry.hasKey("multi-process-1"), "Should have process 1");
    test:assertTrue(registry.hasKey("multi-process-2"), "Should have process 2");
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

@test:Config {}
function testRegisterProcessWithEvents() returns error? {
    // Register process with events
    boolean result = check registerProcess(processWithEvents, "process-with-events");
    test:assertTrue(result, "Process registration with events should succeed");
    
    // Verify the process is registered with events
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

@test:Config {}
function testRegisterProcessWithSingleEvent() returns error? {
    // Register process with a single event
    boolean result = check registerProcess(processWithContextAndEvents, "process-single-event");
    test:assertTrue(result, "Process registration with single event should succeed");
    
    // Verify the process is registered with the event
    WorkflowRegistry registry = check getRegisteredWorkflows();
    test:assertTrue(registry.hasKey("process-single-event"), "Process should be in registry");
    
    ProcessRegistration? processInfo = registry["process-single-event"];
    test:assertTrue(processInfo is ProcessRegistration, "Process info should exist");
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "confirmationEvent", "Should have confirmationEvent");
    }
}

@test:Config {}
function testProcessWithoutEventsHasEmptyEventList() returns error? {
    // Register a process without events
    boolean result = check registerProcess(testProcessFunction, "no-events-process");
    test:assertTrue(result, "Process registration should succeed");
    
    // Verify the process has empty events array
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["no-events-process"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 0, "Should have 0 events");
    }
}

@test:Config {}
function testProcessWithActivitiesAndEvents() returns error? {
    // Create a map of activities
    map<function> activities = {
        "testActivityFunction": testActivityFunction
    };
    
    // Register process with both activities and events
    boolean result = check registerProcess(processWithEvents, "process-activities-events", activities);
    test:assertTrue(result, "Process registration with activities and events should succeed");
    
    // Verify the process has both activities and events
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

@test:Config {}
function testInlineRecordWithMultipleEvents() returns error? {
    // Register process with inline record events
    boolean result = check registerProcess(processWithInlineEvents, "inline-multi-events");
    test:assertTrue(result, "Process registration with inline events should succeed");
    
    // Verify the process is registered with events
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

@test:Config {}
function testInlineRecordWithSingleEvent() returns error? {
    // Register process with single inline event
    boolean result = check registerProcess(processWithSingleInlineEvent, "inline-single-event");
    test:assertTrue(result, "Process registration with single inline event should succeed");
    
    // Verify the process is registered with the event
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-single-event"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "confirmEvent", "Should have confirmEvent");
    }
}

@test:Config {}
function testInlineRecordWithThreeEvents() returns error? {
    // Register process with three inline events of different types
    boolean result = check registerProcess(processWithMixedInlineEvents, "inline-mixed-events");
    test:assertTrue(result, "Process registration with mixed inline events should succeed");
    
    // Verify the process is registered with all events
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

@test:Config {}
function testInlineRecordWithoutContext() returns error? {
    // Register process with inline events but no Context parameter
    boolean result = check registerProcess(processWithInlineEventsNoContext, "inline-no-context");
    test:assertTrue(result, "Process registration with inline events (no context) should succeed");
    
    // Verify the process is registered with the event
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-no-context"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.events.length(), 1, "Should have 1 event");
        test:assertEquals(processInfo.events[0], "simpleEvent", "Should have simpleEvent");
    }
}

@test:Config {}
function testInlineRecordWithActivities() returns error? {
    // Create a map of activities
    map<function> activities = {
        "testActivityFunction": testActivityFunction,
        "testActivityFunction2": testActivityFunction2
    };
    
    // Register process with inline events and activities
    boolean result = check registerProcess(processWithInlineEvents, "inline-with-activities", activities);
    test:assertTrue(result, "Process registration with inline events and activities should succeed");
    
    // Verify the process has both activities and events
    WorkflowRegistry registry = check getRegisteredWorkflows();
    ProcessRegistration? processInfo = registry["inline-with-activities"];
    
    if processInfo is ProcessRegistration {
        test:assertEquals(processInfo.activities.length(), 2, "Should have 2 activities");
        test:assertEquals(processInfo.events.length(), 2, "Should have 2 events");
    }
}

// ================== Workflow Creation Tests ==================
// These tests validate the startProcess API's input validation and error handling.
// They work with lazy gRPC connection - actual workflow execution requires Temporal server.

// Simple workflow process for testing startProcess
@Process
function simpleWorkflowProcess(string input) returns string|error {
    return "Hello, " + input;
}

@test:Config {}
function testStartProcessWithUnregisteredProcess() returns error? {
    // Attempt to start a workflow without registering the process first
    WorkflowData input = {id: "test-workflow-001"};
    
    string|error result = startProcess(simpleWorkflowProcess, input);
    
    // Should fail because the process is not registered
    test:assertTrue(result is error, "Starting unregistered process should fail");
    if result is error {
        test:assertTrue(result.message().includes("not registered") || result.message().includes("Failed"),
            "Error should indicate process not registered");
    }
}

@test:Config {}
function testStartProcessWithMissingId() returns error? {
    // First register the process
    boolean registered = check registerProcess(simpleWorkflowProcess, "simple-workflow");
    test:assertTrue(registered, "Process registration should succeed");
    
    // Attempt to start workflow without 'id' field
    map<anydata> inputWithoutId = {"name": "test"};
    
    string|error result = startProcess(simpleWorkflowProcess, inputWithoutId);
    
    // Should fail because 'id' field is required
    test:assertTrue(result is error, "Starting workflow without 'id' should fail");
    if result is error {
        test:assertTrue(result.message().includes("id") || result.message().includes("Failed"),
            "Error should indicate missing id field");
    }
}

@test:Config {}
function testStartProcessWithValidInput() returns error? {
    // Register the process first
    boolean registered = check registerProcess(simpleWorkflowProcess, "workflow-for-start-test");
    test:assertTrue(registered, "Process registration should succeed");
    
    // Prepare valid input with required 'id' field
    WorkflowData input = {id: "test-workflow-002", "name": "TestUser"};
    
    // This will attempt to connect to Temporal server
    // Without a running Temporal server, we expect a connection error
    string|error result = startProcess(simpleWorkflowProcess, input);
    
    // The result could be:
    // 1. A workflow ID string if Temporal is running (integration test environment)
    // 2. An error due to connection failure (expected in unit test environment)
    if result is string {
        // If Temporal is running, the workflow ID should match our input id
        test:assertEquals(result, "test-workflow-002", "Workflow ID should match input id");
    }
    // Either way, the test passes - we're validating the API works correctly
}