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
// Demonstrates a workflow that pauses for a human decision using createHumanTask.
// Every order above a configured threshold requires manager approval — the
// workflow durably pauses until a reviewer completes the task. Low-value orders
// are auto-approved.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/orders                     — start a new order
//   GET  /api/orders/{id}/tasks          — list pending approval tasks
//   POST /api/tasks/{taskId}/complete    — submit the approval decision
//   GET  /api/orders/{id}               — get the final result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;
import ballerina/workflow.management;

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

# Approval decision submitted by a manager via the task inbox.
#
# + approved - true to approve the order, false to reject
# + reason - Optional reason for the decision
type ApprovalDecision record {|
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
# + return - Validation confirmation or error
@workflow:Activity
function validateOrder(string orderId, string item, decimal amount) returns string|error {
    io:println(string `[Activity] Validating order ${orderId}: ${item}, $${amount}`);
    if amount <= 0d {
        return error("Invalid amount: must be positive");
    }
    return "valid";
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
# 2. If amount > threshold — creates an approval task and durably pauses
# 3. A manager submits their decision via the task inbox (or HTTP API)
# 4. If approved (or auto-approved for low-value orders) — fulfills the order
# 5. If rejected — returns REJECTED
#
# The workflow is fully durable while paused — worker restarts do not lose state.
#
# + ctx - Workflow context for calling activities
# + input - Order details
# + return - Final order result or error
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns OrderResult|error {

    // Step 1: Validate the order
    string _ = check ctx->callActivity(validateOrder, {
        "orderId": input.orderId,
        "item": input.item,
        "amount": input.amount
    });

    // Step 2: Check if approval is needed
    if input.amount > APPROVAL_THRESHOLD {
        // createHumanTask creates a child workflow and durably pauses here
        // until a manager submits a decision via the task inbox or HTTP API.
        io:println(string `[Workflow] Creating approval task for order ${input.orderId}`);
        ApprovalDecision decision = check ctx->createHumanTask("approveOrder", "MANAGER",
                payload = {orderId: input.orderId, item: input.item, amount: input.amount.toString()},
                title = string `Approve ${input.item} for $${input.amount}`);
        io:println(string `[Workflow] Decision received: approved=${decision.approved}`);

        if !decision.approved {
            return {
                orderId: input.orderId,
                status:  "REJECTED",
                message: string `Rejected: ${decision.reason ?: "no reason given"}`
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
        status:  "COMPLETED",
        message: string `Order fulfilled: ${fulfillmentId}`
    };
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the human-in-the-loop workflow over REST.
#
# Endpoints:
#   POST /api/orders                   — creates a new order workflow
#   GET  /api/orders/{id}/tasks        — lists pending approval tasks for a workflow
#   POST /api/tasks/{taskId}/complete  — submits an approval decision for a task
#   GET  /api/orders/{id}             — retrieves the workflow result
service /api on new http:Listener(8090) {

    # Starts a new order processing workflow.
    # + input - Order details including orderId, item, and amount.
    # + return - Workflow handle containing the started workflow ID, or an error.
    resource function post orders(@http:Payload OrderInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processOrder, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Lists pending approval task groups for a workflow, sorted alphabetically by task name.
    # Returns an empty array if the workflow has no pending human tasks.
    # + workflowId - The workflow instance ID.
    # + return - Pending task groups for the workflow, or an error.
    resource function get orders/[string workflowId]/tasks() returns management:HumanTaskGroup[]|error {
        return management:listPendingHumanTasks(workflowId);
    }

    # Submits a manager's approval decision for a pending task.
    # workflowCompleteHumanTask sends a "taskCompletion" signal to the child workflow,
    # unblocking the parent and returning the typed decision to the workflow.
    # + taskId - The human task workflow ID.
    # + decision - The approval or rejection decision.
    # + return - Acceptance status, or an error.
    resource function post tasks/[string taskId]/complete(@http:Payload ApprovalDecision decision)
            returns record {|string status;|}|error {
        check workflow:completeHumanTask(taskId, decision);
        io:println(string `Task ${taskId} completed`);
        return {status: "accepted"};
    }

    # Retrieves the final result of a workflow. Blocks until the workflow completes.
    # + workflowId - The workflow instance ID.
    # + return - Workflow status and result as JSON, or an error.
    resource function get orders/[string workflowId]() returns json|error {
        anydata rawResult = check workflow:getWorkflowResult(workflowId);
        management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(workflowId);
        return {
            status: execInfo.status,
            result: check rawResult.cloneWithType(json)
        };
    }
}
