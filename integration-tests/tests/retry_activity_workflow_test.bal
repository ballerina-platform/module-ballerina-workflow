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
// RETRY ACTIVITY WORKFLOW - TESTS
// ================================================================================
// Tests for activity retry behavior and failOnError options.

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testDefaultFailOnError() returns error? {
    // Default behavior: failOnError=true, maximumAttempts=1 (no retries)
    // Activity always fails → workflow should FAIL immediately
    string testId = uniqueId("retry-default-fail");
    RetryActivityInput input = {id: testId, mode: "default_fail"};
    string workflowId = check workflow:run(retryDefaultFailWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "FAILED", 
        "Workflow should fail when activity fails with default failOnError=true and maximumAttempts=1");
}

@test:Config {
    groups: ["integration"]
}
function testFailOnErrorFalse() returns error? {
    // failOnError=false: activity error is treated as normal completion
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-fail-on-error-false");
    RetryActivityInput input = {id: testId, mode: "fail_on_error_false"};
    string workflowId = check workflow:run(retryFailOnErrorFalseWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", 
        "Workflow should complete when failOnError=false (error treated as value)");
    test:assertTrue((<string>execInfo.result).startsWith("Handled error:"), 
        "Result should show error was handled as a value");
}

@test:Config {
    groups: ["integration"]
}
function testCustomRetryPolicy() returns error? {
    // Custom retry policy: maximumAttempts=3, activity always fails
    // Workflow should FAIL after 3 attempts are exhausted
    string testId = uniqueId("retry-custom-policy");
    RetryActivityInput input = {id: testId, mode: "custom_retry"};
    string workflowId = check workflow:run(retryCustomPolicyWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "FAILED", 
        "Workflow should fail after custom retry policy is exhausted");
}

@test:Config {
    groups: ["integration"]
}
function testFailWithErrorDetails() returns error? {
    // Activity returns error with detail record (orderId, errorCode, stage)
    // Workflow should FAIL and Temporal UI should show details in the failure payload
    string testId = uniqueId("retry-fail-details");
    RetryActivityInput input = {id: testId, mode: "fail_with_details"};
    string workflowId = check workflow:run(retryFailWithDetailsWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "FAILED",
        "Workflow should fail when activity returns error with details");
}

@test:Config {
    groups: ["integration"]
}
function testFailWithErrorCause() returns error? {
    // Activity returns error with a cause chain (outer wraps inner)
    // Workflow should FAIL and Temporal UI should show the cause hierarchy
    string testId = uniqueId("retry-fail-cause");
    RetryActivityInput input = {id: testId, mode: "fail_with_cause"};
    string workflowId = check workflow:run(retryFailWithCauseWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "FAILED",
        "Workflow should fail when activity returns error with cause chain");
}

@test:Config {
    groups: ["integration"]
}
function testHandleErrorWithDetails() returns error? {
    // failOnError=false with an error that has details
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-handle-details");
    RetryActivityInput input = {id: testId, mode: "handle_details"};
    string workflowId = check workflow:run(retryHandleDetailsWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete when failOnError=false with detailed error");
    test:assertTrue((<string>execInfo.result).startsWith("Handled:"),
        "Result should show error was handled as a value");
}

@test:Config {
    groups: ["integration"]
}
function testHandleErrorWithCause() returns error? {
    // failOnError=false with an error that has a cause chain
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-handle-cause");
    RetryActivityInput input = {id: testId, mode: "handle_cause"};
    string workflowId = check workflow:run(retryHandleCauseWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete when failOnError=false with cause chain error");
    test:assertTrue((<string>execInfo.result).startsWith("Handled:"),
        "Result should show error was handled as a value");
}
