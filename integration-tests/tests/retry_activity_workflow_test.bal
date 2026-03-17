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
// Tests for activity retry behavior and retryOnError options.

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testDefaultFailOnError() returns error? {
    // Default behavior: retryOnError=false, error returned as value — `check` propagates it
    // Activity always fails → workflow should FAIL immediately without any Temporal retries
    string testId = uniqueId("retry-default-fail");
    RetryActivityInput input = {id: testId, mode: "default_fail"};
    string workflowId = check workflow:run(retryDefaultFailWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "FAILED",
        "Workflow should fail when activity fails with default retryOnError=false (error propagated via check)");
}

@test:Config {
    groups: ["integration"]
}
function testFailOnErrorFalse() returns error? {
    // retryOnError=false (explicit): activity error is returned as a normal value, no retries
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-fail-on-error-false");
    RetryActivityInput input = {id: testId, mode: "fail_on_error_false"};
    string workflowId = check workflow:run(retryFailOnErrorFalseWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete when retryOnError=false (error treated as value)");
    test:assertTrue((<string>execInfo.result).startsWith("Handled error:"),
        "Result should show error was handled as a value");
}

@test:Config {
    groups: ["integration"]
}
function testCustomRetryPolicy() returns error? {
    // Custom retry options: retryOnError=true, maxRetries=3, activity always fails
    // Workflow should FAIL after 3 retries are exhausted
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
    // retryOnError=false with an error that has details
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-handle-details");
    RetryActivityInput input = {id: testId, mode: "handle_details"};
    string workflowId = check workflow:run(retryHandleDetailsWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete when retryOnError=false with detailed error");
    test:assertTrue((<string>execInfo.result).startsWith("Handled:"),
        "Result should show error was handled as a value");
}

@test:Config {
    groups: ["integration"]
}
function testHandleErrorWithCause() returns error? {
    // retryOnError=false with an error that has a cause chain
    // Workflow should COMPLETE because the error is handled as a value
    string testId = uniqueId("retry-handle-cause");
    RetryActivityInput input = {id: testId, mode: "handle_cause"};
    string workflowId = check workflow:run(retryHandleCauseWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete when retryOnError=false with cause chain error");
    test:assertTrue((<string>execInfo.result).startsWith("Handled:"),
        "Result should show error was handled as a value");
}

// ================================================================================
// RETRY EXHAUSTION SCENARIOS
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testRetryExhaustUnhandled() returns error? {
    // Scenario A — Unhandled: activity always fails, retried 2 times (3 total attempts).
    // After exhaustion the error propagates via `check` — workflow transitions to FAILED.
    // No subsequent steps execute.
    string testId = uniqueId("retry-exhaust-unhandled");
    RetryActivityInput input = {id: testId, mode: "exhaust_unhandled"};
    string workflowId = check workflow:run(retryExhaustUnhandledWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "FAILED",
        "Scenario A: workflow must FAIL when retries are exhausted and error is unhandled");
    test:assertTrue(execInfo.errorMessage != () && (<string>execInfo.errorMessage).includes("exhaust unhandled"),
        "Error message should carry the original activity error text");
}

@test:Config {
    groups: ["integration"]
}
function testRetryExhaustFallback() returns error? {
    // Scenario B1 — Fallback: primary activity is retried 2 times and always fails.
    // Workflow catches the exhaustion error and runs a secondary fallback activity.
    // Workflow should COMPLETE via the fallback path.
    string testId = uniqueId("retry-exhaust-fallback");
    RetryActivityInput input = {id: testId, mode: "exhaust_fallback"};
    string workflowId = check workflow:run(retryExhaustFallbackWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Scenario B1: workflow must COMPLETE via the fallback activity after primary exhausts retries");
    test:assertTrue((<string>execInfo.result).startsWith("Fallback:"),
        "Result should indicate the fallback path was taken");
}

@test:Config {
    groups: ["integration"]
}
function testRetryExhaustCompensation() returns error? {
    // Scenario B2 — Compensation (Saga): step 1 commits, step 2 exhausts retries.
    // Workflow catches the exhaustion error, runs a compensation activity to undo step 1,
    // and completes with a compensated outcome.
    string testId = uniqueId("retry-exhaust-compensate");
    RetryActivityInput input = {id: testId, mode: "exhaust_compensate"};
    string workflowId = check workflow:run(retryExhaustCompensateWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Scenario B2: workflow must COMPLETE after running compensation activity (Saga pattern)");
    test:assertTrue((<string>execInfo.result).includes("Compensated"),
        "Result should confirm the compensation activity ran");
}

@test:Config {
    groups: ["integration"]
}
function testRetryExhaustGracefulCompletion() returns error? {
    // Scenario B3 — Graceful completion: non-critical notification activity exhausts retries.
    // The core business step succeeds. The workflow catches the notification failure,
    // skips it, and still completes successfully.
    string testId = uniqueId("retry-exhaust-graceful");
    RetryActivityInput input = {id: testId, mode: "exhaust_graceful"};
    string workflowId = check workflow:run(retryExhaustGracefulWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Scenario B3: workflow must COMPLETE even though the non-critical activity exhausted retries");
    test:assertTrue((<string>execInfo.result).includes("notification skipped"),
        "Result should note that the non-critical step was gracefully skipped");
}
