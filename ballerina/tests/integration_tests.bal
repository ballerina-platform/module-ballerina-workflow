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

// ================================================================================
// INTEGRATION TESTS - Workflow Execution Tests
// ================================================================================
// 
// These tests use the embedded Temporal test server started by Gradle.
// 
// The test lifecycle is:
// 1. Gradle startTestServer task runs → starts embedded Temporal server
// 2. Gradle writes test Config.toml with the server URL
// 3. Ballerina tests run, connecting to the test server
// 4. Gradle stopTestServer task runs → stops the server
//
// Run with: ./gradlew :workflow-ballerina:test
//
// ================================================================================

import ballerina/test;

// Integration test workflow - defined at module level
@Process
function integrationTestWorkflow(string input) returns string|error {
    return "Hello from workflow: " + input;
}

// Test that uses the embedded Temporal test server
@test:Config {
    groups: ["integration"]
}
function testWorkflowExecutionWithTestServer() returns error? {
    // Register the workflow
    // Use the function name as the process name (matching how startProcess extracts the name)
    boolean registered = check registerProcess(integrationTestWorkflow, "integrationTestWorkflow");
    test:assertTrue(registered, "Workflow registration should succeed");
    
    // Start the workflow
    WorkflowData input = {id: "integration-test-001", "name": "IntegrationTest"};
    string workflowId = check startProcess(integrationTestWorkflow, input);
    
    test:assertEquals(workflowId, "integration-test-001", "Workflow ID should match input id");
}
