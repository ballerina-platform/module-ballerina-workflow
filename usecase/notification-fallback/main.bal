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

// =============================================================================
// Use case: Notification Fallback (Error handling — fallback)
// =============================================================================
//
// Trigger      : HTTP API from any internal system that needs to deliver a
//                customer notification — `POST /notifications`.
// Connectors   : ballerinax/googleapis.gmail (primary email channel),
//                ballerinax/twilio (SMS fallback),
//                ballerinax/slack (delivery audit feed).
// Pattern      : Error-handling — fallback channel.
//                  1) Try email (Gmail) with Temporal-style retries.
//                  2) On exhaustion, capture the error as a value and call
//                     the SMS fallback (Twilio).
//                  3) Post the chosen channel to a Slack audit channel so
//                     operations can see how each notification was actually
//                     delivered.
//                The workflow always completes successfully as long as at
//                least one channel is reachable.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/slack;
import ballerinax/twilio;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable int servicePort = 8121;

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "alerts@example.com";

configurable string twilioAccountSid = "";
configurable string twilioAuthToken = "";
configurable string twilioFromNumber = "+15555550100";

configurable string slackBotToken = "";
configurable string deliveryAuditChannel = "#delivery-audit";

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final gmail:Client gmailClient = check new ({
    auth: {
        refreshToken: gmailRefreshToken,
        clientId: gmailClientId,
        clientSecret: gmailClientSecret
    }
});

final twilio:Client twilioClient = check new ({
    auth: {accountSid: twilioAccountSid, authToken: twilioAuthToken}
});

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Notification payload from the upstream caller.
#
# + notificationId - Idempotency key
# + recipientEmail - Recipient email address (primary channel)
# + recipientPhone - Recipient phone number in E.164 format (fallback channel)
# + subject - Notification subject
# + body - Notification body (plain text)
public type NotificationRequest record {|
    string notificationId;
    string recipientEmail;
    string recipientPhone;
    string subject;
    string body;
|};

# Result of a notification delivery workflow.
#
# + notificationId - Idempotency key from the request
# + channel - Channel actually used to deliver the notification
# + providerMessageId - Provider message id (Gmail message id or Twilio SID)
# + emailErrorMessage - Captured email error message when fallback was used
public type NotificationResult record {|
    string notificationId;
    string channel;
    string providerMessageId;
    string emailErrorMessage = "";
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Sends an email through Gmail. Returns the Gmail message id on success.
# Returns an error if Gmail is unreachable or rejects the message.
#
# + to - Recipient email
# + subject - Email subject
# + body - Email body
# + return - Gmail message id, or an error
@workflow:Activity
isolated function sendEmail(string to, string subject, string body) returns string|error {
    gmail:Message sent = check gmailClient->/users/me/messages/send.post({
        to: [to],
        'from: gmailFromAddress,
        subject,
        bodyInText: body
    });
    log:printInfo("[gmail] notification email sent", to = to, gmailMessageId = sent.id);
    return sent.id;
}

# Sends an SMS through Twilio. Used as the fallback channel when email
# delivery exhausts its retries.
#
# + to - Recipient phone number (E.164)
# + body - SMS body
# + return - Twilio message SID, or an error
@workflow:Activity
isolated function sendSms(string to, string body) returns string|error {
    twilio:Message msg = check twilioClient->createMessage({
        To: to,
        From: twilioFromNumber,
        Body: body
    });
    string? maybeSid = msg?.sid;
    if maybeSid is () || maybeSid == "" {
        return error("[twilio] SID missing from createMessage response");
    }
    string sid = maybeSid;
    log:printInfo("[twilio] notification SMS sent", to = to, sid = sid);
    return sid;
}

# Posts a delivery-audit message to Slack so operations can see which
# channel was actually used for each notification.
#
# + text - Slack message text
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function postDeliveryAudit(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: deliveryAuditChannel,
        text
    });
    log:printInfo("[slack] delivery audit posted",
            channel = deliveryAuditChannel, ts = resp.ts);
    return resp.ts;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Delivers a notification via email; falls back to SMS if email fails after
