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

// Graceful Completion Example
//
// Demonstrates tolerating non-critical activity failures. When a side-effect
// activity (e.g., a notification or audit log) fails, the workflow catches the
// error, records that the step was skipped, and completes successfully. The
// core business outcome is preserved regardless of the non-critical failure.
//
// Start the service:
//   bal run
//
// Then use the HTTP API:
//   POST /api/orders       — start a new order
//   GET  /api/orders/{id}  — get the workflow result

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
    int quantity;
    string customerEmail;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Core business activity: reserves inventory for an order.
# This step is mandatory — if it fails, the workflow also fails.
#
# + orderId - The order identifier
# + item - The item to reserve
# + quantity - Number of units to reserve
# + return - Reservation confirmation or error
@workflow:Activity
function reserveInventory(string orderId, string item, int quantity) returns string|error {
    io:println(string `Reserving ${quantity} unit(s) of "${item}" for order ${orderId}`);
    return string `RES-${orderId}`;
}

# Non-critical side-effect: sends an order confirmation email.
# If this fails the order is still processed; we simply skip the notification.
#
# + email - The customer email address
# + orderId - The order identifier
# + return - Delivery confirmation or error
@workflow:Activity
function sendConfirmationEmail(string email, string orderId) returns string|error {
    io:println(string `Sending confirmation email to ${email} for order ${orderId}`);
    // Simulate an intermittent email service failure
    return error("Email service temporarily unavailable");
}

# Non-critical side-effect: writes an entry to the audit log.
# If this fails the order is still processed; we simply skip the log entry.
#
# + orderId - The order identifier
# + reservationId - The reservation ID from the inventory step
# + return - Log confirmation or error
@workflow:Activity
function writeAuditLog(string orderId, string reservationId) returns string|error {
    io:println(string `Writing audit log for order ${orderId}, reservation ${reservationId}`);
    return string `Audit logged: ${orderId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes an order and tolerates failures in non-critical side effects.
#
# The `reserveInventory` activity is mandatory — a failure here propagates with
# `check` and fails the workflow. The `sendConfirmationEmail` and `writeAuditLog`
# activities are non-critical: each is retried once, but their failure is caught
# and recorded in the result message rather than failing the workflow.
#
# + ctx - Workflow context for calling activities
# + input - Order details
# + return - Completion message (with any skipped steps noted) or error
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderInput input) returns string|error {
    // CRITICAL: inventory reservation must succeed — propagate with `check`
    string reservationId = check ctx->callActivity(reserveInventory, {
        "orderId": input.orderId,
        "item": input.item,
        "quantity": input.quantity
    });
    io:println("Core step completed: " + reservationId);

    string[] skipped = [];

    // NON-CRITICAL: confirmation email — retry once, tolerate failure
    string|error emailResult = ctx->callActivity(sendConfirmationEmail,
            {"email": input.customerEmail, "orderId": input.orderId},
            retryPolicy = {maxRetries: 1, retryDelay: 1.0});
    if emailResult is error {
        io:println("Email skipped: " + emailResult.message());
        skipped.push("email");
    }

    // NON-CRITICAL: audit log — retry once, tolerate failure
    string|error auditResult = ctx->callActivity(writeAuditLog,
            {"orderId": input.orderId, "reservationId": reservationId},
            retryPolicy = {maxRetries: 1, retryDelay: 1.0});
    if auditResult is error {
        io:println("Audit log skipped: " + auditResult.message());
        skipped.push("audit");
    }

    string suffix = skipped.length() > 0
        ? string ` (skipped: ${string:'join(", ", ...skipped)})`
        : "";

    return string `Order ${input.orderId} COMPLETED — reservation ${reservationId}${suffix}`;
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

service /api on new http:Listener(8094) {

    # Starts a new order processing workflow.
    # + input - Order details.
    # + return - Workflow handle containing the started workflow ID, or an error.
    resource function post orders(@http:Payload OrderInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processOrder, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Retrieves the workflow result. Blocks until complete.
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
