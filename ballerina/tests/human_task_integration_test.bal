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

// ============================================================================
// Human task completion — end-to-end integration tests
// ============================================================================
//
// These tests run a workflow that parks on `awaitHumanTask`, then complete it
// through `management:completeHumanTask` against the embedded IN_MEMORY Temporal
// server (started in @BeforeSuite in test.bal). They verify that:
//   * a valid payload completes the task and is returned to the workflow,
//   * an empty (nil) completion works for a nilable result type,
//   * an invalid payload is rejected without completing the task, so the task
//     stays pending and can still be completed with a valid payload
//     (ballerina-library#8866).
//
// They follow the graceful-skip pattern used by the ManualRetry tests: if no
// workflow server is reachable the test returns early instead of failing.
// ============================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

type HtDecision record {|
    boolean approved;
    string comment;
|};

// Workflow that waits for a human decision typed as a (non-nilable) record.
@Workflow
function htRecordWorkflow(Context ctx, string orderId) returns HtDecision|error {
    HtDecision decision = check ctx->awaitHumanTask("htApproveOrder", "APPROVER",
            payload = {"orderId": orderId});
    return decision;
}

// Workflow whose human decision is nilable, so an empty completion is valid.
@Workflow
function htNilableWorkflow(Context ctx, string orderId) returns HtDecision?|error {
    HtDecision? decision = check ctx->awaitHumanTask("htApproveOptional", "APPROVER",
            payload = {"orderId": orderId});
    return decision;
}

// Resolves the first pending human task ID for a parent workflow, or () when none/unavailable.
function firstPendingHumanTaskId(string workflowId) returns string? {
    management:HumanTaskGroup[]|error groups = management:listPendingHumanTasks(workflowId);
    if groups is error {
        return ();
    }
    foreach management:HumanTaskGroup g in groups {
        if g.taskIds.length() > 0 {
            return g.taskIds[0];
        }
    }
    return ();
}

@test:Config {groups: ["unit"]}
function testCompleteHumanTaskWithValidRecordPayload() returns error? {
    _ = check wfInternal:registerWorkflow(htRecordWorkflow, "human-task-record-valid-test");

    map<string> input = {id: "test-ht-valid-001", orderId: "ORD-HT-001"};
    string|error runResult = run(htRecordWorkflow, input);
    if runResult is error {
        return; // No server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(2);

    string? taskId = firstPendingHumanTaskId(workflowId);
    if taskId is () {
        return; // Task not visible — skip.
    }

    HtDecision expected = {approved: true, comment: "LGTM"};
    error? completeResult = management:completeHumanTask(taskId, expected, ["APPROVER"]);
    test:assertTrue(completeResult is (), "Valid record completion should succeed");

    anydata|error wfResult = getWorkflowResult(workflowId, 15);
    if wfResult is error {
        return;
    }
    HtDecision|error decision = wfResult.ensureType();
    test:assertTrue(decision is HtDecision, "Workflow should return the completed decision record");
    if decision is HtDecision {
        test:assertEquals(decision, expected, "Returned decision should match the completion payload");
    }
}

@test:Config {groups: ["unit"]}
function testCompleteHumanTaskEmptyForNilableType() returns error? {
    _ = check wfInternal:registerWorkflow(htNilableWorkflow, "human-task-nilable-test");

    map<string> input = {id: "test-ht-nil-001", orderId: "ORD-HT-002"};
    string|error runResult = run(htNilableWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    string? taskId = firstPendingHumanTaskId(workflowId);
    if taskId is () {
        return;
    }

    // Empty (nil) completion is the happy path for a nilable result type.
    error? completeResult = management:completeHumanTask(taskId, (), ["APPROVER"]);
    test:assertTrue(completeResult is (), "Empty completion should succeed for a nilable result type");

    anydata|error wfResult = getWorkflowResult(workflowId, 15);
    if wfResult is error {
        return;
    }
    test:assertTrue(wfResult is (), "Workflow should return nil for an empty completion");
}

@test:Config {groups: ["unit"]}
function testCompleteHumanTaskInvalidPayloadDoesNotComplete() returns error? {
    _ = check wfInternal:registerWorkflow(htRecordWorkflow, "human-task-record-invalid-test");

    map<string> input = {id: "test-ht-invalid-001", orderId: "ORD-HT-003"};
    string|error runResult = run(htRecordWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    string? taskId = firstPendingHumanTaskId(workflowId);
    if taskId is () {
        return;
    }

    // Completing a record-typed task with a plain string must be rejected …
    error? invalidResult = management:completeHumanTask(taskId, "not-a-record", ["APPROVER"]);
    test:assertTrue(invalidResult is error, "Invalid payload completion must return an error");
    if invalidResult is error {
        test:assertTrue(invalidResult.message().includes("Invalid payload"),
                "Error should identify an invalid payload: " + invalidResult.message());
    }

    // … and must NOT complete the task: it should still be pending.
    runtime:sleep(1);
    string? stillPending = firstPendingHumanTaskId(workflowId);
    test:assertTrue(stillPending is string,
            "Task must remain pending after a rejected completion");

    // A subsequent valid completion should then succeed.
    HtDecision expected = {approved: false, comment: "rejected after retry"};
    error? validResult = management:completeHumanTask(taskId, expected, ["APPROVER"]);
    test:assertTrue(validResult is (), "Valid completion after an invalid attempt should succeed");

    anydata|error wfResult = getWorkflowResult(workflowId, 15);
    if wfResult is error {
        return;
    }
    HtDecision|error decision = wfResult.ensureType();
    test:assertTrue(decision is HtDecision, "Workflow should ultimately return the valid decision");
    if decision is HtDecision {
        test:assertEquals(decision, expected, "Returned decision should match the valid payload");
    }
}

@test:Config {groups: ["unit"]}
function testFailHumanTaskBypassesPayloadValidation() returns error? {
    // failHumanTask sends a rejection sentinel ({__rejected: true, ...}) that intentionally does not
    // conform to the task's result type. Payload validation must not block it (ballerina-library#8866).
    _ = check wfInternal:registerWorkflow(htRecordWorkflow, "human-task-record-reject-test");

    map<string> input = {id: "test-ht-reject-001", orderId: "ORD-HT-004"};
    string|error runResult = run(htRecordWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    string? taskId = firstPendingHumanTaskId(workflowId);
    if taskId is () {
        return;
    }

    error? failResult = management:failHumanTask(taskId, "Missing supporting documents",
            details = {"missingDocs": ["receipt"]}, callerRoles = ["APPROVER"]);
    test:assertTrue(failResult is (), "failHumanTask should succeed for a valid pending task");

    // The rejection is delivered; the task workflow fails with the rejection reason
    // (ballerina-library#8892), so the parent reaches a terminal state rather than staying pending.
    anydata|error wfResult = getWorkflowResult(workflowId, 15);
    test:assertTrue(wfResult is error, "Rejecting a record-typed task should terminate the workflow with an error");
}
