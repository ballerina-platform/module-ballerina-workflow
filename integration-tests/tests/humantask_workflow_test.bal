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
// HUMAN TASK (callHumanTask / completeHumanTask) — TESTS
// ================================================================================
//
// Covers the scenarios from docs/humantask-temporal-native.md:
//
//   1. Approved path: task approved → reimbursement processed
//   2. Rejected path: task rejected → REJECTED result returned
//   3. Timeout path: nobody acts within deadline → escalation called, TIMED_OUT result
//   4. Minimal config: taskName-only (defaults for title, roles, timeout)
//   5. Multiple user roles: userRoles array with two entries
//
// Each test uses management:listPendingHumanTasks(parentWorkflowId) to discover the
// child workflow ID, then workflow:completeHumanTask(taskWorkflowId, result) to
// submit the human decision.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// Polls until at least one pending task appears (with at least one task ID) or timeout elapses.
function waitForPendingHumanTask(string parentWorkflowId, decimal timeoutSecs = 10)
        returns management:HumanTaskGroup[]|error {
    decimal elapsed = 0.0;
    while elapsed < timeoutSecs {
        management:HumanTaskGroup[] groups = check management:listPendingHumanTasks(parentWorkflowId);
        if groups.length() > 0 && groups[0].taskIds.length() > 0 {
            return groups;
        }
        runtime:sleep(0.3);
        elapsed += 0.3;
    }
    return error("Timed out waiting for pending human task for workflow: " + parentWorkflowId);
}

// ================================================================================
// TEST 1 — Happy path: task approved, reimbursement processed
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testHumanTaskApprovedPath() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-approved"),
        orderId: "ORD-HT-001",
        amount: 500.0,
        requester: "Alice"
    };

    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);

    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    test:assertEquals(groups.length(), 1, "Exactly one pending human task should exist");
    test:assertTrue(groups[0].taskIds.length() > 0, "Task group should have at least one task ID");

    check workflow:completeHumanTask(groups[0].taskIds[0], {approved: true, comment: "LGTM"});

    anydata result = check workflow:getWorkflowResult(parentWorkflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HT-001");
        test:assertEquals(result["status"], "COMPLETED");
        test:assertTrue((<string>result["message"]).includes("ORD-HT-001"),
                "Message should reference the order ID");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 2 — Rejected path: task rejected, workflow returns REJECTED
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testHumanTaskRejectedPath() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-rejected"),
        orderId: "ORD-HT-002",
        amount: 9999.0,
        requester: "Bob"
    };

    string parentWorkflowId = check workflow:run(expenseApprovalWorkflow, input);

    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    test:assertEquals(groups.length(), 1, "Exactly one pending human task should exist");
    test:assertTrue(groups[0].taskIds.length() > 0, "Task group should have at least one task ID");

    check workflow:completeHumanTask(groups[0].taskIds[0], {approved: false, comment: "Amount too high"});

    anydata result = check workflow:getWorkflowResult(parentWorkflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HT-002");
        test:assertEquals(result["status"], "REJECTED");
        test:assertTrue((<string>result["message"]).includes("Amount too high"),
                "Rejection comment should appear in the message");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 3 — Timeout path: no human acts, escalation fires, TIMED_OUT result
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testHumanTaskTimeoutPath() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-timeout"),
        orderId: "ORD-HT-003",
        amount: 200.0,
        requester: "Carol"
    };

    string parentWorkflowId = check workflow:run(expenseApprovalWithTimeoutWorkflow, input);

    // The task timeout is 5 seconds; wait beyond it
    runtime:sleep(10);

    anydata result = check workflow:getWorkflowResult(parentWorkflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HT-003");
        test:assertEquals(result["status"], "TIMED_OUT");
        test:assertTrue((<string>result["message"]).includes("approveExpenseWithTimeout"),
                "Message should reference the timed-out task name");
    } else {
        test:assertFail("Expected map<anydata> result");
    }

    // Verify the escalation activity was recorded in the workflow history
    management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(parentWorkflowId);
    management:ActivityInvocation[] escalations = from management:ActivityInvocation inv
            in execInfo.activityInvocations
        where inv.activityName.includes("htNotifyEscalation")
        select inv;
    test:assertTrue(escalations.length() > 0, "htNotifyEscalation should have been called on timeout");
}

// ================================================================================
// TEST 4 — Minimal config: only taskName provided
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testHumanTaskMinimalConfig() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-minimal"),
        orderId: "ORD-HT-004",
        amount: 75.0,
        requester: "Dave"
    };

    string parentWorkflowId = check workflow:run(expenseApprovalMinimalWorkflow, input);

    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    test:assertEquals(groups.length(), 1, "One pending task should exist for minimal config");
    test:assertTrue(groups[0].taskIds.length() > 0, "Task group should have at least one task ID");

    // Approve — the default roles include "admin" so any caller can complete
    check workflow:completeHumanTask(groups[0].taskIds[0], {approved: true, comment: "OK"});

    anydata result = check workflow:getWorkflowResult(parentWorkflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["status"], "COMPLETED");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 5 — Multiple user roles
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testHumanTaskMultipleUserRoles() returns error? {
    ExpenseRequest input = {
        id: uniqueId("ht-multirole"),
        orderId: "ORD-HT-005",
        amount: 1500.0,
        requester: "Eve"
    };

    string parentWorkflowId = check workflow:run(expenseApprovalMultiRoleWorkflow, input);

    management:HumanTaskGroup[] groups = check waitForPendingHumanTask(parentWorkflowId);
    test:assertEquals(groups.length(), 1, "One pending task should exist for multi-role workflow");
    test:assertTrue(groups[0].taskIds.length() > 0, "Task group should have at least one task ID");

    // Complete as a MANAGER
    check workflow:completeHumanTask(groups[0].taskIds[0], {approved: true, comment: "Approved by manager"});

    anydata result = check workflow:getWorkflowResult(parentWorkflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HT-005");
        test:assertEquals(result["status"], "COMPLETED");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 6 — listPendingHumanTasks returns empty array before task is created
// ================================================================================

@test:Config {
    groups: ["integration", "humantask"]
}
function testListPendingHumanTasksEmptyForUnknownWorkflow() returns error? {
    // Use a fake workflow ID — should return empty list without error
    string fakeId = "nonexistent-workflow-" + uniqueId("fake");
    management:HumanTaskGroup[]|error result = management:listPendingHumanTasks(fakeId);
    // Getting history for a nonexistent workflow may return an error or an empty list;
    // either is acceptable — the function must not panic
    if result is management:HumanTaskGroup[] {
        test:assertEquals(result.length(), 0,
                "No task groups expected for a workflow that was never started");
    }
    // If the server returns an error (not-found), that is also acceptable behaviour
}
