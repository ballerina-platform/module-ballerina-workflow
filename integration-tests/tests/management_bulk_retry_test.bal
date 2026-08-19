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
// MANAGEMENT API — BULK RETRY OF FAILED-ACTIVITY REVIEWS
// ================================================================================
//
// Covers the reviewActivities.bulkRetry operation against a live worker: selecting
// by explicit IDs and by parent workflow, and the per-task outcomes a batch reports
// rather than raising — an undecidable task must not cost the rest of the batch.
//
// ================================================================================

import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// Runs a bulk retry command and returns its report, failing the test if the batch
// itself was rejected. Per-task failures live in the report, not here.
function bulkRetry(map<json> params) returns management:BulkRetryResult|error {
    json|management:Error result = management:executeCommand({
        operation: management:BULK_RETRY_REVIEW_ACTIVITIES,
        params: params,
        identity: {userId: "bulk-tester", roles: ["approver"]}
    });
    if result is management:Error {
        return error("Bulk retry was rejected: " + result.message());
    }
    return result.cloneWithType();
}

// Starts a workflow whose activity fails under a manual-retry policy and waits for
// the review activity it raises.
function startFailingReview(string idPrefix)
        returns [string, management:ReviewActivitySummary]|error {
    string testId = uniqueId(idPrefix);
    RetryActivityInput input = {id: testId, mode: "manual_retry_fail"};
    string workflowId = check workflow:run(manualRetryFailDecisionWorkflow, input);
    management:ReviewActivitySummary review = check waitForPendingReviewActivity(workflowId);
    return [workflowId, review];
}

// Drains a workflow that must end in failure once its review is failed, and asserts
// that it failed *for the right reason*. Completing successfully would mean a rejected
// review no longer surfaces the activity's failure; failing with anything else — a
// result timeout, a cancellation, a lookup error — would let the same regression pass
// as long as something went wrong. The reviewed activity's own message is what
// distinguishes them.
function awaitExpectedFailure(string workflowId,
        string expectedFailure = "manual retry fail decision") {
    anydata|error result = workflow:getWorkflowResult(workflowId, 15);
    if result !is error {
        test:assertFail("Failing the review must surface the activity failure to the workflow, "
                + "but it completed with: " + result.toBalString());
    }
    test:assertTrue(result.message().includes(expectedFailure),
            "The workflow must fail with the reviewed activity's failure rather than a timeout or "
            + "lookup error. Expected the message to contain '" + expectedFailure
            + "' but got: " + result.message());
}

@test:Config {
    groups: ["integration"]
}
function testBulkRetryFailsManyReviewsByTaskIds() returns error? {
    [string, management:ReviewActivitySummary] first = check startFailingReview("bulk-fail-a");
    [string, management:ReviewActivitySummary] second = check startFailingReview("bulk-fail-b");

    management:BulkRetryResult report = check bulkRetry({
        action: "fail",
        taskIds: [first[1].taskId, second[1].taskId],
        feedback: "rejected in bulk"
    });

    test:assertEquals(report.action, "fail");
    test:assertEquals(report.requested, 2, "Both named tasks must be addressed");
    test:assertEquals(report.applied, 2, "Both pending failure reviews must be decided");
    test:assertEquals(report.skipped, 0);
    test:assertEquals(report.failed, 0);
    test:assertEquals(report.decidedBy, "bulk-tester", "The report records who decided");
    foreach management:BulkItemResult item in report.items {
        test:assertEquals(item.outcome, management:APPLIED);
        test:assertTrue(item.reason is (), "An applied decision carries no reason");
    }

    awaitExpectedFailure(first[0]);
    awaitExpectedFailure(second[0]);
}

@test:Config {
    groups: ["integration"]
}
function testBulkRetryByParentWorkflowRetriesThenFails() returns error? {
    [string, management:ReviewActivitySummary] started = check startFailingReview("bulk-parent");
    string workflowId = started[0];
    string firstTaskId = started[1].taskId;

    // "retry" reruns the activity with its original arguments. The activity always
    // fails, so the rerun raises a second review — which is how the test observes
    // that the decision was really submitted.
    management:BulkRetryResult report = check bulkRetry({
        action: "retry",
        parentWorkflowId: workflowId
    });
    test:assertEquals(report.requested, 1, "The parent has exactly one pending failure review");
    test:assertEquals(report.applied, 1);
    test:assertEquals(report.items[0].taskId, firstTaskId);

    management:ReviewActivitySummary second =
            check waitForPendingReviewActivity(workflowId, excludeTaskId = firstTaskId);
    test:assertTrue(second.taskId != firstTaskId, "Retrying must raise a fresh review activity");

    // activityName narrows a parent selection. A name the parent never called selects
    // nothing, which is an empty batch rather than an error — the caller asked for
    // whatever matches, and nothing does.
    management:BulkRetryResult narrowedAway = check bulkRetry({
        action: "fail",
        parentWorkflowId: workflowId,
        activityName: "someActivityThisWorkflowNeverCalls"
    });
    test:assertEquals(narrowedAway.requested, 0, "A non-matching activityName must select nothing");
    test:assertEquals(narrowedAway.items.length(), 0);

    // The review's own activity name selects it.
    management:BulkRetryResult narrowedTo = check bulkRetry({
        action: "fail",
        parentWorkflowId: workflowId,
        activityName: second.activityName
    });
    test:assertEquals(narrowedTo.requested, 1, "The review's own activity name must select it");
    test:assertEquals(narrowedTo.applied, 1);

    awaitExpectedFailure(workflowId);

    // With every review decided the parent has nothing pending, so the same selector
    // now reports an empty batch instead of failing.
    management:BulkRetryResult drained = check bulkRetry({action: "fail", parentWorkflowId: workflowId});
    test:assertEquals(drained.requested, 0, "A parent with nothing pending selects nothing");
    test:assertEquals(drained.applied, 0);
}

@test:Config {
    groups: ["integration"]
}
function testBulkRetryReportsPerTaskOutcomes() returns error? {
    [string, management:ReviewActivitySummary] started = check startFailingReview("bulk-mixed");
    string workflowId = started[0];
    string taskId = started[1].taskId;

    // One real task, one that does not exist: the batch applies what it can and
    // reports the rest, rather than refusing the whole request.
    management:BulkRetryResult report = check bulkRetry({
        action: "fail",
        taskIds: [taskId, "reviewactivity-not-a-real-task"]
    });
    test:assertEquals(report.requested, 2);
    test:assertEquals(report.applied, 1, "The decidable task must still be decided");
    test:assertEquals(report.failed, 1, "The unknown task must be reported, not raised");

    foreach management:BulkItemResult item in report.items {
        if item.taskId == taskId {
            test:assertEquals(item.outcome, management:APPLIED);
        } else {
            test:assertEquals(item.outcome, management:FAILED);
            test:assertTrue(item.reason is string, "A failed item must say why");
        }
    }

    // Re-issuing the same decision is safe: the task is already decided, so it is
    // skipped rather than failed.
    management:BulkRetryResult repeat = check bulkRetry({action: "fail", taskIds: [taskId]});
    test:assertEquals(repeat.skipped, 1, "An already-decided task must be skipped");
    test:assertEquals(repeat.applied, 0);
    test:assertEquals(repeat.items[0].outcome, management:SKIPPED);

    awaitExpectedFailure(workflowId);
}
