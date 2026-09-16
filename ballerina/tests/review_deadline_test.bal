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

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.management;

// The review has a deadline; nobody decides in time.
@Workflow
function workflowWithReviewDeadline(Context ctx, string orderId) returns string|error {
    string result = check ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = {userRoles: "manager", timeout: {seconds: 4}});
    return result;
}

@test:Config {groups: ["unit"]}
function testReviewDeadlineFailsTheReviewAndTheWorkflow() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check registerWorkflowForTest(workflowWithReviewDeadline, "workflowWithReviewDeadline", activities);

    string workflowId = check run(workflowWithReviewDeadline, "ORD-RD-001");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1, "the failed activity raises one review");
    string taskId = pending[0].taskId;

    anydata|error result = getWorkflowResult(workflowId, 20);
    test:assertTrue(result is error, "the workflow fails once the review deadline passes");
    if result is error {
        test:assertTrue(result.message().includes("timed out"), "the failure names the deadline: " + result.message());
    }

    management:ReviewActivityInfo info = check management:getReviewActivityInfo(taskId);
    test:assertEquals(info.status, "FAILED", "a review that times out ends FAILED, like a human task");
}

@test:Config {groups: ["unit"]}
function testReviewDeciderIsReadableFromTheListingMemo() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check registerWorkflowForTest(workflowWithManualRetry, "workflowWithManualRetry", activities);

    string workflowId = check run(workflowWithManualRetry, "ORD-RD-002");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1);

    check management:completeReviewActivity(pending[0].taskId, {action: "reject", feedback: "not now"},
            callerRoles = ["manager"], userId = "auditor");
    anydata|error outcome = getWorkflowResult(workflowId, 20);
    test:assertTrue(outcome is error, "a rejected review surfaces the activity failure");

    management:ReviewActivityInfo info = check management:getReviewActivityInfo(pending[0].taskId);
    test:assertEquals(info.completedBy, "auditor");
    test:assertTrue(info.completedAt is string, "the decision instant is recorded beside the decider");
}
