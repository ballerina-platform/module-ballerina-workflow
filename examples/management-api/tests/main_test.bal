// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

// ── Management API response types ─────────────────────────────────────────────

type HumanTaskSummaryRes record {
    string taskId;
    string taskName;
    string parentWorkflowId;
    string? parentWorkflowType;
    string status;
    string[] userRoles;
};

type HumanTaskPage record {
    HumanTaskSummaryRes[] items;
    string? nextPageToken;
    boolean hasMore;
};

type RetryTaskSummaryRes record {
    string taskId;
    string taskName;
    string activityName;
    string parentWorkflowId;
    string status;
};

type RetryTaskPage record {
    RetryTaskSummaryRes[] items;
    string? nextPageToken;
    boolean hasMore;
};

// ── Poll helpers ───────────────────────────────────────────────────────────────

// Polls the Management API until at least one pending human task appears for
// the given workflow, or the timeout elapses.
function waitForPendingHumanTask(http:Client mgmt, string workflowId, decimal timeoutSecs = 15)
        returns HumanTaskSummaryRes|error {
    decimal elapsed = 0.0d;
    while elapsed < timeoutSecs {
        HumanTaskPage page = check mgmt->get(
                string `/human-tasks?status=PENDING&parentWorkflowId=${workflowId}`);
        if page.items.length() > 0 {
            return page.items[0];
        }
        runtime:sleep(0.3d);
        elapsed += 0.3d;
    }
    return error(string `Timed out waiting for pending human task (workflow: ${workflowId})`);
}

// Polls the Management API until at least one pending retry task appears for
// the given workflow, or the timeout elapses.
function waitForPendingRetryTask(http:Client mgmt, string workflowId, decimal timeoutSecs = 15)
        returns RetryTaskSummaryRes|error {
    decimal elapsed = 0.0d;
    while elapsed < timeoutSecs {
        RetryTaskPage page = check mgmt->get(
                string `/retry-tasks?status=PENDING&parentWorkflowId=${workflowId}`);
        if page.items.length() > 0 {
            return page.items[0];
        }
        runtime:sleep(0.3d);
        elapsed += 0.3d;
    }
    return error(string `Timed out waiting for pending retry task (workflow: ${workflowId})`);
}

// Polls the application API until the workflow reaches a terminal status, or the timeout elapses.
function waitForWorkflowCompleted(http:Client app, string wfId, decimal timeoutSecs = 20)
        returns WorkflowResponse|error {
    decimal elapsed = 0.0d;
    while elapsed < timeoutSecs {
        WorkflowResponse|error resp = app->get(string `/requests/${wfId}`);
        if resp is WorkflowResponse {
            string s = resp.status;
            if s == "COMPLETED" || s == "FAILED" || s == "CANCELED" || s == "CANCELLED" || s == "TERMINATED" {
                return resp;
            }
        }
        runtime:sleep(0.3d);
        elapsed += 0.3d;
    }
    return error(string `Timed out waiting for workflow ${wfId} to reach a terminal status`);
}

// Wraps the GET /api/requests/{id} response which now includes a `status` field
// alongside the `result` record.
type WorkflowResponse record {
    string status;
    ProcurementResult result;
};

// ── Test 1: Low-value request ─────────────────────────────────────────────────
// Amount is below $500 → auto-approved. Email address is valid → no retry task.
// The workflow completes fully without any human interaction.
@test:Config {}
function testLowValueAutoApproved() returns error? {
    http:Client app  = check new ("http://localhost:8080/api");
    http:Client mgmt = check new ("http://localhost:7234/workflow");

    record {|string workflowId;|} started = check app->post("/requests", {
        requestId:      "REQ-LOW-001",
        item:           "USB hub",
        amount:         49.99,
        requesterEmail: "alice@example.com",
        notifyEmail:    "procurement@example.com"
    });
    string wfId = started.workflowId;
    test:assertNotEquals(wfId, "", "Workflow ID must not be empty");

    // No human task should appear
    runtime:sleep(1.0d);
    HumanTaskPage tasks = check mgmt->get(
            string `/human-tasks?status=PENDING&parentWorkflowId=${wfId}`);
    test:assertEquals(tasks.items.length(), 0, "Low-value request must not create a human task");

    // No retry task should appear
    RetryTaskPage retries = check mgmt->get(
            string `/retry-tasks?status=PENDING&parentWorkflowId=${wfId}`);
    test:assertEquals(retries.items.length(), 0, "Valid email must not create a retry task");

    // Workflow should complete successfully
    WorkflowResponse response = check waitForWorkflowCompleted(app, wfId);
    test:assertEquals(response.result.requestId, "REQ-LOW-001");
    test:assertTrue(response.result.message.includes("USB hub"), "Result should mention the item");
}

