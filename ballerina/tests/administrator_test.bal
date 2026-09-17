// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

// Approvers decide; the ops team administers. Nobody else sees the task.
@Workflow
function workflowWithAdministeredTask(Context ctx, string orderId) returns string|error {
    string result = check ctx->awaitHumanTask("adminSignoff", {"orderId": orderId},
            userRoles = "approver", administratorRoles = "ops", administratorUsers = "dana");
    HumanTaskCompletion? completion = ctx.lastHumanTaskCompletion("adminSignoff");
    string completer = completion is () ? "?" : (completion.completedBy ?: "?");
    string role = completion is () ? "?" : completion.completedAs;
    return result + " by " + completer + " as " + role;
}

// A review with a short deadline an administrator can move.
@Workflow
function workflowWithAdministeredReview(Context ctx, string orderId) returns string|error {
    string|error result = ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = {userRoles: "approver", administratorRoles: "ops", timeout: {seconds: 3}});
    if result is ReviewTimeoutError {
        return "timed out";
    }
    if result is ReviewRejectedError {
        ReviewDecisionRecord? decision = ctx.lastReviewDecision();
        return "rejected as " + (decision is () ? "?" : decision.completedAs);
    }
    return result;
}

// Every test in this file shares the two workflows; the first registration wins, later ones are no-ops.
function registerAdministeredTaskWorkflow() returns error? {
    boolean|error registered = registerWorkflowForTest(workflowWithAdministeredTask, "workflowWithAdministeredTask");
    if registered is error && !registered.message().includes("already registered") {
        return registered;
    }
}

function registerAdministeredReviewWorkflow() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    boolean|error registered = registerWorkflowForTest(workflowWithAdministeredReview,
            "workflowWithAdministeredReview", activities);
    if registered is error && !registered.message().includes("already registered") {
        return registered;
    }
}

@test:Config {groups: ["unit"]}
function testAdministratorsSeeAndOthersDoNot() returns error? {
    check registerAdministeredTaskWorkflow();
    string workflowId = check run(workflowWithAdministeredTask, "ORD-ADM-001");
    runtime:sleep(1.5);
    management:HumanTaskGroup[] pending = check management:listPendingHumanTasks(workflowId);
    string taskId = pending[0].taskIds[0];

    // The command layer is what every console goes through; identity decides what it answers.
    json approverView = check management:executeCommand({operation: management:GET_HUMAN_TASK,
            params: {taskId}, identity: {userId: "bob", roles: ["approver"]}});
    test:assertEquals(check approverView.canComplete, true);
    test:assertEquals(check approverView.canAdminister, false);

    json opsView = check management:executeCommand({operation: management:GET_HUMAN_TASK,
            params: {taskId}, identity: {userId: "carol", roles: ["ops"]}});
    test:assertEquals(check opsView.canAdminister, true, "an administrator sees the task and may act");
    test:assertEquals(check opsView.canComplete, true);

    json|management:Error viewer = management:executeCommand({operation: management:GET_HUMAN_TASK,
            params: {taskId}, identity: {userId: "erin", roles: ["viewer"]}});
    test:assertTrue(viewer is management:AccessDeniedError,
            "a caller who is neither audience nor administrator is refused, whatever else they may see");

    json viewerCount = check management:executeCommand({operation: management:COUNT_PENDING_HUMAN_TASKS,
            params: {}, identity: {userId: "erin", roles: ["viewer"]}});
    test:assertEquals(check viewerCount.count, 0);
    json opsCount = check management:executeCommand({operation: management:COUNT_PENDING_HUMAN_TASKS,
            params: {}, identity: {userId: "carol", roles: ["ops"]}});
    int opsPending = check (check opsCount.count).ensureType();
    test:assertTrue(opsPending >= 1);

    // An administrator completes: accepted, and recorded as an administrator's completion.
    check management:completeHumanTask(taskId, "ok", callerRoles = ["ops"], userId = "carol");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "ok by carol as administrator");
    management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
    test:assertEquals(info.completedAs, "administrator");
    test:assertEquals(info.completedBy, "carol");
}

@test:Config {groups: ["unit"]}
function testAnAudienceCompletionIsRecordedAsSuch() returns error? {
    check registerAdministeredTaskWorkflow();
    string workflowId = check run(workflowWithAdministeredTask, "ORD-ADM-002");
    runtime:sleep(1.5);
    management:HumanTaskGroup[] pending = check management:listPendingHumanTasks(workflowId);
    check management:completeHumanTask(pending[0].taskIds[0], "ok", callerRoles = ["approver"], userId = "bob");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "ok by bob as audience");
}

@test:Config {groups: ["unit"]}
function testAnAdministratorReassignsTheTask() returns error? {
    check registerAdministeredTaskWorkflow();
    string workflowId = check run(workflowWithAdministeredTask, "ORD-ADM-003");
    runtime:sleep(1.5);
    management:HumanTaskGroup[] pending = check management:listPendingHumanTasks(workflowId);
    string taskId = pending[0].taskIds[0];

    error? notAdmin = management:reassignTask(taskId, {users: ["frank"]}, callerRoles = ["approver"], userId = "bob");
    test:assertTrue(notAdmin is error, "the audience may not reassign");

    check management:reassignTask(taskId, {userRoles: [], users: ["frank"]}, callerRoles = ["ops"], userId = "carol");
    runtime:sleep(1);
    error? formerApprover = management:completeHumanTask(taskId, "ok", callerRoles = ["approver"], userId = "bob");
    test:assertTrue(formerApprover is error, "the former audience is refused after reassignment");
    check management:completeHumanTask(taskId, "ok", callerRoles = ["guest"], userId = "frank");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "ok by frank as audience");
}

@test:Config {groups: ["unit"]}
function testAnAdministratorMovesTheReviewDeadline() returns error? {
    check registerAdministeredReviewWorkflow();
    string workflowId = check run(workflowWithAdministeredReview, "ORD-ADM-004");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1);
    string taskId = pending[0].taskId;

    check management:extendTaskDeadline(taskId, 20000, callerRoles = ["ops"], userId = "carol");
    runtime:sleep(4);
    // Past the original 3s deadline, the review is still open.
    check management:completeReviewActivity(taskId, {action: "reject"}, callerRoles = ["ops"], userId = "carol");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "rejected as administrator");
}

@test:Config {groups: ["unit"]}
function testAnAdministratorClearsTheReviewDeadline() returns error? {
    check registerAdministeredReviewWorkflow();
    string workflowId = check run(workflowWithAdministeredReview, "ORD-ADM-005");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    string taskId = pending[0].taskId;

    check management:extendTaskDeadline(taskId, (), callerRoles = ["ops"], userId = "carol");
    runtime:sleep(4);
    check management:completeReviewActivity(taskId, {action: "reject"}, callerRoles = ["approver"], userId = "bob");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "rejected as audience");
}
