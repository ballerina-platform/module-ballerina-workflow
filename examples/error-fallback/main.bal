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

// Error Fallback Example
//
// Demonstrates the fallback pattern: when a primary activity exhausts its
// Temporal retries, the workflow catches the error and executes a secondary
// activity instead. The workflow completes successfully via the fallback path.
//
// Start the service:
//   bal run
//
// Then use the HTTP API:
//   POST /api/notifications       — send a notification
//   GET  /api/notifications/{id}  — get the workflow result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;
import ballerina/workflow.management;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type NotificationInput record {|
    string recipientId;
    string message;
    string email;
    string phone;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Primary delivery channel: send an email notification.
# Simulates a transient failure (e.g., SMTP server down).
#
# + email - The recipient email address
# + message - The notification message
# + return - Delivery confirmation or error
@workflow:Activity
function sendEmailNotification(string email, string message) returns string|error {
    io:println("Attempting email delivery to: " + email);
    // Simulate a failing email service
    return error("SMTP server unavailable: connection refused");
}

# Secondary delivery channel: send an SMS notification.
# Acts as the fallback when email delivery fails.
#
# + phone - The recipient phone number
# + message - The notification message
# + return - Delivery confirmation or error
@workflow:Activity
function sendSmsNotification(string phone, string message) returns string|error {
    io:println("Delivering SMS to: " + phone);
    return string `SMS delivered to ${phone}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Delivers a notification via email, falling back to SMS when email fails.
#
# The primary activity (`sendEmailNotification`) is retried twice with a 1-second
# initial delay. If it exhausts all retries, the error is captured as a value
# and the fallback SMS activity is called instead. The workflow always completes
# successfully as long as at least one channel is available.
#
# + ctx - Workflow context for calling activities
# + input - Notification details including both email and phone
# + return - Delivery channel and confirmation, or error if both channels fail
@workflow:Workflow
function sendNotification(workflow:Context ctx, NotificationInput input) returns string|error {
    // Try email with 2 Temporal retries (3 total attempts)
    string|error emailResult = ctx->callActivity(sendEmailNotification,
            {"email": input.email, "message": input.message},
            retryPolicy = {maxRetries: 2, retryDelay: 1.0, retryBackoff: 2.0});

    if emailResult is error {
        io:println("Email failed after retries: " + emailResult.message());
        io:println("Falling back to SMS...");

        // Fallback: SMS — propagate with `check` if this also fails
        string smsResult = check ctx->callActivity(sendSmsNotification,
                {"phone": input.phone, "message": input.message});
        return "Delivered via SMS: " + smsResult;
    }

    return "Delivered via email: " + emailResult;
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

service /api on new http:Listener(8093) {

    # Sends a notification (email with SMS fallback).
    # + input - Notification details including email, phone, and message.
    # + return - Workflow handle containing the started workflow ID, or an error.
    resource function post notifications(@http:Payload NotificationInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(sendNotification, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Retrieves the workflow result. Blocks until complete.
    # + workflowId - The workflow instance ID.
    # + return - Workflow status and result as JSON, or an error.
    resource function get notifications/[string workflowId]() returns json|error {
        anydata rawResult = check workflow:getWorkflowResult(workflowId);
        management:WorkflowExecutionInfo execInfo = check management:getWorkflowInfo(workflowId);
        return {
            status: execInfo.status,
            result: check rawResult.cloneWithType(json)
        };
    }
}
