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

// Alternative Wait Example — Approval Ladder
//
// Demonstrates a workflow where multiple people can satisfy the same step.
// Either the Manager or the Director can approve a purchase request —
// whichever responds first unblocks the workflow, and any subsequent
// response to the same channel is silently ignored.
//
// The workflow uses a single shared "approval" data channel. Both approvers
// call the same endpoint — the first sendData("approval", ...) unblocks the
// wait, and any later call is ignored because the workflow has already moved
// past the wait point.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/purchases                  — submit a purchase request
//   POST /api/purchases/{id}/approval    — any approver sends decision (first wins)
//   GET  /api/purchases/{id}             — get the final result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type PurchaseInput record {|
    string requestId;
    string item;
    decimal amount;
    string requestedBy;
|};

type PurchaseResult record {|
    string requestId;
    string status;
    string message;
|};

# Approval decision sent by an approver.
#
# + approverId - ID of the approver
# + approved - true to approve, false to reject
# + reason - Optional reason for the decision
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Validates a purchase request.
#
# + requestId - Request identifier
# + item - Requested item
# + amount - Purchase amount
# + return - Error if validation fails
@workflow:Activity
function validatePurchase(string requestId, string item, decimal amount) returns string|error {
    io:println(string `[Activity] Validating purchase ${requestId}: ${item}, $${amount}`);
    if amount <= 0d {
        return error("Invalid amount: must be positive");
    }
    return "valid";
}

# Notifies both approvers that a purchase request needs review.
#
# + requestId - Request identifier
# + item - Requested item
# + amount - Purchase amount
# + return - Confirmation or error
@workflow:Activity
function notifyApprovers(string requestId, string item, decimal amount) returns string|error {
    io:println(string `[APPROVAL NEEDED] Purchase ${requestId}: ${item} ($${amount})`);
    io:println("  → Sent to: Manager and Director");
    return "Both approvers notified";
}

# Processes an approved purchase.
#
# + requestId - Request identifier
# + item - Item to purchase
# + amount - Approved amount
# + return - Purchase order number or error
@workflow:Activity
function processPurchase(string requestId, string item, decimal amount) returns string|error {
    io:println(string `[Activity] Processing purchase ${requestId}: ${item}, $${amount}`);
    return string `PO-${requestId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes a purchase request using an approval ladder.
#
# 1. Validates the request
# 2. Notifies both a Manager and a Director
# 3. Waits for either approver using a single shared data channel
# 4. If approved — processes the purchase
# 5. If rejected — returns REJECTED
#
# Both approvers target the same channel name ("approval"). The first
# sendData("approval", ...) unblocks the wait; any subsequent response
# to the same workflow instance is silently ignored.
#
# + ctx - Workflow context for calling activities
# + input - Purchase request details
# + events - Record containing the shared approval data future
# + return - Final purchase result or error
@workflow:Workflow
function purchaseApproval(
    workflow:Context ctx,
    PurchaseInput input,
    record {|
        future<ApprovalDecision> approval;
    |} events
) returns PurchaseResult|error {

    // Step 1: Validate the purchase request
    string _ = check ctx->callActivity(validatePurchase, {
        "requestId": input.requestId,
        "item": input.item,
        "amount": input.amount
    });

    // Step 2: Notify both approvers
    string _ = check ctx->callActivity(notifyApprovers, {
        "requestId": input.requestId,
        "item": input.item,
        "amount": input.amount
    });

    // Step 3: Wait once — the first sendData("approval", ...) unblocks the workflow
    io:println(string `[Workflow] Waiting for approval (Manager or Director) for: ${input.requestId}`);
    ApprovalDecision decision = check wait events.approval;
    io:println(string `[Workflow] Decision from ${decision.approverId}: approved=${decision.approved}`);

    if !decision.approved {
        return {
            requestId: input.requestId,
            status: "REJECTED",
            message: string `Rejected by ${decision.approverId}: ${decision.reason ?: "no reason given"}`
        };
    }

    // Step 4: Process the approved purchase
    string poNumber = check ctx->callActivity(processPurchase, {
        "requestId": input.requestId,
        "item": input.item,
        "amount": input.amount
    });

    return {
        requestId: input.requestId,
        status: "APPROVED",
        message: string `Approved by ${decision.approverId} — ${poNumber}`
    };
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the approval-ladder workflow over REST.
#
# Endpoints:
#   POST /api/purchases                      — submit a purchase request
#   POST /api/purchases/{id}/approval        — any approver sends decision (first wins)
#   GET  /api/purchases/{id}                 — get the final result
service /api on new http:Listener(8090) {

    # Submits a new purchase request.
    resource function post purchases(@http:Payload PurchaseInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(purchaseApproval, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Sends an approval decision to a waiting workflow.
    # Both the Manager and the Director call this endpoint — the first response wins.
    resource function post purchases/[string workflowId]/approval(
            @http:Payload ApprovalDecision decision) returns record {|string status; string message;|}|error {
        check workflow:sendData(purchaseApproval, workflowId, "approval", decision);
        io:println(string `Approval from ${decision.approverId} sent to workflow ${workflowId}`);
        return {status: "accepted", message: "Approval delivered to workflow"};
    }

    # Retrieves the final result of a purchase workflow.
    resource function get purchases/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
