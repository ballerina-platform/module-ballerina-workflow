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

import ballerina/http;
import ballerina/test;

// ── Cursor token encode / decode ──────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testEncodeCursorToken() {
    string token = encodeCursorToken("2026-06-01T10:00:00Z", "humantask-wf-task-abc");
    test:assertEquals(token, "2026-06-01T10:00:00Z~humantask-wf-task-abc");
}

@test:Config {groups: ["unit"]}
function testDecodeCursorToken() {
    [string, string] cursor = decodeCursorToken("2026-06-01T10:00:00Z~humantask-wf-task-abc");
    test:assertEquals(cursor[0], "2026-06-01T10:00:00Z");
    test:assertEquals(cursor[1], "humantask-wf-task-abc");
}

@test:Config {groups: ["unit"]}
function testDecodeCursorTokenNoTilde() {
    // Old numeric offset tokens (before cursor-based pagination) → treat as no cursor
    [string, string] cursor = decodeCursorToken("42");
    test:assertEquals(cursor[0], "", "No tilde should yield empty cursorTime");
    test:assertEquals(cursor[1], "", "No tilde should yield empty cursorId");
}

@test:Config {groups: ["unit"]}
function testDecodeCursorTokenEmptyString() {
    [string, string] cursor = decodeCursorToken("");
    test:assertEquals(cursor[0], "");
    test:assertEquals(cursor[1], "");
}

@test:Config {groups: ["unit"]}
function testDecodeCursorTokenTaskIdWithTilde() {
    // taskId itself contains a tilde (e.g. parent workflow ID has ~).
    // encode/decode must round-trip correctly by splitting on the FIRST tilde.
    string taskId = "humantask-my~workflow-taskname-uuid";
    string token = encodeCursorToken("2026-06-01T10:00:00Z", taskId);
    [string, string] cursor = decodeCursorToken(token);
    test:assertEquals(cursor[0], "2026-06-01T10:00:00Z");
    test:assertEquals(cursor[1], taskId, "Full taskId including embedded tilde should round-trip");
}

// ── paginateHumanTasks ────────────────────────────────────────────────────────

