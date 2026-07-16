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
// MANAGEMENT API — FUNCTION COVERAGE TESTS
// ================================================================================
//
// Tests for management API functions not exercised by other test files:
//   • terminateWorkflow / cancelWorkflow
//   • getWorkflowHistory / getActivityTree / getExecutionGraph
//   • listWorkflowInstances (with type / status / workflowId filters)
//   • startWorkflowByType
//   • getHumanTaskInfo / listAllHumanTasks / failHumanTask
//   • getReviewActivityInfo / listAllReviewActivities
//
// ================================================================================

import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// ================================================================================
// TERMINATE WORKFLOW
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testTerminateRunningWorkflow() returns error? {
    // simpleSignalWorkflow stays RUNNING until a signal arrives — use it as a long-lived target.
    string testId = uniqueId("terminate");
    SimpleSignalInput input = {id: testId, message: "terminate-test"};
    string workflowId = check workflow:run(simpleSignalWorkflow, input);

    _ = check waitForWorkflowState(workflowId, ["RUNNING"]);

    // Terminate with a reason — should succeed without error
    check management:terminateWorkflow(workflowId, "", reason = "test cleanup");

    // Poll until the workflow reaches TERMINATED
    _ = check waitForWorkflowState(workflowId, ["TERMINATED"]);
}

@test:Config {
    groups: ["integration"]
}
function testTerminateNonExistentWorkflowReturnsError() returns error? {
    error? result = management:terminateWorkflow("nonexistent-wf-xyz-terminate", "");
    test:assertTrue(result is error, "Terminating a nonexistent workflow should return an error");
}

// ================================================================================
// CANCEL WORKFLOW
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testCancelRunningWorkflow() returns error? {
    string testId = uniqueId("cancel-wf");
    SimpleSignalInput input = {id: testId, message: "cancel-test"};
    string workflowId = check workflow:run(simpleSignalWorkflow, input);

    _ = check waitForWorkflowState(workflowId, ["RUNNING"]);

    // cancelWorkflow requests graceful cancellation — should succeed without error
    check management:cancelWorkflow(workflowId, "");

    // Poll until the workflow reaches a cancelled terminal state
    _ = check waitForWorkflowState(workflowId, ["CANCELED", "CANCELLED"]);
}

@test:Config {
    groups: ["integration"]
}
function testCancelNonExistentWorkflowReturnsError() returns error? {
    error? result = management:cancelWorkflow("nonexistent-wf-xyz-cancel", "");
    test:assertTrue(result is error, "Cancelling a nonexistent workflow should return an error");
}

