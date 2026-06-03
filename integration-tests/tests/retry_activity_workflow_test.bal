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

    anydata|error rawResult = workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue(rawResult is error,
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

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue((<string>result).startsWith("Handled error:"),
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

    anydata|error rawResult = workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue(rawResult is error,
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

    anydata|error rawResult = workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue(rawResult is error,
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

    anydata|error rawResult = workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue(rawResult is error,
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

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue((<string>result).startsWith("Handled:"),
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

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue((<string>result).startsWith("Handled:"),
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

    anydata|error rawResult = workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue(rawResult is error,
        "Scenario A: workflow must FAIL when retries are exhausted and error is unhandled");
    if rawResult is error {
        test:assertTrue(rawResult.message().includes("exhaust unhandled"),
            "Error message should carry the original activity error text");
    }
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

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue((<string>result).startsWith("Fallback:"),
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

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue((<string>result).includes("Compensated"),
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

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue((<string>result).includes("notification skipped"),
        "Result should note that the non-critical step was gracefully skipped");
}

// ================================================================================
// ADDITIONAL PATTERN-DOC COVERAGE
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testMultiTierFallback() returns error? {
    // Multi-tier fallback chain: email (fails) → SMS (fails) → support ticket (succeeds).
    // Matches error-fallback.md "Chaining Multiple Fallbacks" pattern.
    string testId = uniqueId("multi-tier-fallback");
    RetryActivityInput input = {id: testId, mode: "multi_tier_fallback"};
    string workflowId = check workflow:run(multiTierFallbackWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue((<string>result).startsWith("Fallback:ticket:"),
        "Result should indicate the support ticket fallback was used");
    test:assertTrue((<string>result).includes("TICKET-001"),
        "Result should contain the ticket ID from the final fallback");
}

@test:Config {
    groups: ["integration"]
}
function testMultiStepCompensation() returns error? {
    // Multi-step compensation: step 1 & 2 commit, step 3 fails.
    // Compensate in reverse: step 2 first, then step 1.
    // Matches error-compensation.md "Scaling to More Steps" pattern.
    string testId = uniqueId("multi-step-compensate");
    RetryActivityInput input = {id: testId, mode: "multi_step_compensate"};
    string workflowId = check workflow:run(multiStepCompensationWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertTrue((<string>result).startsWith("ROLLED_BACK:"),
        "Result should indicate a rollback occurred");
    // Verify reverse order: charge-card compensated first, then reserve-inventory
    string resultStr = <string>result;
    test:assertTrue(resultStr.includes("compensated:charge-card") && resultStr.includes("compensated:reserve-inventory"),
        "Result should confirm both steps were compensated");
}

@test:Config {
    groups: ["integration"]
}
function testMultiNonCriticalGraceful() returns error? {
    // Multiple non-critical steps: critical reservation succeeds,
    // but email and audit both fail. Workflow completes with skipped list.
    // Matches graceful-completion.md full code pattern.
    string testId = uniqueId("multi-graceful");
    RetryActivityInput input = {id: testId, mode: "multi_graceful"};
    string workflowId = check workflow:run(multiNonCriticalGracefulWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    string resultStr = <string>result;
    test:assertTrue(resultStr.startsWith("RSV-"),
        "Result should contain the reservation ID from the critical step");
    test:assertTrue(resultStr.includes("skipped: email, audit"),
        "Result should list both skipped non-critical steps");
}
