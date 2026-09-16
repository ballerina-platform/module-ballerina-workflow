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

@Activity
function gatedEchoActivity(string orderId) returns string|error {
    return "ok:" + orderId;
}

// The activity runs only once a person proceeds; a rejection is a typed error.
@Workflow
function workflowWithApprovalGate(Context ctx, string orderId) returns string|error {
    string|error result = ctx->callActivity(gatedEchoActivity, {"orderId": orderId},
            approvalPolicy = {userRoles: "manager"});
    if result is ReviewRejectedError {
        return "rejected by " + (result.detail().rejectedBy ?: "nobody") + " at " + result.detail().trigger;
    }
    return result;
}

// Automatic attempts first, then a person; the original failure rides as the cause.
@Workflow
function workflowWithRetryBeforeReview(Context ctx, string orderId) returns string|error {
    string|error result = ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = {maxRetries: 1, retryDelay: 0.1, userRoles: "manager"});
    if result is ReviewRejectedError {
        ReviewDecisionRecord? decision = ctx.lastReviewDecision();
        string action = decision is () ? "none" : decision.action;
        return "reviewed:" + action + ":" + (result.cause() is error ? "caused" : "uncaused");
    }
    return result;
}

// A review that names nobody is refused before anything runs.
@Workflow
function workflowWithNobodyToReview(Context ctx, string orderId) returns string|error {
    string result = check ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = {userRoles: (), title: "Nobody can answer this"});
    return result;
}

// Retries before a review nobody can answer: the review was declared, so it is refused, not skipped.
@Workflow
function workflowWithRetriesThenNobody(Context ctx, string orderId) returns string|error {
    string result = check ctx->callActivity(failingActivityForRetry, {"orderId": orderId},
            retryPolicy = {maxRetries: 1, retryDelay: 0.1, userRoles: ()});
    return result;
}

// The workflow learns who completed its task, so a later task can exclude or prefer them.
@Workflow
function workflowThatRemembersTheCompleter(Context ctx, string orderId) returns string|error {
    string _ = check ctx->awaitHumanTask("firstSignoff", {"orderId": orderId}, userRoles = "manager");
    HumanTaskCompletion? completion = ctx.lastHumanTaskCompletion("firstSignoff");
    return completion is () ? "unknown" : (completion.completedBy ?: "anonymous");
}

// Assigned to a user, closed to a role.
@Workflow
function workflowAssignedToAUser(Context ctx, string orderId) returns string|error {
    string result = check ctx->awaitHumanTask("namedSignoff", {"orderId": orderId},
            userRoles = (), users = "alice", excludedRoles = "intern");
    return result;
}

@test:Config {groups: ["unit"]}
function testApprovalGateProceedRunsTheActivity() returns error? {
    map<function> activities = {"gatedEchoActivity": gatedEchoActivity};
    _ = check registerWorkflowForTest(workflowWithApprovalGate, "workflowWithApprovalGate", activities);

    string workflowId = check run(workflowWithApprovalGate, "ORD-AG-001");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1, "the gate raises one review before the activity runs");
    test:assertEquals(pending[0].trigger, "PRE_RUN");

    check management:completeReviewActivity(pending[0].taskId, {action: "proceed"},
            callerRoles = ["manager"], userId = "alice");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "ok:ORD-AG-001");
}

@test:Config {groups: ["unit"]}
function testApprovalGateRejectionIsTyped() returns error? {
    // Registered by the proceed test when it runs first; a repeat registration is an error.
    map<function> activities = {"gatedEchoActivity": gatedEchoActivity};
    boolean|error registered = registerWorkflowForTest(workflowWithApprovalGate, "workflowWithApprovalGate",
            activities);
    test:assertTrue(registered is boolean || registered.message().includes("already registered"));

    string workflowId = check run(workflowWithApprovalGate, "ORD-AG-002");
    runtime:sleep(1.5);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1);

    check management:completeReviewActivity(pending[0].taskId, {action: "reject", feedback: "not today"},
            callerRoles = ["manager"], userId = "alice");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "rejected by alice at PRE_RUN");
}

