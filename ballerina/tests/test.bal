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