// Helper creates a minimal HumanTaskSummary for test data.
function mkHumanTask(string taskId, string startTime) returns HumanTaskSummary =>
    {taskId, taskName: "review", parentWorkflowId: "parent", parentWorkflowType: (),
     status: "PENDING", startTime, closeTime: (), userRoles: ["admin"]};

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksEmpty() {
    HumanTaskPage page = paginateHumanTasks([], 10, ());
    test:assertEquals(page.items.length(), 0);
    test:assertTrue(page.nextPageToken is ());
    test:assertFalse(page.hasMore);
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksAllOnOnePage() {
    HumanTaskSummary[] items = [
        mkHumanTask("task-b", "2026-06-01T11:00:00Z"),
        mkHumanTask("task-a", "2026-06-01T10:00:00Z")
    ];
    HumanTaskPage page = paginateHumanTasks(items, 10, ());
    test:assertEquals(page.items.length(), 2, "Both items should be on one page");
    test:assertFalse(page.hasMore);
    test:assertTrue(page.nextPageToken is ());
    // Sorted ascending by startTime
    test:assertEquals(page.items[0].taskId, "task-a");
    test:assertEquals(page.items[1].taskId, "task-b");
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksFirstPageHasMore() {
    HumanTaskSummary[] items = [
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-b", "2026-06-01T11:00:00Z"),
        mkHumanTask("task-c", "2026-06-01T12:00:00Z")
    ];
    HumanTaskPage page = paginateHumanTasks(items, 2, ());
    test:assertEquals(page.items.length(), 2);
    test:assertTrue(page.hasMore);
    test:assertFalse(page.nextPageToken is ());
    test:assertEquals(page.items[0].taskId, "task-a");
    test:assertEquals(page.items[1].taskId, "task-b");
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksSecondPageViaToken() {
    HumanTaskSummary[] items = [
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-b", "2026-06-01T11:00:00Z"),
        mkHumanTask("task-c", "2026-06-01T12:00:00Z")
    ];
    HumanTaskPage page1 = paginateHumanTasks(items, 2, ());
    HumanTaskPage page2 = paginateHumanTasks(items, 2, page1.nextPageToken);
    test:assertEquals(page2.items.length(), 1, "Second page should have the remaining item");
    test:assertFalse(page2.hasMore);
    test:assertEquals(page2.items[0].taskId, "task-c");
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksTiebreakByTaskId() {
    // Items with identical startTime are ordered lexicographically by taskId
    HumanTaskSummary[] items = [
        mkHumanTask("task-z", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-m", "2026-06-01T10:00:00Z")
    ];
    HumanTaskPage page = paginateHumanTasks(items, 10, ());
    test:assertEquals(page.items[0].taskId, "task-a");
    test:assertEquals(page.items[1].taskId, "task-m");
    test:assertEquals(page.items[2].taskId, "task-z");
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksCursorStabilityOnNewItem() {
    // A new (later) task inserted between page 1 and page 2 requests must NOT
    // cause page-1 items to re-appear on page 2.
    HumanTaskSummary[] originalItems = [
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-b", "2026-06-01T11:00:00Z"),
        mkHumanTask("task-c", "2026-06-01T12:00:00Z")
    ];
    HumanTaskPage page1 = paginateHumanTasks(originalItems, 2, ());
    string? cursor = page1.nextPageToken;

    // Simulate a later task arriving before the next request
    HumanTaskSummary[] updatedItems = [
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-b", "2026-06-01T11:00:00Z"),
        mkHumanTask("task-c", "2026-06-01T12:00:00Z"),
        mkHumanTask("task-d", "2026-06-01T13:00:00Z")
    ];
    HumanTaskPage page2 = paginateHumanTasks(updatedItems, 2, cursor);
    // Page 2 must start after task-b (the last item on page 1)
    HumanTaskSummary[] page1Dupes = page2.items.filter(t => t.taskId == "task-a" || t.taskId == "task-b");
    test:assertEquals(page1Dupes.length(), 0, "Page-1 items must not re-appear on page 2");
    test:assertEquals(page2.items[0].taskId, "task-c");
}

@test:Config {groups: ["unit"]}
function testPaginateHumanTasksOldOffsetTokenRestartsFromBeginning() {
    // An old numeric offset token (e.g. "20") cannot be decoded as a cursor.
    // Pagination must restart from the beginning rather than crashing.
    HumanTaskSummary[] items = [
        mkHumanTask("task-a", "2026-06-01T10:00:00Z"),
        mkHumanTask("task-b", "2026-06-01T11:00:00Z")
    ];
    HumanTaskPage page = paginateHumanTasks(items, 10, "20");
    test:assertEquals(page.items.length(), 2, "Unrecognised token should restart from beginning");
    test:assertEquals(page.items[0].taskId, "task-a");
}

// ── paginateRetryTasks ────────────────────────────────────────────────────────

function mkRetryTask(string taskId, string startTime) returns RetryTaskSummary =>
    {taskId, taskName: "retryOrder", activityName: "processOrder",
     parentWorkflowId: "parent", status: "PENDING", startTime, closeTime: ()};

@test:Config {groups: ["unit"]}
function testPaginateRetryTasksEmpty() {
    RetryTaskPage page = paginateRetryTasks([], 10, ());
    test:assertEquals(page.items.length(), 0);
    test:assertTrue(page.nextPageToken is ());
    test:assertFalse(page.hasMore);
}

@test:Config {groups: ["unit"]}
function testPaginateRetryTasksFirstAndSecondPage() {
    RetryTaskSummary[] items = [
        mkRetryTask("retry-b", "2026-06-01T11:00:00Z"),
        mkRetryTask("retry-c", "2026-06-01T12:00:00Z"),
        mkRetryTask("retry-a", "2026-06-01T10:00:00Z")
    ];
    RetryTaskPage page1 = paginateRetryTasks(items, 2, ());
    test:assertEquals(page1.items.length(), 2);
    test:assertTrue(page1.hasMore);
    test:assertEquals(page1.items[0].taskId, "retry-a");
    test:assertEquals(page1.items[1].taskId, "retry-b");

    RetryTaskPage page2 = paginateRetryTasks(items, 2, page1.nextPageToken);
    test:assertEquals(page2.items.length(), 1);
    test:assertFalse(page2.hasMore);
    test:assertEquals(page2.items[0].taskId, "retry-c");
}

@test:Config {groups: ["unit"]}
function testPaginateRetryTasksTiebreakByTaskId() {
    RetryTaskSummary[] items = [
        mkRetryTask("retry-z", "2026-06-02T10:00:00Z"),
        mkRetryTask("retry-a", "2026-06-02T10:00:00Z")
    ];
    RetryTaskPage page = paginateRetryTasks(items, 10, ());
    test:assertEquals(page.items[0].taskId, "retry-a");
    test:assertEquals(page.items[1].taskId, "retry-z");
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

// ── errorBody ─────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testErrorBodyStructure() {
    map<json> body = errorBody("something went wrong");
    test:assertTrue(body.hasKey("error"), "errorBody must have 'error' key");
    map<json> inner = <map<json>>body["error"];
    test:assertEquals(inner["message"], "something went wrong");
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

// ── buildRetryDecisionResponse ────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testBuildRetryDecisionResponseWithUserId() {
    RetryDecisionInfo info = buildRetryDecisionResponse("retry", "bob@example.com");
    test:assertTrue(info.success, "success must be true");
    test:assertEquals(info.decision, "retry");
    test:assertEquals(info.decidedBy, "bob@example.com");
    test:assertFalse(info.decidedAt == "", "decidedAt should be populated");
}

@test:Config {groups: ["unit"]}
function testBuildRetryDecisionResponseNoUserId() {
    RetryDecisionInfo info = buildRetryDecisionResponse("fail", ());
    test:assertEquals(info.decidedBy, "unknown", "missing userId should fall back to 'unknown'");
    test:assertEquals(info.decision, "fail");
}

// ── humanTaskErrorResponse ────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testHumanTaskErrorResponseNotFound() {
    error err = error("workflow not found in Temporal");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            humanTaskErrorResponse(err);
    test:assertTrue(resp is http:NotFound, "NOT_FOUND message should produce 404");
}

@test:Config {groups: ["unit"]}
function testHumanTaskErrorResponseForbidden() {
    error err = error("Unauthorized: caller role not in userRoles");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            humanTaskErrorResponse(err);
    test:assertTrue(resp is http:Forbidden, "Unauthorized message should produce 403");
}

@test:Config {groups: ["unit"]}
function testHumanTaskErrorResponseConflict() {
    error err = error("task is already completed");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            humanTaskErrorResponse(err);
    test:assertTrue(resp is http:Conflict, "already-completed message should produce 409");
}

@test:Config {groups: ["unit"]}
function testHumanTaskErrorResponseInternalError() {
    error err = error("unexpected Temporal gRPC error");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            humanTaskErrorResponse(err);
    test:assertTrue(resp is http:InternalServerError, "Unknown error should produce 500");
}

// ── retryTaskErrorResponse ────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testRetryTaskErrorResponseNotFound() {
    error err = error("workflow NOT_FOUND");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            retryTaskErrorResponse(err);
    test:assertTrue(resp is http:NotFound, "NOT_FOUND message should produce 404");
}

@test:Config {groups: ["unit"]}
function testRetryTaskErrorResponseForbidden() {
    error err = error("not authorized to complete this task");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            retryTaskErrorResponse(err);
    test:assertTrue(resp is http:Forbidden, "not-authorized message should produce 403");
}

@test:Config {groups: ["unit"]}
function testRetryTaskErrorResponseConflict() {
    error err = error("task is not running");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            retryTaskErrorResponse(err);
    test:assertTrue(resp is http:Conflict, "not-running message should produce 409");
}

@test:Config {groups: ["unit"]}
function testRetryTaskErrorResponseInternalError() {
    error err = error("some unexpected failure");
    http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError resp =
            retryTaskErrorResponse(err);
    test:assertTrue(resp is http:InternalServerError, "Unknown error should produce 500");
}
