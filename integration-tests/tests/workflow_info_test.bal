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
// WORKFLOW INFO - TESTS
// ================================================================================

import ballerina/test;
import ballerina/lang.runtime;
import ballerina/workflow;
import ballerina/workflow.management;

// Polls getWorkflowInfo until status matches one of the expected values or timeout elapses.
function waitForWorkflowState(string workflowId, string[] expected, decimal timeoutSecs = 10)
        returns management:WorkflowExecutionInfo|error {
    decimal elapsed = 0.0d;
    error? lastError = ();
    while elapsed < timeoutSecs {
        management:WorkflowExecutionInfo|error infoOrErr = management:getWorkflowInfo(workflowId);
        if infoOrErr is management:WorkflowExecutionInfo {
            lastError = ();
            foreach string s in expected {
                if infoOrErr.status == s {
                    return infoOrErr;
                }
            }
        } else {
            lastError = infoOrErr;
        }
        runtime:sleep(0.1d);
        elapsed += 0.1d;
    }
    if lastError is error {
        return lastError;
    }
    return error("Timed out waiting for workflow " + workflowId + " to reach state: "
            + string:'join(", ", ...expected));
}

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowInfo() returns error? {
    string testId = uniqueId("info-test");
    InfoTestInput input = {id: testId, name: "Charlie"};
    string workflowId = check workflow:run(infoTestWorkflow, input);

    // Poll until the workflow is in a stable state (RUNNING or COMPLETED)
    management:WorkflowExecutionInfo execInfo =
            check waitForWorkflowState(workflowId, ["RUNNING", "COMPLETED"]);

    // Workflow ID must be a valid UUID v7
    test:assertTrue(isValidUuidV7(execInfo.workflowId), "Workflow ID should be a valid UUID v7");
    test:assertTrue(execInfo.status == "RUNNING" || execInfo.status == "COMPLETED",
        "Status should be RUNNING or COMPLETED");
}

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowInfoAfterCompletion() returns error? {
    string testId = uniqueId("info-complete");
    InfoTestInput input = {id: testId, name: "Diana"};
    string workflowId = check workflow:run(infoTestWorkflow, input);

    // Wait for completion and verify result
    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, "Processed: Diana", "Result should match");

    // For status/workflowId, use getWorkflowInfo
    management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(workflowId);
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should be completed");
    test:assertTrue(isValidUuidV7(execInfo.workflowId), "Workflow ID should be a valid UUID v7");
}

// ================================================================================
// MANAGEMENT API - listWorkflowDefinitions
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListWorkflowDefinitions() returns error? {
    management:WorkflowDefinition[] defs = check management:listWorkflowDefinitions();

    test:assertTrue(defs.length() > 0, "Should have at least one workflow definition registered");

    foreach management:WorkflowDefinition def in defs {
        test:assertFalse(def.workflowType == "", "workflowType must not be empty");
    }
}

// ================================================================================
// MANAGEMENT API - suspendWorkflow / resumeWorkflow
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testSuspendAndResumeWorkflow() returns error? {
    string testId = uniqueId("suspend-resume");
    // Use a signal-based workflow so it stays RUNNING while waiting for the signal
    SimpleSignalInput input = {id: testId, message: "suspend-resume-test"};
    string workflowId = check workflow:run(simpleSignalWorkflow, input);

    // Wait until workflow reaches RUNNING (signal-wait point)
    _ = check waitForWorkflowState(workflowId, ["RUNNING"]);

    // Suspend should succeed without error
    check management:suspendWorkflow(workflowId);

    // Resume should succeed without error
    check management:resumeWorkflow(workflowId);

    // Send the signal to complete the workflow after it has been resumed
    check workflow:sendData(simpleSignalWorkflow, workflowId, "response", {
        id: testId,
        response: "resume-ok"
    });

    _ = check workflow:getWorkflowResult(workflowId, 30);
    management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(workflowId);
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete after suspend/resume cycle");
}

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowInfoNonExistentWorkflowReturnsError() returns error? {
    management:WorkflowExecutionInfo|error result = management:getWorkflowInfo("non-existent-workflow-id-xyz");
    test:assertTrue(result is error, "Getting info for a non-existent workflow should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testSuspendNonExistentWorkflowReturnsError() returns error? {
    error? result = management:suspendWorkflow("non-existent-workflow-id-xyz");
    test:assertTrue(result is error, "Suspending a non-existent workflow should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testResumeNonExistentWorkflowReturnsError() returns error? {
    error? result = management:resumeWorkflow("non-existent-workflow-id-xyz");
    test:assertTrue(result is error, "Resuming a non-existent workflow should return an error");
}
