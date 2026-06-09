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
//   • getRetryTaskInfo / listAllRetryTasks
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
    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    string taskId = groups[0].taskIds[0];

    management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
    test:assertFalse(info.taskId == "", "HumanTaskInfo must have a non-empty taskId");
    test:assertFalse(info.taskName == "", "HumanTaskInfo must have a non-empty taskName");
    test:assertEquals(info.status, "RUNNING", "Active human task should be RUNNING");
    test:assertTrue(info.userRoles.length() > 0, "HumanTaskInfo should include at least one user role");

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
// RETRY TASK — getRetryTaskInfo / listAllRetryTasks
// ================================================================================

@test:Config {
    groups: ["integration"]
}
function testGetRetryTaskInfo() returns error? {
    string testId = uniqueId("retry-info");
    RetryActivityInput input = {id: testId, mode: "manual_retry_fail"};
    string workflowId = check workflow:run(manualRetryFailDecisionWorkflow, input);

    management:RetryTaskSummary retryTask = check waitForPendingRetryTask(workflowId);

    management:RetryTaskInfo info = check management:getRetryTaskInfo(retryTask.taskId);
    test:assertFalse(info.taskId == "", "RetryTaskInfo must have a non-empty taskId");
    test:assertFalse(info.activityName == "", "RetryTaskInfo must have a non-empty activityName");
    test:assertFalse(info.errorMessage == "", "RetryTaskInfo should capture the activity error message");
    test:assertEquals(info.status, "RUNNING", "Active retry task should be RUNNING");

    // Clean up — decide fail so the workflow terminates (workflow itself will also error out)
    check management:completeRetryTask(retryTask.taskId, {action: "fail"});
    do {
        _ = check workflow:getWorkflowResult(workflowId, 15);
    } on fail {
        // expected — the workflow fails when the retry task action is "fail"
    }
}

@test:Config {
    groups: ["integration"]
}
function testGetRetryTaskInfoNonExistentReturnsError() returns error? {
    management:RetryTaskInfo|error result = management:getRetryTaskInfo("nonexistent-retrytask-xyz");
    test:assertTrue(result is error, "getRetryTaskInfo for a nonexistent task should return an error");
}

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksPending() returns error? {
    string testId = uniqueId("retry-list");
    RetryActivityInput input = {id: testId, mode: "manual_retry_input"};
    string workflowId = check workflow:run(manualRetryWithInputWorkflow, input);

    management:RetryTaskSummary expected = check waitForPendingRetryTask(workflowId);

    management:RetryTaskSummary[] tasks = check management:listAllRetryTasks(status = "PENDING");
    test:assertTrue(tasks.length() > 0, "listAllRetryTasks(PENDING) should find at least one task");

    boolean found = (from management:RetryTaskSummary t in tasks where t.taskId == expected.taskId select t).length() > 0;
    test:assertTrue(found, "The newly created retry task should appear in the PENDING list");

    // Clean up
    check management:completeRetryTask(expected.taskId, {action: "retry-with-input", input: {mode: "ok"}});
    _ = check workflow:getWorkflowResult(workflowId, 30);
}

@test:Config {
    groups: ["integration"]
}
function testListAllRetryTasksNoFilter() returns error? {
    management:RetryTaskSummary[]|error result = management:listAllRetryTasks();
    test:assertFalse(result is error, "listAllRetryTasks() with no filters should not return an error");
}