// ================================================================================
// EXECUTION VISUALIZATION — history, activity tree, execution graph
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowHistory() returns error? {
    string testId = uniqueId("history");
    InfoTestInput input = {id: testId, name: "HistoryUser"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:HistoryEvent[] events = check management:getWorkflowHistory(workflowId, "");
    test:assertTrue(events.length() > 0, "Completed workflow should have at least one history event");

    // Every event must have a non-empty eventType
    foreach management:HistoryEvent ev in events {
        test:assertFalse(ev.eventType == "", "Each history event must have a non-empty eventType");
    }
}

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowHistoryNonExistentReturnsError() returns error? {
    management:HistoryEvent[]|error result = management:getWorkflowHistory("nonexistent-wf-xyz-history", "");
    test:assertTrue(result is error, "History of a nonexistent workflow should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testGetActivityTree() returns error? {
    string testId = uniqueId("activity-tree");
    InfoTestInput input = {id: testId, name: "TreeUser"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ActivityTreeNode[] nodes = check management:getActivityTree(workflowId, "");
    test:assertTrue(nodes.length() > 0, "Completed workflow with one activity should have at least one tree node");
}

@test:Config {
    groups: ["integration"]
}
function testGetExecutionGraph() returns error? {
    string testId = uniqueId("exec-graph");
    InfoTestInput input = {id: testId, name: "GraphUser"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ExecutionGraph graph = check management:getExecutionGraph(workflowId, "");
    test:assertTrue(graph.nodes.length() > 0, "Execution graph should have at least one node");
}

// ================================================================================
// listWorkflowInstances — filter variants
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesByStatus() returns error? {
    // Run one workflow to completion so at least one COMPLETED instance exists
    string testId = uniqueId("list-by-status");
    InfoTestInput input = {id: testId, name: "StatusFilter"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:WorkflowInstancePage page =
            check management:listWorkflowInstances(status = "COMPLETED");
    // At least the workflow we just ran should appear
    test:assertTrue(page.items.length() > 0, "Should find at least one COMPLETED workflow");
    foreach management:WorkflowInstanceSummary item in page.items {
        test:assertEquals(item.status, "COMPLETED", "All listed items should have COMPLETED status");
    }
}

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesByWorkflowType() returns error? {
    string testId = uniqueId("list-by-type");
    InfoTestInput input = {id: testId, name: "TypeFilter"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:WorkflowInstancePage page =
            check management:listWorkflowInstances(workflowType = "infoTestWorkflow");
    test:assertTrue(page.items.length() > 0, "Filter by workflowType should find at least one instance");
    foreach management:WorkflowInstanceSummary item in page.items {
        test:assertEquals(item.workflowType, "infoTestWorkflow",
                "All returned instances should match the requested workflowType");
    }
}

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesByWorkflowId() returns error? {
    string testId = uniqueId("list-by-id");
    InfoTestInput input = {id: testId, name: "IdFilter"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    // Filter by the exact workflow ID prefix
    management:WorkflowInstancePage page =
            check management:listWorkflowInstances(workflowId = workflowId);
    test:assertTrue(page.items.length() > 0, "Filter by workflowId prefix should find the instance");
    test:assertEquals(page.items[0].workflowId, workflowId, "Returned instance should match the workflow ID");
}

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesWithLimit() returns error? {
    // Verify the limit parameter is respected
    management:WorkflowInstancePage page =
            check management:listWorkflowInstances('limit = 2);
    test:assertTrue(page.items.length() <= 2, "Returned items should not exceed the requested limit");
}

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesByStartedBy() returns error? {
    string startedBy = "integration-starter@example.com";
    string testId = uniqueId("list-by-startedBy");

    management:WorkflowHandle wfHandle = check management:startWorkflowByType(
            "infoTestWorkflow",
            {id: testId, name: "StartedByFilter"},
            (),
            (),
            startedBy);

    _ = check workflow:getWorkflowResult(wfHandle.workflowId, 30);

    // Visibility queries can be briefly stale right after completion; do bounded retries.
    boolean found = false;
    int itemCount = 0;
    foreach int _ in 0 ..< 25 {
        management:WorkflowInstancePage page =
                check management:listWorkflowInstances(startedBy = startedBy);
        itemCount = page.items.length();
        foreach management:WorkflowInstanceSummary item in page.items {
            if item.workflowId == wfHandle.workflowId {
                found = true;
                break;
            }
        }
        if found {
            break;
        }
    }
    test:assertTrue(itemCount > 0,
            "Filter by startedBy should find at least one workflow started by that user");
    test:assertTrue(found,
            "Expected startedBy filter results to include the workflow started with the matching startedBy");
}

// ================================================================================
// startWorkflowByType
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testStartWorkflowByType() returns error? {
    string testId = uniqueId("start-by-type");
    json inputJson = {id: testId, name: "TypeStart"};

    management:WorkflowHandle wfHandle = check management:startWorkflowByType(
            "infoTestWorkflow", inputJson);

    test:assertFalse(wfHandle.workflowId == "", "startWorkflowByType must return a non-empty workflowId");
    test:assertFalse(wfHandle.runId == "", "startWorkflowByType must return a non-empty runId");

    // Wait for it to complete so it doesn't leak into other tests
    _ = check workflow:getWorkflowResult(wfHandle.workflowId, 30);
}

// ================================================================================
// HUMAN TASK — getHumanTaskInfo / listAllHumanTasks / failHumanTask
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetHumanTaskInfo() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-info"),
        orderId: "ORD-INFO-001",
        amount: 300.0,
        requester: "Kate"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId, 20);
    string taskId = groups[0].taskIds[0];

    management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
    test:assertFalse(info.taskId == "", "HumanTaskInfo must have a non-empty taskId");
    test:assertFalse(info.taskName == "", "HumanTaskInfo must have a non-empty taskName");
    test:assertEquals(info.status, "PENDING", "Active human task should be PENDING");
    test:assertTrue(info.userRoles.length() > 0, "HumanTaskInfo should include at least one user role");
        test:assertTrue(info.formSchema is string,
            "formSchema must be populated for human task: " + taskId);
        if info.formSchema is string {
            string schema = <string>info.formSchema;
            test:assertTrue(schema.indexOf("approved") != (),
            "formSchema should include the 'approved' field");
            test:assertTrue(schema.indexOf("comment") != (),
            "formSchema should include the 'comment' field");
        }

    // Clean up — approve the task so the workflow completes
    check workflow:completeHumanTask(taskId, {approved: true, comment: "info-test cleanup"});
    _ = check workflow:getWorkflowResult(parentWorkflowId, 15);
}

@test:Config {
    groups: ["integration"]
}
function testGetHumanTaskInfoNonExistentReturnsError() returns error? {
    management:HumanTaskInfo|error result = management:getHumanTaskInfo("nonexistent-humantask-xyz");
    test:assertTrue(result is error, "getHumanTaskInfo for a nonexistent task should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListAllHumanTasksPending() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-list"),
        orderId: "ORD-LIST-001",
        amount: 100.0,
        requester: "Liam"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    string taskId = groups[0].taskIds[0];

    // At least the task we just created should appear in the PENDING list
    management:HumanTaskSummary[] tasks = check management:listAllHumanTasks(status = "PENDING");
    test:assertTrue(tasks.length() > 0, "listAllHumanTasks(PENDING) should find at least one task");

    boolean found = (from management:HumanTaskSummary t in tasks where t.taskId == taskId select t).length() > 0;
    test:assertTrue(found, "The newly created task should appear in the PENDING list");

    // Clean up
    check workflow:completeHumanTask(taskId, {approved: true, comment: "list-test cleanup"});
    _ = check workflow:getWorkflowResult(parentWorkflowId, 15);
}

@test:Config {
    groups: ["integration"]
}
function testListAllHumanTasksNoFilter() returns error? {
    // Calling with no filters should not error; result may be empty if no tasks exist
    management:HumanTaskSummary[]|error result = management:listAllHumanTasks();
    test:assertFalse(result is error, "listAllHumanTasks() with no filters should not return an error");
}

@test:Config {
    groups: ["integration"]
}
function testFailHumanTask() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-fail"),
        orderId: "ORD-FAIL-001",
        amount: 750.0,
        requester: "Mia"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    string taskId = groups[0].taskIds[0];

    // failHumanTask sends a rejection signal to the waiting workflow task
    error? failResult = management:failHumanTask(taskId, "Missing supporting documents",
            details = {"missingDocs": ["receipt"]});
    test:assertTrue(failResult is (), "failHumanTask should succeed for a valid pending task");

    // Poll until the workflow transitions out of RUNNING (any terminal state)
    _ = check waitForWorkflowState(parentWorkflowId, ["FAILED", "COMPLETED", "CANCELED", "CANCELLED", "TERMINATED"]);
}

// ================================================================================
// RETRY TASK — getReviewActivityInfo / listAllReviewActivities
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetReviewActivityInfo() returns error? {
    string testId = uniqueId("retry-info");
    RetryActivityInput input = {id: testId, mode: "manual_retry_fail"};
    string workflowId = check workflow:run(manualRetryFailDecisionWorkflow, input);

    management:ReviewActivitySummary retryTask = check waitForPendingRetryTask(workflowId);

    management:ReviewActivityInfo info = check management:getReviewActivityInfo(retryTask.taskId);
    test:assertFalse(info.taskId == "", "ReviewActivityInfo must have a non-empty taskId");
    test:assertFalse(info.activityName == "", "ReviewActivityInfo must have a non-empty activityName");
    test:assertFalse(info.errorMessage == "", "ReviewActivityInfo should capture the activity error message");
    test:assertEquals(info.status, "PENDING", "Active review activity should be PENDING");
    test:assertEquals(info.trigger, "ON_FAILURE", "A manual-retry review is failure-triggered");
    test:assertTrue(info.title.includes("failed activity"),
            "Title should indicate this reviews a failed activity, got: " + info.title);
    test:assertTrue(info.description.includes(info.errorMessage),
            "Description should include the failure message");
    // ballerina-library#8895 — the input schema for proceed-with-input must be served.
    test:assertTrue(info.formSchema is string, "ReviewActivityInfo must include a formSchema");
    if info.formSchema is string {
        string schema = <string>info.formSchema;
        test:assertTrue(schema.includes("properties"),
                "formSchema should be a JSON Schema object, got: " + schema);
        test:assertTrue(schema.includes("\"message\""),
                "formSchema should describe the editable activity argument 'message', got: " + schema);
    }

    // Clean up — decide fail so the workflow terminates (workflow itself will also error out)
    check management:completeReviewActivity(retryTask.taskId, {action: "reject"});
    do {
        _ = check workflow:getWorkflowResult(workflowId, 15);
    } on fail {
        // expected — the workflow fails when the retry task action is "fail"
    }
}

@test:Config {
    groups: ["integration"]
}
function testGetReviewActivityInfoNonExistentReturnsError() returns error? {
    management:ReviewActivityInfo|error result = management:getReviewActivityInfo("nonexistent-retrytask-xyz");
    test:assertTrue(result is error, "getReviewActivityInfo for a nonexistent task should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksPending() returns error? {
    string testId = uniqueId("retry-list");
    RetryActivityInput input = {id: testId, mode: "manual_retry_input"};
    string workflowId = check workflow:run(manualRetryWithInputWorkflow, input);

    management:ReviewActivitySummary expected = check waitForPendingRetryTask(workflowId);

    management:ReviewActivitySummary[] tasks = check management:listAllReviewActivities(status = "PENDING");
    test:assertTrue(tasks.length() > 0, "listAllReviewActivities(PENDING) should find at least one task");

    boolean found = (from management:ReviewActivitySummary t in tasks where t.taskId == expected.taskId select t).length() > 0;
    test:assertTrue(found, "The newly created retry task should appear in the PENDING list");

    // Clean up
    check management:completeReviewActivity(expected.taskId, {action: "proceed-with-input", input: {mode: "ok"}});
    _ = check workflow:getWorkflowResult(workflowId, 30);
}

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksNoFilter() returns error? {
    management:ReviewActivitySummary[]|error result = management:listAllReviewActivities();
    test:assertFalse(result is error, "listAllReviewActivities() with no filters should not return an error");
}

// ================================================================================
// listPendingHumanTasks
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListPendingHumanTasks() returns error? {
    ExpenseRequest input = {
        id: uniqueId("list-pending-ht"),
        orderId: "ORD-PENDING-001",
        amount: 200.0,
        requester: "Alice"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);

    management:HumanTaskGroup[] pendingGroups = check management:listPendingHumanTasks(parentWorkflowId);
    test:assertTrue(pendingGroups.length() > 0,
            "listPendingHumanTasks should find pending tasks for the parent workflow");
    test:assertEquals(pendingGroups[0].taskName, groups[0].taskName,
            "Task name should match what waitForPendingHumanTask returned");

    // Clean up
    check workflow:completeHumanTask(groups[0].taskIds[0], {approved: true, comment: "cleanup"});
    _ = check workflow:getWorkflowResult(parentWorkflowId, 15);
}

@test:Config {
    groups: ["integration"]
}
function testListPendingHumanTasksNoTasks() returns error? {
    // A completed workflow has no pending tasks
    string testId = uniqueId("no-pending-ht");
    InfoTestInput input = {id: testId, name: "NoPending"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:HumanTaskGroup[] groups = check management:listPendingHumanTasks(workflowId);
    test:assertEquals(groups.length(), 0, "Completed workflow should have no pending human tasks");
}

// ================================================================================
// listPendingReviewActivities
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListPendingRetryTasks() returns error? {
    string testId = uniqueId("list-pending-rt");
    RetryActivityInput input = {id: testId, mode: "manual_retry_input"};
    string workflowId = check workflow:run(manualRetryWithInputWorkflow, input);
    management:ReviewActivitySummary expected = check waitForPendingRetryTask(workflowId);

    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertTrue(pending.length() > 0,
            "listPendingReviewActivities should find pending tasks for the parent workflow");

    boolean found = (from management:ReviewActivitySummary t in pending
                     where t.taskId == expected.taskId select t).length() > 0;
    test:assertTrue(found, "The pending retry task should appear in the list");

    // Clean up
    check management:completeReviewActivity(expected.taskId, {action: "proceed-with-input", input: {mode: "ok"}});
    _ = check workflow:getWorkflowResult(workflowId, 30);
}

@test:Config {
    groups: ["integration"]
}
function testListPendingRetryTasksNoTasks() returns error? {
    // A completed workflow has no pending retry tasks
    string testId = uniqueId("no-pending-rt");
    InfoTestInput input = {id: testId, name: "NoPendingRetry"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ReviewActivitySummary[] pending = check management:listPendingReviewActivities(workflowId);
    test:assertEquals(pending.length(), 0, "Completed workflow should have no pending retry tasks");
}

// ================================================================================
// listWorkflowInstances — time filter
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesWithStartTimeFilter() returns error? {
    string testId = uniqueId("time-filter");
    InfoTestInput input = {id: testId, name: "TimeFilter"};
    string workflowId = check workflow:run(infoTestWorkflow, input);
    _ = check workflow:getWorkflowResult(workflowId, 30);

    // A far-past startTimeFrom should include any workflow created recently
    management:WorkflowInstancePage page =
            check management:listWorkflowInstances(startTimeFrom = "2024-01-01T00:00:00Z");
    test:assertTrue(page.items.length() > 0,
            "startTimeFrom filter with a far-past date should return results");
}

// ================================================================================
// listAllHumanTasks — COMPLETED filter
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListAllHumanTasksCompleted() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-completed"),
        orderId: "ORD-COMP-001",
        amount: 150.0,
        requester: "Carol"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    string taskId = groups[0].taskIds[0];

    // Complete the task
    check workflow:completeHumanTask(taskId, {approved: true, comment: "approved"});
    _ = check workflow:getWorkflowResult(parentWorkflowId, 15);

    // The completed task should now appear under COMPLETED status
    management:HumanTaskSummary[] tasks = check management:listAllHumanTasks(status = "COMPLETED");
    boolean found = (from management:HumanTaskSummary t in tasks
                     where t.taskId == taskId select t).length() > 0;
    test:assertTrue(found, "Completed human task should appear in listAllHumanTasks(COMPLETED)");
}

// ================================================================================
// getHumanTaskInfo — after task completion (covers history signal-payload scanning)
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetHumanTaskInfoAfterCompletion() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-info-done"),
        orderId: "ORD-DONE-001",
        amount: 250.0,
        requester: "Dan"
    };
    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    string taskId = groups[0].taskIds[0];

    check workflow:completeHumanTask(taskId, {approved: true, comment: "done"});
    _ = check workflow:getWorkflowResult(parentWorkflowId, 15);

    // getHumanTaskInfo on a completed task exercises the history-scanning path
    // that reads the taskCompletion signal payload from workflow history.
    management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
    test:assertFalse(info.taskId == "", "Completed task must have a non-empty taskId");
    test:assertEquals(info.status, "COMPLETED", "Completed task should have COMPLETED status");
    test:assertNotEquals(info.result, (), "Completed task should have a result");
}

// ================================================================================
// getReviewActivityInfo — after retry decision (covers history signal-payload scanning)
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetReviewActivityInfoAfterDecision() returns error? {
    string testId = uniqueId("retry-info-done");
    RetryActivityInput input = {id: testId, mode: "manual_retry_input"};
    string workflowId = check workflow:run(manualRetryWithInputWorkflow, input);

    management:ReviewActivitySummary retryTask = check waitForPendingRetryTask(workflowId);
    string taskId = retryTask.taskId;

    // Resolve the retry task — workflow retries and completes successfully
    check management:completeReviewActivity(taskId, {action: "proceed-with-input", input: {mode: "ok"}});
    _ = check workflow:getWorkflowResult(workflowId, 30);

    // getReviewActivityInfo on a closed task covers the post-decision history-scanning path
    management:ReviewActivityInfo info = check management:getReviewActivityInfo(taskId);
    test:assertFalse(info.taskId == "", "Decided task must have a non-empty taskId");
    test:assertEquals(info.status, "COMPLETED", "Decided task should have COMPLETED status");
}

// ================================================================================
// listAllHumanTasks — time filters (covers addTimeClause in ManagementNative)
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListAllHumanTasksWithTimeFilter() returns error? {
    // startTimeFrom in the far past should include all tasks ever created
    management:HumanTaskSummary[]|error result =
            management:listAllHumanTasks(startTimeFrom = "2024-01-01T00:00:00Z");
    test:assertFalse(result is error,
            "listAllHumanTasks with startTimeFrom should not return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListAllHumanTasksStatusAndTimeFilter() returns error? {
    // Combine status + time filter — exercises the multi-clause query builder
    management:HumanTaskSummary[]|error result =
            management:listAllHumanTasks(status = "COMPLETED", startTimeFrom = "2024-01-01T00:00:00Z");
    test:assertFalse(result is error,
            "listAllHumanTasks with status + time filter should not return an error");
}

// ================================================================================
// listAllReviewActivities — time filters (covers addTimeClause in ManagementNative)
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksWithTimeFilter() returns error? {
    management:ReviewActivitySummary[]|error result =
            management:listAllReviewActivities(startTimeFrom = "2024-01-01T00:00:00Z");
    test:assertFalse(result is error,
            "listAllReviewActivities with startTimeFrom should not return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksStatusAndTimeFilter() returns error? {
    management:ReviewActivitySummary[]|error result =
            management:listAllReviewActivities(status = "PENDING", startTimeFrom = "2024-01-01T00:00:00Z");
    test:assertFalse(result is error,
            "listAllReviewActivities with status + time filter should not return an error");
}

// ================================================================================
// listWorkflowInstances — close-time filter and combined filters
// (covers additional addTimeClause and query-builder paths in ManagementNative)
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesWithCloseTimeFilter() returns error? {
    // Use a far-future closeTimeTo — should include all closed workflows
    management:WorkflowInstancePage|error result =
            management:listWorkflowInstances(status = "COMPLETED", closeTimeFrom = "2024-01-01T00:00:00Z");
    test:assertFalse(result is error,
            "listWorkflowInstances with closeTimeFrom should not return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListWorkflowInstancesCombinedFilters() returns error? {
    // Combine status + workflowType + startTimeFrom — exercises the full query builder
    management:WorkflowInstancePage page =
            check management:listWorkflowInstances(
                status = "COMPLETED",
                workflowType = "infoTestWorkflow",
                startTimeFrom = "2024-01-01T00:00:00Z"
            );
    // Result may be empty but the call must succeed
    test:assertTrue(page.items.length() >= 0,
            "Combined filter query should succeed");
}
