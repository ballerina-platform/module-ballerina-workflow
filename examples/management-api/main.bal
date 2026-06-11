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

// Management API Example
//
// Demonstrates the Ballerina Workflow Management HTTP Service alongside a
// realistic workflow that uses:
//   - createHumanTask — pauses execution for a human approval decision
//   - ManualRetry    — pauses execution when an activity fails so an operator
//                      can retry (optionally with corrected input)
//
// Scenario — IT Equipment Procurement:
//   1. Staff submits a purchase request
//   2. High-value requests (> $500) need MANAGER approval → human task
//   3. After approval the workflow sends a procurement email → ManualRetry
//      activity (simulates a flaky email service)
//
// Two HTTP services start together:
//   Application API   — http://localhost:8080/api/   (start + query requests)
//   Management API    — http://localhost:7234/workflow/ (tasks, retry, dashboards)
//
// Driving the workflow end-to-end:
//
//   # 1. Start a high-value request (triggers approval + email retry)
//   curl -s -X POST http://localhost:8080/api/requests \
//        -H 'Content-Type: application/json' \
//        -d '{"requestId":"REQ-001","item":"laptop","amount":1500,"requesterEmail":
//             "alice@co.com","notifyEmail":"bad@example.com"}'
//
//   # 2. List pending approvals
//   curl -s 'http://localhost:7234/workflow/human-tasks?status=PENDING'
//
//   # 3. Approve the request (replace TASK_ID with the taskId from step 2)
//   curl -s -X POST http://localhost:7234/workflow/human-tasks/TASK_ID/complete \
//        -H 'Content-Type: application/json' \
//        -d '{"result":{"approved":true,"reason":"Approved for Q2 budget"}}'
//
//   # 4. List pending retry tasks (email failed)
//   curl -s 'http://localhost:7234/workflow/retry-tasks?status=PENDING'
//
//   # 5. Retry with corrected email (replace RETRY_ID)
//   curl -s -X POST http://localhost:7234/workflow/retry-tasks/RETRY_ID/retry-with-input \
//        -H 'Content-Type: application/json' \
//        -d '{"input":{"requestId":"REQ-001","toEmail":"procurement@co.com",
//             "item":"laptop","amount":1500}}'
//
//   # 6. Check the workflow result
//   curl -s http://localhost:8080/api/requests/WORKFLOW_ID

import ballerina/http;
import ballerina/io;
import ballerina/workflow;
import ballerina/workflow.management;

// ── Types ──────────────────────────────────────────────────────────────────────

# Input for a procurement request workflow.
#
# + requestId - Unique identifier for the request
# + item - Equipment item being requested
# + amount - Total purchase amount in USD
# + requesterEmail - Email address of the staff member making the request
# + notifyEmail - Procurement team email for the purchase order notification
type ProcurementRequest record {|
    string requestId;
    string item;
    decimal amount;
    string requesterEmail;
    string notifyEmail;
|};

# Final result returned by the procurement workflow.
#
# + requestId - The request identifier
# + status - COMPLETED | REJECTED
# + message - Human-readable outcome description
type ProcurementResult record {|
    string requestId;
    string status;
    string message;
|};

# Decision submitted by a manager to approve or reject a procurement request.
#
# + approved - `true` to approve, `false` to reject
# + reason - Optional justification for the decision
type ApprovalDecision record {|
    boolean approved;
    string? reason;
|};

// Requests above this threshold require explicit manager approval.
const decimal APPROVAL_THRESHOLD = 500.00d;

// ── Activities ─────────────────────────────────────────────────────────────────

# Validates the procurement request fields.
#
# + requestId - Unique request identifier
# + item - Item being requested
# + amount - Requested amount
# + return - `"valid"` on success, or an error if validation fails
@workflow:Activity
function validateRequest(string requestId, string item, decimal amount) returns string|error {
    io:println(string `[Activity] Validating request ${requestId}: ${item} ($${amount})`);
    if amount <= 0d {
        return error("Invalid amount: must be positive");
    }
    return "valid";
}

