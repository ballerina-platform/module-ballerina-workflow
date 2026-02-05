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
// DUPLICATE WORKFLOW DETECTION - TESTS
// ================================================================================
//
// Tests for duplicate workflow detection when starting workflows with
// the same correlation keys. The system should prevent creating multiple
// workflow instances with identical correlation keys.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// DUPLICATE WORKFLOW DETECTION TESTS
// ================================================================================

@test:Config {
    groups: ["integration", "duplicate"]
}
function testDuplicateWorkflowPrevention() returns error? {
    string testId = uniqueId("duplicate-test");
    SimpleSignalInput input = {id: testId, message: "First workflow"};
    
    // Start the first workflow
    string workflowId1 = check workflow:createInstance(simpleSignalWorkflow, input);
    test:assertNotEquals(workflowId1, "", "First workflow should start successfully");
    
    // Give the workflow time to start and be visible in the search index
    runtime:sleep(2);
    
    // Try to start a second workflow with the same correlation key (id)
    // This should fail with a DuplicateWorkflowError
    SimpleSignalInput duplicateInput = {id: testId, message: "Duplicate attempt"};
    string|error result = workflow:createInstance(simpleSignalWorkflow, duplicateInput);
    
    if result is error {
        // Verify the error message indicates duplicate
        test:assertTrue(result.message().includes("already exists"), 
                "Error message should indicate duplicate: " + result.message());
    } else {
        test:assertFail("Starting duplicate workflow should have failed");
    }
    
    // Clean up - send signal to complete the first workflow
    SimpleSignalData signalData = {id: testId, response: "Completing first workflow"};
    _ = check workflow:sendEvent(simpleSignalWorkflow, signalData, "response");
}

@test:Config {
    groups: ["integration", "duplicate"]
}
function testDuplicateAfterCompletionAllowed() returns error? {
    string testId = uniqueId("duplicate-after-complete");
    SimpleSignalInput input = {id: testId, message: "First workflow"};
    
    // Start the first workflow
    string workflowId1 = check workflow:createInstance(simpleSignalWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send signal to complete the first workflow
    SimpleSignalData signalData = {id: testId, response: "Completing workflow"};
    _ = check workflow:sendEvent(simpleSignalWorkflow, signalData, "response");
    
    // Wait for the workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId1, 30);
    test:assertEquals(execInfo.status, "COMPLETED", "First workflow should complete");
    
    // Wait a bit for visibility to update (workflow is now completed, not running)
    runtime:sleep(2);
    
    // Now starting a new workflow with the same correlation key should succeed
    // because the first one is no longer running
    SimpleSignalInput newInput = {id: testId, message: "Second workflow after completion"};
    string|error workflowId2 = workflow:createInstance(simpleSignalWorkflow, newInput);
    
    if workflowId2 is error {
        test:assertFail("Should be able to start new workflow after first one completes: " + workflowId2.message());
    }
    
    test:assertNotEquals(workflowId1, workflowId2, "Second workflow should have different ID");
    
    // Clean up - complete the second workflow
    SimpleSignalData signalData2 = {id: testId, response: "Completing second workflow"};
    _ = check workflow:sendEvent(simpleSignalWorkflow, signalData2, "response");
}

@test:Config {
    groups: ["integration", "duplicate"]
}
function testDifferentCorrelationKeysAllowed() returns error? {
    string testId1 = uniqueId("unique-workflow-1");
    string testId2 = uniqueId("unique-workflow-2");
    
    // Start two workflows with different correlation keys - should both succeed
    SimpleSignalInput input1 = {id: testId1, message: "First unique workflow"};
    SimpleSignalInput input2 = {id: testId2, message: "Second unique workflow"};
    
    string workflowId1 = check workflow:createInstance(simpleSignalWorkflow, input1);
    runtime:sleep(1); // Wait for visibility
    
    string workflowId2 = check workflow:createInstance(simpleSignalWorkflow, input2);
    
    test:assertNotEquals(workflowId1, "", "First workflow should start");
    test:assertNotEquals(workflowId2, "", "Second workflow should start");
    test:assertNotEquals(workflowId1, workflowId2, "Workflows should have different IDs");
    
    // Clean up - complete both workflows
    SimpleSignalData signal1 = {id: testId1, response: "Done 1"};
    SimpleSignalData signal2 = {id: testId2, response: "Done 2"};
    _ = check workflow:sendEvent(simpleSignalWorkflow, signal1, "response");
    _ = check workflow:sendEvent(simpleSignalWorkflow, signal2, "response");
}