# its retry budget is exhausted.
#
# + ctx - Workflow context
# + req - Inbound notification request
# + return - Notification result, or an error if both channels fail
@workflow:Workflow
function deliverNotification(workflow:Context ctx, NotificationRequest req)
        returns NotificationResult|error {

    // Primary channel: email with retries. Capture as `T|error` so that an
    // exhausted retry budget becomes a value we can inspect, not a thrown
    // error that aborts the workflow.
    string|error emailResult = ctx->callActivity(sendEmail,
            {"to": req.recipientEmail, "subject": req.subject, "body": req.body},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    if emailResult is string {
        string|error auditResult = ctx->callActivity(postDeliveryAudit,
                {"text": string `:email: Notification ${req.notificationId} delivered ` +
                        string `via email (${emailResult}).`},
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        if auditResult is error {
            log:printWarn("audit post failed; delivery still successful",
                    notificationId = req.notificationId, channel = "EMAIL",
                    reason = auditResult.message());
        }
        return {
            notificationId: req.notificationId,
            channel: "EMAIL",
            providerMessageId: emailResult
        };
    }

    string emailErrorMsg = emailResult.message();
    log:printWarn("email channel failed; falling back to SMS",
            notificationId = req.notificationId, reason = emailErrorMsg);

    // Fallback channel: SMS. Propagate with `check` so an SMS failure
    // surfaces as a workflow failure rather than silent loss.
    string smsSid = check ctx->callActivity(sendSms,
            {"to": req.recipientPhone, "body": string `${req.subject}: ${req.body}`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string|error smsAuditResult = ctx->callActivity(postDeliveryAudit,
            {"text": string `:warning: Notification ${req.notificationId} delivered ` +
                    string `via SMS fallback (Twilio SID ${smsSid}). ` +
                    string `Email failure: ${emailErrorMsg}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if smsAuditResult is error {
        log:printWarn("audit post failed; delivery still successful",
                notificationId = req.notificationId, channel = "SMS",
                reason = smsAuditResult.message());
    }

    return {
        notificationId: req.notificationId,
        channel: "SMS",
        providerMessageId: smsSid,
        emailErrorMessage: emailErrorMsg
    };
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

// In-memory idempotency store: notificationId → workflowId.
// Prevents duplicate workflows when the caller retries the same notificationId.
// Use a durable store in production.
isolated map<string> notificationWorkflowIds = {};

# REST API for the notification-fallback workflow.
service /notifications on new http:Listener(servicePort) {

    # Starts a notification-delivery workflow.
    #
    # + req - Notification request
    # + return - Workflow id wrapper, or an error
    resource function post .(@http:Payload NotificationRequest req)
            returns record {|string workflowId;|}|error {
        lock {
            string? existing = notificationWorkflowIds[req.notificationId];
            if existing is string {
                log:printInfo("notification workflow already started (idempotent hit)",
                        workflowId = existing, notificationId = req.notificationId);
                return {workflowId: existing};
            }
            // Reserve the slot atomically to prevent concurrent duplicate starts.
            notificationWorkflowIds[req.notificationId] = "in-progress";
        }
        string|error workflowIdOrError = workflow:run(deliverNotification, req);
        if workflowIdOrError is error {
            lock {
                _ = notificationWorkflowIds.remove(req.notificationId);
            }
            return workflowIdOrError;
        }
        string workflowId = workflowIdOrError;
        lock {
            notificationWorkflowIds[req.notificationId] = workflowId;
        }
        log:printInfo("notification workflow started",
                notificationId = req.notificationId, workflowId = workflowId);
        return {workflowId};
    }

    # Returns the workflow execution result. Blocks until the workflow ends.
    #
    # + workflowId - Workflow id
    # + return - Workflow execution info, or an error
    resource function get [string workflowId]()
            returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
