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
// ACTIVITY INVOCATION TRACKING - TESTS
// ================================================================================
// Tests that WorkflowExecutionInfo.activityInvocations is populated from the
// Temporal event history and that the optional `attempt` field reflects retries.

import ballerina/test;
import ballerina/workflow;

// ================================================================================
// SUCCESS PATH — two activities complete on first attempt
// ================================================================================

@test:Config {
    groups: ["integration", "activity-invocations"]
}
function testActivityInvocationsOnSuccess() returns error? {
    string testId = uniqueId("inv-success");
    ActivityInvocationInput input = {id: testId, value: "hello"};
    string workflowId = check workflow:run(twoActivityInvocationWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Workflow should complete successfully");
    test:assertEquals(execInfo.result, "HELLO:5",
        "Result should be uppercased value + length");

    // Verify activity invocations are populated
    workflow:ActivityInvocation[] invocations = execInfo.activityInvocations;
    test:assertTrue(invocations.length() >= 2,
        "Should have at least 2 activity invocations, got " + invocations.length().toString());

    // Filter for the two user activities by name
    workflow:ActivityInvocation[] userActivities = from workflow:ActivityInvocation inv in invocations
        where inv.activityName.includes("uppercaseActivity") ||
              inv.activityName.includes("lengthActivity")
        select inv;

    test:assertEquals(userActivities.length(), 2,
        "Should have exactly 2 user activity invocations");

    // Both should be COMPLETED with attempt == 1 (no retries)
    foreach workflow:ActivityInvocation inv in userActivities {
        test:assertEquals(inv.status, "COMPLETED",
            "Activity " + inv.activityName + " should be COMPLETED");
        int? attempt = inv.attempt;
        test:assertTrue(attempt is int && attempt == 1,
            "Activity " + inv.activityName + " should be attempt 1 (no retries)");
    }
}

// ================================================================================
// RETRY PATH — activity fails after retries, attempt count reflects total attempts
// ================================================================================

@test:Config {
    groups: ["integration", "activity-invocations"]
}
function testActivityInvocationsOnRetryFailure() returns error? {
    string testId = uniqueId("inv-retry-fail");
    ActivityInvocationInput input = {id: testId, value: "trigger"};
    string workflowId = check workflow:run(retryInvocationWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "FAILED",
        "Workflow should fail after retries exhausted");

    // Find the FAILED invocation for invocationFailActivity
    workflow:ActivityInvocation[] failedActivities =
            from workflow:ActivityInvocation inv in execInfo.activityInvocations
            where inv.activityName.includes("invocationFailActivity") && inv.status == "FAILED"
            select inv;

    test:assertTrue(failedActivities.length() >= 1,
        "Should have at least one FAILED invocationFailActivity invocation");

    // With maxRetries=2, the final attempt should be >= 3 (1 initial + 2 retries)
    workflow:ActivityInvocation lastFailed = failedActivities[failedActivities.length() - 1];
    int? lastAttempt = lastFailed.attempt;
    test:assertTrue(lastAttempt is int && lastAttempt >= 3,
        "Final failed invocation should be attempt >= 3, got " + (lastAttempt ?: 0).toString());

    // Verify error message is captured
    test:assertTrue(lastFailed.errorMessage is string,
        "Failed activity should have an errorMessage");
}

// ================================================================================
// SINGLE FAILURE — no retry, attempt == 1
// ================================================================================

@test:Config {
    groups: ["integration", "activity-invocations"]
}
function testActivityInvocationsOnSingleFailure() returns error? {
    string testId = uniqueId("inv-single-fail");
    ActivityInvocationInput input = {id: testId, value: "boom"};
    string workflowId = check workflow:run(singleFailInvocationWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "FAILED",
        "Workflow should fail on activity error");

    // Find the FAILED invocation
    workflow:ActivityInvocation[] failedActivities =
            from workflow:ActivityInvocation inv in execInfo.activityInvocations
            where inv.activityName.includes("invocationFailActivity") && inv.status == "FAILED"
            select inv;

    test:assertTrue(failedActivities.length() >= 1,
        "Should have at least one FAILED invocation");

    // Without retries the single attempt should be attempt 1
    int? attempt = failedActivities[0].attempt;
    test:assertTrue(attempt is int && attempt == 1,
        "Without retries, failed activity should be attempt 1");
}
