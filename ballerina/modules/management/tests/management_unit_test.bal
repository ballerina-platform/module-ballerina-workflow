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
