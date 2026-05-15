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

// Human-in-the-Loop Example
//
// Demonstrates a workflow that pauses for a human decision before proceeding.
// Every order above a configured threshold requires manager approval — the
// workflow durably pauses until a reviewer sends their decision via the
// HTTP API. Low-value orders are auto-approved.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/orders               — start a new order
//   POST /api/orders/{id}/approve  — send the approval decision
//   GET  /api/orders/{id}          — get the final result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type OrderInput record {|
    string orderId;
    string item;
    decimal amount;
|};

type OrderResult record {|
    string orderId;
    string status;
    string message;
|};

# Approval decision sent by a manager.
#
# + approverId - ID of the approver
# + approved - true to approve the order, false to reject
# + reason - Optional reason for the decision
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

// Orders above this threshold require human approval
const decimal APPROVAL_THRESHOLD = 500.00;

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Validates the order details.
#
# + orderId - Order identifier
# + item - Item name
# + amount - Order amount
# + return - Error if validation fails
@workflow:Activity
function validateOrder(string orderId, string item, decimal amount) returns string|error {
    io:println(string `[Activity] Validating order ${orderId}: ${item}, $${amount}`);
    if amount <= 0d {
        return error("Invalid amount: must be positive");
    }
    return "valid";
}

# Notifies the approval team that a new order needs review.
#
# + orderId - The order identifier
# + item - The item being ordered
# + amount - The order amount
# + return - Confirmation or error
@workflow:Activity
function notifyApprover(string orderId, string item, decimal amount) returns string|error {
    io:println(string `[APPROVAL NEEDED] Order ${orderId}: ${item} ($${amount}) requires manager approval`);
    return "Notified";
}

# Fulfills an approved order.
#
# + orderId - The order identifier
# + item - The item to fulfill
# + return - Fulfillment confirmation or error
@workflow:Activity
function fulfillOrder(string orderId, string item) returns string|error {
    io:println(string `[Activity] Fulfilling order ${orderId}: ${item}`);
    return string `FULFILLED-${orderId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes an order with human-in-the-loop approval for high-value orders.
#
# 1. Validates the order
# 2. If amount > threshold — notifies the approval team and pauses
# 3. Waits for a manager's approval decision
# 4. If approved (or auto-approved) — fulfills the order
# 5. If rejected — returns REJECTED
#
# The workflow is fully durable while paused — worker restarts do not lose state.
#
# + ctx - Workflow context for calling activities
# + input - Order details
# + events - Record containing the approval decision future
# + return - Final order result or error
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ApprovalDecision> approval; |} events
) returns OrderResult|error {

    // Step 1: Validate the order
    string _ = check ctx->callActivity(validateOrder, {
        "orderId": input.orderId,
        "item": input.item,
        "amount": input.amount
    });

    // Step 2: Check if approval is needed
    if input.amount > APPROVAL_THRESHOLD {
        // Notify the approval team
        string _ = check ctx->callActivity(notifyApprover, {
            "orderId": input.orderId,
            "item": input.item,
            "amount": input.amount
        });

        // Workflow durably pauses here until the "approval" data arrives
        io:println(string `[Workflow] Waiting for approval for order: ${input.orderId}`);
        ApprovalDecision decision = check wait events.approval;
        io:println(string `[Workflow] Decision from ${decision.approverId}: approved=${decision.approved}`);

        if !decision.approved {
            return {
                orderId: input.orderId,
                status: "REJECTED",
                message: string `Rejected by ${decision.approverId}: ${decision.reason ?: "no reason given"}`
            };
        }
    } else {
        io:println(string `[Workflow] Order ${input.orderId} auto-approved (amount $${input.amount} <= threshold)`);
    }

    // Step 3: Fulfill the order
    string fulfillmentId = check ctx->callActivity(fulfillOrder, {
        "orderId": input.orderId,
        "item": input.item
    });

    return {
        orderId: input.orderId,
        status: "COMPLETED",
        message: string `Order fulfilled: ${fulfillmentId}`
    };
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the human-in-the-loop workflow over REST.
#
# Endpoints:
#   POST /api/orders               — creates a new order workflow
#   POST /api/orders/{id}/approve  — sends the approval decision
#   GET  /api/orders/{id}          — retrieves the workflow result
service /api on new http:Listener(8090) {

    # Starts a new order processing workflow.
    resource function post orders(@http:Payload OrderInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processOrder, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Sends the manager's approval decision to a waiting workflow.
    # workflow:sendData is asynchronous — it signals the workflow engine and
    # returns once the engine has accepted the signal. The workflow processes
    # the signal and resumes independently. The response confirms engine
    # acceptance, not that the workflow has already acted on the decision.
    resource function post orders/[string workflowId]/approve(@http:Payload ApprovalDecision decision)
            returns record {|string status; string message;|}|error {
        check workflow:sendData(processOrder, workflowId, "approval", decision);
        io:println(string `Approval decision accepted by engine for workflow ${workflowId}`);
        return {status: "accepted", message: "Approval signal accepted by the workflow engine"};
    }

    # Retrieves the final result of a workflow. Blocks until the workflow completes.
    resource function get orders/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