@test:Config {groups: ["unit"]}
function testRetryBeforeReviewRaisesAReviewAfterTheRetries() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check registerWorkflowForTest(workflowWithRetryBeforeReview, "workflowWithRetryBeforeReview", activities);

    string workflowId = check run(workflowWithRetryBeforeReview, "ORD-RBR-001");
    runtime:sleep(3);
    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 1, "the review is raised once the automatic attempts are spent");
    test:assertEquals(pending[0].trigger, "ON_FAILURE");

    check management:completeReviewActivity(pending[0].taskId, {action: "reject"},
            callerRoles = ["manager"], userId = "alice");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "reviewed:reject:caused");
}

@test:Config {groups: ["unit"]}
function testReviewDefinitionMustNameAnAudience() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check registerWorkflowForTest(workflowWithNobodyToReview, "workflowWithNobodyToReview", activities);

    string workflowId = check run(workflowWithNobodyToReview, "ORD-NB-001");
    anydata|error result = getWorkflowResult(workflowId, 20);
    test:assertTrue(result is error, "a review nobody can answer is refused");
    if result is error {
        test:assertTrue(result.message().includes("must name 'userRoles' or 'users'"), result.message());
    }
}

@test:Config {groups: ["unit"]}
function testRetriesThenNobodyIsRefused() returns error? {
    map<function> activities = {"failingActivityForRetry": failingActivityForRetry};
    _ = check registerWorkflowForTest(workflowWithRetriesThenNobody, "workflowWithRetriesThenNobody", activities);

    string workflowId = check run(workflowWithRetriesThenNobody, "ORD-RN-001");
    anydata|error result = getWorkflowResult(workflowId, 20);
    test:assertTrue(result is error, "a retry policy naming nobody to review is refused, not run as AutoRetry");
    if result is error {
        test:assertTrue(result.message().includes("must name 'userRoles' or 'users'"), result.message());
    }
}

@test:Config {groups: ["unit"]}
function testWorkflowLearnsWhoCompletedItsTask() returns error? {
    _ = check registerWorkflowForTest(workflowThatRemembersTheCompleter, "workflowThatRemembersTheCompleter");

    string workflowId = check run(workflowThatRemembersTheCompleter, "ORD-LC-001");
    runtime:sleep(1.5);
    management:HumanTaskGroup[] pending = check management:listPendingHumanTasks(workflowId);
    test:assertEquals(pending.length(), 1);

    check management:completeHumanTask(pending[0].taskIds[0], "signed", callerRoles = ["manager"], userId = "bob");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "bob");
}

@test:Config {groups: ["unit"]}
function testUsersAndExcludedRolesDecideWhoMayComplete() returns error? {
    _ = check registerWorkflowForTest(workflowAssignedToAUser, "workflowAssignedToAUser");

    string workflowId = check run(workflowAssignedToAUser, "ORD-AU-001");
    runtime:sleep(1.5);
    management:HumanTaskGroup[] pending = check management:listPendingHumanTasks(workflowId);
    test:assertEquals(pending.length(), 1);
    string taskId = pending[0].taskIds[0];

    error? wrongUser = management:completeHumanTask(taskId, "no", callerRoles = ["manager"], userId = "bob");
    test:assertTrue(wrongUser is error, "a caller who is not the named user may not complete it");
    error? noIdentity = management:completeHumanTask(taskId, "no", callerRoles = ["manager"]);
    test:assertTrue(noIdentity is error, "a task assigned to users needs a user id to decide");
    error? excludedRole = management:completeHumanTask(taskId, "no", callerRoles = ["intern"], userId = "alice");
    test:assertTrue(excludedRole is error, "an excluded role denies even the named user");

    check management:completeHumanTask(taskId, "yes", callerRoles = ["guest"], userId = "alice");
    anydata result = check getWorkflowResult(workflowId, 20);
    test:assertEquals(result, "yes");
}