# Sends a procurement order notification to the purchasing team.
#
# Simulates a flaky email service: requests addressed to an address that
# contains the word `"bad"` are rejected, allowing the test to trigger the
# `ManualRetry` path and then recover with corrected input.
#
# + requestId - The procurement request identifier
# + toEmail - Recipient address for the purchase-order notification
# + item - Equipment item description
# + amount - Purchase amount
# + return - Email reference ID on success, or an error on delivery failure
@workflow:Activity
function sendProcurementEmail(string requestId, string toEmail, string item, decimal amount)
        returns string|error {
    io:println(string `[Activity] Sending procurement email to ${toEmail}: ${item} ($${amount})`);
    if toEmail.includes("bad") {
        return error(string `Email delivery failed: '${toEmail}' rejected by mail server`);
    }
    io:println(string `[Activity] Email sent to ${toEmail}`);
    return string `EMAIL-${requestId}`;
}

// ── Workflow ───────────────────────────────────────────────────────────────────

# Processes an IT equipment procurement request end-to-end.
#
# Steps:
# 1. Validates the request fields.
# 2. High-value requests (> $500) create an "approveRequest" human task
# and durably pause until a manager submits a decision via the
# Management API (`POST /workflow/human-tasks/{taskId}/complete`).
# 3. Sends a procurement email using `ManualRetry` so that delivery failures
# surface as retry tasks in the Management API
# (`GET /workflow/retry-tasks`) instead of crashing the workflow.
# An operator can retry with the original or corrected arguments.
#
# + ctx - Workflow execution context
# + input - Procurement request details
# + return - Final procurement result or an error
@workflow:Workflow
function processProcurementRequest(workflow:Context ctx, ProcurementRequest input)
        returns ProcurementResult|error {

    // Step 1 — Validate
    string _ = check ctx->callActivity(validateRequest, {
        "requestId": input.requestId,
        "item": input.item,
        "amount": input.amount
    });

    // Step 2 — Manager approval for high-value requests
    if input.amount > APPROVAL_THRESHOLD {
        io:println(string `[Workflow] Requesting approval for ${input.requestId} ($${input.amount})`);

        ApprovalDecision decision = check ctx->createHumanTask("approveRequest", "MANAGER",
                payload = {
                    requestId: input.requestId,
                    item: input.item,
                    amount: input.amount.toString(),
                    requester: input.requesterEmail
                },
                title = string `Approve purchase of '${input.item}' ($${input.amount}) ${input.requestId}`);

        io:println(string `[Workflow] Approval decision: approved=${decision.approved}`);

        if !decision.approved {
            return {
                requestId: input.requestId,
                status: "REJECTED",
                message: string `Rejected by manager: ${decision.reason ?: "no reason provided"}`
            };
        }
    } else {
        io:println(string `[Workflow] Auto-approved: amount $${input.amount} is below threshold`);
    }

    // Step 3 — Send procurement notification (ManualRetry for delivery failures)
    string _ = check ctx->callActivity(sendProcurementEmail, {
        "requestId": input.requestId,
        "toEmail": input.notifyEmail,
        "item": input.item,
        "amount": input.amount
    }, retryPolicy = <workflow:ManualRetry>{
        taskName: "retryProcurementEmail",
        userRoles: ["OPS"]
    });

    io:println(string `[Workflow] Procurement completed for ${input.requestId}`);
    return {
        requestId: input.requestId,
        status: "COMPLETED",
        message: string `Procurement request fulfilled: ${input.item} ($${input.amount})`
    };
}

// ── Application HTTP Service (port 8080) ──────────────────────────────────────
// Exposes only workflow start and result retrieval.
// All human-task and retry-task management goes through the Management API
// at http://localhost:7234/workflow/

service /api on new http:Listener(8080) {

    # Starts a new procurement request workflow and returns its ID.
    #
    # + input - Procurement request details
    # + return - `{workflowId}` on success, or an error
    resource function post requests(@http:Payload ProcurementRequest input)
            returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processProcurementRequest, input);
        io:println(string `[API] Started workflow: ${workflowId}`);
        return {workflowId};
    }

    # Returns the current status and final result of a procurement workflow.
    # Blocks until the workflow completes or times out (60 s).
    #
    # + workflowId - ID returned by `POST /api/requests`
    # + return - `{status, result}` as JSON, or an error
    resource function get requests/[string workflowId]() returns json|error {
        anydata rawResult = check workflow:getWorkflowResult(workflowId, 60);
        management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(workflowId);
        return {
            status: execInfo.status,
            result: check rawResult.cloneWithType(json)
        };
    }
}
