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

import ballerina/test;

// ── Task row token ────────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testTaskRowTokenRoundTrips() {
    test:assertEquals(decodeTaskRowToken(encodeTaskRowToken(3, "c2VydmVyLXRva2Vu")), [3, "c2VydmVyLXRva2Vu"]);
    test:assertEquals(decodeTaskRowToken(encodeTaskRowToken(2, ())), [2, ()], "a part-read first page has no server token");
    test:assertEquals(decodeTaskRowToken(()), [0, ()]);
}

@test:Config {groups: ["unit"]}
function testUnreadableTaskRowTokenStartsOver() {
    test:assertEquals(decodeTaskRowToken("2026-06-01T10:00:00Z~humantask-wf-task-abc"), [0, ()],
        "a cursor from before 1.0.1 starts the listing over");
    test:assertEquals(decodeTaskRowToken("42"), [0, ()]);
    test:assertEquals(decodeTaskRowToken("-1~abc"), [0, ()]);
}

// ── eligible ──────────────────────────────────────────────────────────────────

final Assignment ROLE_TASK = {userRoles: ["approver"], users: [], excludedUsers: [], excludedRoles: []};
final Assignment USERS_TASK = {userRoles: [], users: ["alice"], excludedUsers: [], excludedRoles: []};
final Assignment LADDER_TASK = {userRoles: ["approver"], users: [], excludedUsers: ["alice"], excludedRoles: ["auditor"]};

@test:Config {groups: ["unit"]}
function testEligibleByRole() {
    test:assertTrue(eligible(ROLE_TASK, ["approver"], ()));
    test:assertFalse(eligible(ROLE_TASK, ["viewer"], "alice"));
    test:assertFalse(eligible(ROLE_TASK, (), ()), "no identity sees nothing");
}

@test:Config {groups: ["unit"]}
function testEligibleByUser() {
    test:assertTrue(eligible(USERS_TASK, ["viewer"], "alice"));
    test:assertTrue(eligible(USERS_TASK, (), "alice"));
    test:assertFalse(eligible(USERS_TASK, ["approver"], ()), "a task assigned to users needs a user id");
    test:assertFalse(eligible(USERS_TASK, ["approver"], "bob"));
}

@test:Config {groups: ["unit"]}
function testEligibleHonoursExclusions() {
    test:assertFalse(eligible(LADDER_TASK, ["approver"], "alice"), "excluded user");
    test:assertFalse(eligible(LADDER_TASK, ["approver", "auditor"], "bob"), "excluded role");
    test:assertFalse(eligible(LADDER_TASK, ["approver"], ()), "exclusions by user need a user id");
    test:assertTrue(eligible(LADDER_TASK, ["approver"], "bob"));
}

final Assignment ADMINISTERED_TASK = {userRoles: ["approver"], users: [], excludedUsers: ["alice"], excludedRoles: [],
    administratorRoles: ["ops"], administratorUsers: ["dana"]};

@test:Config {groups: ["unit"]}
function testAccessTellsAudienceFromAdministrators() {
    test:assertEquals(accessOf(ADMINISTERED_TASK, ["approver"], "bob"), AUDIENCE);
    test:assertEquals(accessOf(ADMINISTERED_TASK, ["ops"], "carol"), ADMINISTRATOR);
    test:assertEquals(accessOf(ADMINISTERED_TASK, ["viewer"], "dana"), ADMINISTRATOR);
    test:assertEquals(accessOf(ADMINISTERED_TASK, ["viewer"], "erin"), NONE, "a viewer sees nothing");
    test:assertEquals(accessOf(ADMINISTERED_TASK, ["approver", "ops"], "alice"), ADMINISTRATOR,
            "an excluded administrator still administers");
    test:assertEquals(accessOf(ADMINISTERED_TASK, (), ()), NONE);
}

@test:Config {groups: ["unit"]}
function testEligibleOpenAudience() {
    Assignment open = {userRoles: [], users: [], excludedUsers: [], excludedRoles: ["auditor"]};
    test:assertTrue(eligible(open, [], "bob"));
    test:assertFalse(eligible(open, ["auditor"], "bob"));
}

// ── acceptTaskRow ─────────────────────────────────────────────────────────────

function mkHumanTask(string taskId, string startTime) returns HumanTaskSummary =>
    {kind: "HUMAN_TASK", taskId, taskName: "review", parentWorkflowId: "parent", parentWorkflowType: (),
     status: "PENDING", startTime, closeTime: (), userRoles: ["admin"]};

function mkReviewActivity(string taskId, string startTime) returns ReviewActivitySummary =>
    {kind: "REVIEW_ACTIVITY", taskId, taskName: "orders.retryOrder", activityName: "processOrder",
     parentWorkflowId: "parent", trigger: "ON_FAILURE",
     title: "Review failed activity: processOrder", status: "PENDING", startTime, closeTime: (),
     userRoles: []};