// ── Test 2: High-value request — approved, then email retried via Management API
// Amount exceeds $500 → human approval required.
// notifyEmail contains "bad" → email activity fails → manual retry task.
// The test drives both tasks through the Management API to completion.
@test:Config {}
function testHighValueApprovedWithEmailRetry() returns error? {
    http:Client app  = check new ("http://localhost:8080/api");
    http:Client mgmt = check new ("http://localhost:7234/workflow");

    // ── Start the workflow ────────────────────────────────────────────────────
    record {|string workflowId;|} started = check app->post("/requests", {
        requestId:      "REQ-HIGH-001",
        item:           "developer laptop",
        amount:         1499.99,
        requesterEmail: "bob@example.com",
        notifyEmail:    "bad@example.com"   // triggers email failure → retry task
    });
    string wfId = started.workflowId;
    test:assertNotEquals(wfId, "", "Workflow ID must not be empty");

    // ── Step 1: Human approval task ───────────────────────────────────────────
    // The workflow pauses at callHumanTask waiting for a MANAGER decision.
    HumanTaskSummaryRes humanTask = check waitForPendingHumanTask(mgmt, wfId);
    test:assertTrue(humanTask.taskName.includes("approveRequest"),
            "Task name should include 'approveRequest'");
    test:assertTrue(humanTask.userRoles.indexOf("MANAGER") != (),
            "Task should require MANAGER role");

    // Complete the human task via the Management API — manager approves
    record {|boolean success; string completedBy; string completedAt;|} completeResp =
            check mgmt->post(string `/human-tasks/${humanTask.taskId}/complete`, {
                "result": {
                    "approved": true,
                    "reason":   "Approved for Q2 hardware refresh"
                }
            });
    test:assertTrue(completeResp.success, "Human task completion must succeed");

    // ── Step 2: Retry task for failed email ───────────────────────────────────
    // After approval the workflow calls sendProcurementEmail with "bad@example.com",
    // which fails. A ManualRetry task surfaces in the Management API.
    RetryTaskSummaryRes retryTask = check waitForPendingRetryTask(mgmt, wfId);
    test:assertTrue(retryTask.taskName.includes("retryProcurementEmail"),
            "Retry task name should include 'retryProcurementEmail'");
    test:assertEquals(retryTask.parentWorkflowId, wfId);

    // Retry with corrected email via Management API
    record {|boolean success; string decision; string decidedBy; string decidedAt;|} retryResp =
            check mgmt->post(string `/retry-tasks/${retryTask.taskId}/retry-with-input`, {
                "input": {
                    "requestId": "REQ-HIGH-001",
                    "toEmail":   "procurement@example.com",  // corrected address
                    "item":      "developer laptop",
                    "amount":    1499.99
                }
            });
    test:assertTrue(retryResp.success, "Retry decision must succeed");
    test:assertEquals(retryResp.decision, "retry-with-input");

    // ── Step 3: Workflow completes ────────────────────────────────────────────
    WorkflowResponse response = check waitForWorkflowCompleted(app, wfId);
    test:assertEquals(response.result.requestId, "REQ-HIGH-001");
    test:assertTrue(response.result.message.includes("developer laptop"),
            "Result should mention the approved item");
}

// ── Test 3: High-value request — rejected by manager ─────────────────────────
// Amount exceeds $500, but the manager rejects the request.
// No retry task should appear since the workflow ends at the rejection.
@test:Config {}
function testHighValueRejected() returns error? {
    http:Client app  = check new ("http://localhost:8080/api");
    http:Client mgmt = check new ("http://localhost:7234/workflow");

    record {|string workflowId;|} started = check app->post("/requests", {
        requestId:      "REQ-HIGH-002",
        item:           "conference room display",
        amount:         3200.00,
        requesterEmail: "carol@example.com",
        notifyEmail:    "procurement@example.com"
    });
    string wfId = started.workflowId;

    // Wait for and reject the approval task
    HumanTaskSummaryRes humanTask = check waitForPendingHumanTask(mgmt, wfId);
    record {|boolean success; string completedBy; string completedAt;|} completeResp =
            check mgmt->post(string `/human-tasks/${humanTask.taskId}/complete`, {
                "result": {
                    "approved": false,
                    "reason":   "Exceeds annual equipment budget"
                }
            });
    test:assertTrue(completeResp.success, "Human task rejection completion must succeed");

    // Workflow ends REJECTED — no retry task should appear
    runtime:sleep(0.5d);
    RetryTaskPage retries = check mgmt->get(
            string `/retry-tasks?status=PENDING&parentWorkflowId=${wfId}`);
    test:assertEquals(retries.items.length(), 0,
            "Rejected workflow must not produce a retry task");

    WorkflowResponse response = check waitForWorkflowCompleted(app, wfId);
    test:assertEquals(response.result.status, "REJECTED");
    test:assertEquals(response.result.requestId, "REQ-HIGH-002");
    test:assertTrue(response.result.message.includes("Exceeds annual equipment budget"),
            "Result should carry the rejection reason");
}

// ── Test 4: Management API — list workflow definitions ────────────────────────
// Verifies that the Management API is running and the workflow type is registered.
@test:Config {}
function testListWorkflowDefinitions() returns error? {
    http:Client mgmt = check new ("http://localhost:7234/workflow");

    record {|record{}[] definitions;|} resp = check mgmt->get("/definitions");
    test:assertTrue(resp.definitions.length() > 0, "At least one workflow must be registered");
}