@test:Config {groups: ["unit"]}
function testAcceptTaskRowKeepsOnlyWhatTheCallerMayAct() {
    HumanTaskSummary task = mkHumanTask("t1", "2026-06-01T10:00:00Z");
    test:assertTrue(acceptTaskRow(task, {callerRoles: ["admin"]}));
    test:assertTrue(task.canComplete);
    test:assertFalse(acceptTaskRow(mkHumanTask("t2", "2026-06-01T10:00:00Z"), {callerRoles: ["viewer"]}));

    HumanTaskSummary seenByAll = mkHumanTask("t3", "2026-06-01T10:00:00Z");
    test:assertTrue(acceptTaskRow(seenByAll, {callerRoles: ["viewer"], all: true}),
        "`all` keeps a row the caller may not act on");
    test:assertFalse(seenByAll.canComplete);
}

@test:Config {groups: ["unit"]}
function testAcceptTaskRowAppliesTheMemoFilters() {
    string at = "2026-06-01T10:00:00Z";
    test:assertFalse(acceptTaskRow(mkHumanTask("t1", at), {callerRoles: ["admin"], parentWorkflowId: "other"}));
    test:assertFalse(acceptTaskRow(mkHumanTask("t2", at), {callerRoles: ["admin"], taskName: "approve"}));
    test:assertFalse(acceptTaskRow(mkHumanTask("t3", at), {callerRoles: ["admin"], userRole: "approver"}));
    test:assertTrue(acceptTaskRow(mkHumanTask("t4", at), {callerRoles: ["admin"], userRole: "admin"}));
}

@test:Config {groups: ["unit"]}
function testAcceptTaskRowReadsAReviewParentTypeFromItsTaskName() {
    ReviewActivitySummary review = mkReviewActivity("r1", "2026-06-01T10:00:00Z");
    test:assertTrue(acceptTaskRow(review, {parentWorkflowType: "orders"}),
        "a review that recorded no parent type is matched on its qualified task name");
    test:assertTrue(review.canComplete, "a review that declares no roles is open by default");
    test:assertFalse(acceptTaskRow(mkReviewActivity("r2", "2026-06-01T10:00:00Z"), {parentWorkflowType: "billing"}));
}

// ── clampLimit ────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testClampLimitBelowMinReturnsDefault() {
    test:assertEquals(clampLimit(0, 100), 20, "Below-min request should return default 20");
}

@test:Config {groups: ["unit"]}
function testClampLimitNegativeReturnsDefault() {
    test:assertEquals(clampLimit(-5, 100), 20, "Negative request should return default 20");
}

@test:Config {groups: ["unit"]}
function testClampLimitWithinRange() {
    test:assertEquals(clampLimit(50, 100), 50, "In-range request should be returned as-is");
}

@test:Config {groups: ["unit"]}
function testClampLimitExceedsMax() {
    test:assertEquals(clampLimit(200, 100), 100, "Above-max request should be capped at max");
}

@test:Config {groups: ["unit"]}
function testClampLimitAtMax() {
    test:assertEquals(clampLimit(100, 100), 100, "Request equal to max should be accepted");
}

// ── parseRolesHeader ──────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testParseRolesHeaderNull() {
    test:assertTrue(parseRolesHeader(()) is (), "Null header should return ()");
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderEmptyString() {
    test:assertTrue(parseRolesHeader("") is (), "Empty header should return ()");
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderBlankString() {
    test:assertTrue(parseRolesHeader("   ") is (), "Blank-only header should return ()");
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderSingleRole() {
    [string, string...]? result = parseRolesHeader("admin");
    test:assertFalse(result is (), "Single role should not be null");
    if result is [string, string...] {
        test:assertEquals(result[0], "admin");
        test:assertEquals(result.length(), 1);
    }
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderMultipleRoles() {
    [string, string...]? result = parseRolesHeader("admin,manager,ops");
    test:assertFalse(result is (), "Multiple roles should not be null");
    if result is [string, string...] {
        test:assertEquals(result[0], "admin");
        test:assertEquals(result.length(), 3);
    }
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderTrimsSpaces() {
    [string, string...]? result = parseRolesHeader(" admin , manager ");
    test:assertFalse(result is (), "Roles with surrounding spaces should still parse");
    if result is [string, string...] {
        test:assertEquals(result[0], "admin");
        test:assertEquals(result[1], "manager");
    }
}

@test:Config {groups: ["unit"]}
function testParseRolesHeaderSkipsBlankEntries() {
    // A header like "admin,,ops" should skip the blank middle entry
    [string, string...]? result = parseRolesHeader("admin,,ops");
    test:assertFalse(result is (), "Blank-entry header should still parse valid roles");
    if result is [string, string...] {
        test:assertEquals(result.length(), 2, "Blank entry should be skipped");
    }
}

// ── buildCompletionResponse ───────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testBuildCompletionResponseWithUserId() {
    CompletionInfo info = buildCompletionResponse("alice@example.com");
    test:assertTrue(info.success, "success must be true");
    test:assertEquals(info.completedBy, "alice@example.com");
    test:assertFalse(info.completedAt == "", "completedAt should be populated");
}

@test:Config {groups: ["unit"]}
function testBuildCompletionResponseNoUserId() {
    CompletionInfo info = buildCompletionResponse(());
    test:assertTrue(info.success, "success must be true");
    test:assertEquals(info.completedBy, "unknown", "missing userId should fall back to 'unknown'");
}

// ── buildReviewDecisionResponse ────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testBuildReviewDecisionResponseWithUserId() {
    ReviewDecisionInfo info = buildReviewDecisionResponse("proceed", "bob@example.com");
    test:assertTrue(info.success, "success must be true");
    test:assertEquals(info.decision, "proceed");
    test:assertEquals(info.completedBy, "bob@example.com");
    test:assertFalse(info.completedAt == "", "completedAt should be populated");
}

@test:Config {groups: ["unit"]}
function testBuildReviewDecisionResponseNoUserId() {
    ReviewDecisionInfo info = buildReviewDecisionResponse("reject", ());
    test:assertEquals(info.completedBy, "unknown", "missing userId should fall back to 'unknown'");
    test:assertEquals(info.decision, "reject");
}

// ── Runtime error classification ──────────────────────────────────────────────
// The runtime reports failures as plain errors whose message carries the reason.
// classifyRuntimeError turns those into the management error types; mapping those
// to a transport's vocabulary (status codes and the like) is the adapter's job.

@test:Config {groups: ["unit"]}
function testClassifyNotFound() {
    test:assertTrue(classifyRuntimeError(error("workflow not found in Temporal")) is NotFoundError,
        "A missing target must classify as NotFoundError");
}

@test:Config {groups: ["unit"]}
function testClassifyForeignTaskQueueIsAccessDenied() {
    error err = error("Unauthorized: human task 'humantask-x' belongs to task queue 'other-q', " +
        "which is served by a different integration");
    test:assertTrue(classifyRuntimeError(err) is AccessDeniedError,
        "A task served by a different integration must classify as AccessDeniedError");
}

@test:Config {groups: ["unit"]}
function testClassifyNotAuthorizedIsAccessDenied() {
    test:assertTrue(classifyRuntimeError(error("not authorized to complete this task")) is AccessDeniedError,
        "A not-authorized message must classify as AccessDeniedError");
}

@test:Config {groups: ["unit"]}
function testClassifyAlreadyCompletedIsConflict() {
    test:assertTrue(classifyRuntimeError(error("task is already completed")) is ConflictError,
        "An already-completed task must classify as ConflictError");
}

@test:Config {groups: ["unit"]}
function testClassifyNotRunningIsConflict() {
    test:assertTrue(classifyRuntimeError(error("task is not running")) is ConflictError,
        "A task that is not running must classify as ConflictError");
}

@test:Config {groups: ["unit"]}
function testClassifyInvalidPayload() {
    // A payload that does not match the task's declared result type is semantically
    // invalid rather than malformed.
    error err = error("Invalid payload for human task 'order.approve': " +
            "'string' value 'oops' cannot be converted to 'ApprovalDecision'");
    test:assertTrue(classifyRuntimeError(err) is InvalidPayloadError,
        "A mismatched payload must classify as InvalidPayloadError");
}

@test:Config {groups: ["unit"]}
function testClassifyUnknownIsExecutionError() {
    test:assertTrue(classifyRuntimeError(error("unexpected Temporal gRPC error")) is ExecutionError,
        "An unrecognized runtime failure must classify as ExecutionError");
}

@test:Config {groups: ["unit"]}
function testNotFoundOrExecutionErrorKeepsCallerMessage() {
    Error err = notFoundOrExecutionError(error("NOT_FOUND"), "Workflow not found: wf-1");
    test:assertTrue(err is NotFoundError);
    test:assertEquals(err.message(), "Workflow not found: wf-1",
        "The operation's own not-found message must be used");
}

@test:Config {groups: ["unit"]}
function testNotFoundOrExecutionErrorPrefixesFailures() {
    Error err = notFoundOrExecutionError(error("boom"), "Workflow not found: wf-1",
            "Failed to get history: ");
    test:assertTrue(err is ExecutionError);
    test:assertEquals(err.message(), "Failed to get history: boom");
}

@test:Config {groups: ["unit"]}
function testNotFoundOrExecutionErrorIgnoresTaskSemantics() {
    // Lifecycle operations distinguish only missing-vs-failed: an "Unauthorized"
    // message from the runtime is still a failure, not an access decision.
    test:assertTrue(notFoundOrExecutionError(error("Unauthorized: something")) is ExecutionError,
        "Lifecycle classification must not infer access decisions");
}
