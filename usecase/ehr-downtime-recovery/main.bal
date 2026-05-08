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
// Use case: EHR Downtime Recovery (Healthcare interoperability)
// =============================================================================
//
// Trigger      : HTTP API from an integration gateway (`POST /interop/dispatch`)
// Connectors   : ballerina/http (EMR REST/FHIR endpoint),
//                ballerinax/slack (ops alerts),
//                ballerinax/googleapis.gmail (delivery report).
//
// Key insight  : In traditional message-routing and ETL pipelines, a failed
//                delivery writes undelivered messages to a "Queued" or
//                "Errored" state that an operator must drain manually after
//                the downstream system recovers.
//
//                In a workflow-based architecture there is NO separate physical
//                queue.  The workflow itself IS the durable message carrier.
//                A running workflow that is retrying a failed activity IS the
//                "queued message" — it is persisted, observable, and retried
//                automatically without any operator action.
//
// Flow
//   1. Receive a clinical message dispatch request over HTTP.
//   2. Attempt first delivery to the downstream EHR/EMR (no automatic retry).
//   3. If delivery succeeds on the first try → optional Slack confirmation and done.
//   4. If the EHR returns 5xx or is unreachable:
//        a. Optionally notify the Slack ops channel: "EHR offline — workflow is retrying."
//        b. Retry delivery with exponential backoff (up to maxRetries).
//           The workflow runtime persists the in-flight state across restarts,
//           acting as the message's durable "queue slot".
//        c. When the EHR recovers and a retry succeeds → optional Slack recovery
//           notice and optional Gmail delivery report for audit.
//        d. If all retries are exhausted → workflow fails; notifications are
//           still optional.
//
// Retry parameters (tune for your SLA):
//   maxRetries = 20, retryDelay = 30 s, retryBackoff = 1.5
//   → attempts at ~30 s, 45 s, 67 s, 101 s, … up to ~10 hours total window.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable int servicePort = 8124;

configurable string slackBotToken = "";
configurable string interopOpsChannel = "#interop-ops";
configurable boolean enableDispatchNotifications = true;

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "interop@example.com";
configurable string opsEmail = "ops@example.com";

configurable string emrBaseUrl = "http://localhost:9080";

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final gmail:Client gmailClient = check new ({
    auth: {
        refreshToken: gmailRefreshToken,
        clientId: gmailClientId,
        clientSecret: gmailClientSecret
    }
});

final http:Client emrClient = check new (emrBaseUrl);

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Clinical message dispatch request from an upstream integration gateway.
#
# + messageId - Unique message / idempotency key
# + patientId - Target patient identifier
# + messageType - HL7 message type (e.g. `ADT_A01`, `ORU_R01`)
# + sourceSystem - Name of the source system that produced the message
# + payload - Transformed payload (canonical JSON or HL7 string)
# + downstreamPath - Resource path on the EHR endpoint
public type ClinicalMessageDispatch record {|
    string messageId;
    string patientId;
    string messageType;
    string sourceSystem;
    string payload;
    string downstreamPath = "/api/fhir/messages";
|};

# Outcome of a dispatch workflow execution.
#
# + messageId - Unique message identifier from the request
# + status - `DELIVERED` (first try), `RECOVERED` (after retries), or `FAILED`
# + httpStatusCode - Final HTTP status code returned by the EHR endpoint
# + summary - Human-readable description of the outcome
public type DispatchResult record {|
    string messageId;
    string status;
    int httpStatusCode;
    string summary;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Delivers a clinical message to the downstream EHR/EMR over HTTP.
#
# + msg - Clinical message dispatch request
# + return - HTTP status code on success, or an error when the EHR is
#   unreachable or returns a non-2xx response
@workflow:Activity
isolated function deliverToEhr(ClinicalMessageDispatch msg) returns int|error {
    http:Request request = new;
    request.setJsonPayload({
        messageId: msg.messageId,
        patientId: msg.patientId,
        messageType: msg.messageType,
        sourceSystem: msg.sourceSystem,
        payload: msg.payload
    });
    http:Response response = check emrClient->post(msg.downstreamPath, request);
    int statusCode = response.statusCode;
    if statusCode < 200 || statusCode >= 300 {
        return error(string `EHR returned HTTP ${statusCode} for message ${msg.messageId}`);
    }
    log:printInfo("[http] message delivered to EHR",
            messageId = msg.messageId, statusCode = statusCode, path = msg.downstreamPath);
    return statusCode;
}

# Posts an alert to the Slack ops channel indicating the EHR is offline and
# the workflow has entered automatic retry mode.
#
# + messageId - Message being retried
# + sourceSystem - Originating system
# + messageType - HL7 message type
# + failureReason - Error detail from the first failed delivery attempt
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function notifySlackEhrDown(string messageId, string sourceSystem,
        string messageType, string failureReason) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: interopOpsChannel,
        text: string `:warning: *EHR Offline — Workflow Retrying* (no message queue needed)\n` +
              string `Message *${messageId}* (${messageType}) from *${sourceSystem}* ` +
              string `could not be delivered.\n` +
              string `Reason: ${failureReason}\n` +
              string `The workflow will retry automatically with exponential backoff. ` +
              string `No manual replay required — the workflow *is* the queue.`
    });
    log:printInfo("[slack] EHR downtime alert sent", messageId = messageId);
    return resp.ts;
}

# Posts a recovery notice to the Slack ops channel once the EHR comes back
# online and the deferred delivery succeeds.
#
# + messageId - Message that was successfully delivered after retries
# + sourceSystem - Originating system
# + httpStatusCode - HTTP status code from the successful delivery
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function notifySlackEhrRecovered(string messageId, string sourceSystem,
        int httpStatusCode) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: interopOpsChannel,
        text: string `:white_check_mark: *EHR Recovered — Message Delivered*\n` +
              string `Message *${messageId}* from *${sourceSystem}* ` +
              string `was successfully delivered (HTTP ${httpStatusCode}) after the EHR came back online.\n` +
              string `Workflow closed — no operator action required.`
    });
    log:printInfo("[slack] EHR recovery notice sent", messageId = messageId);
    return resp.ts;
}

# Sends a delivery audit report via Gmail to the ops team.
#
# + result - Final dispatch result record
# + return - Gmail message id, or an error
@workflow:Activity
isolated function sendDeliveryReport(DispatchResult result) returns string|error {
    string subject = string `[Interop] EHR Delivery ${result.status}: ${result.messageId}`;
    string body = string `EHR Delivery Report\n` +
            string `-------------------\n` +
            string `Message ID  : ${result.messageId}\n` +
            string `Status      : ${result.status}\n` +
            string `HTTP Code   : ${result.httpStatusCode}\n` +
            string `Summary     : ${result.summary}\n`;
    gmail:Message sent = check gmailClient->/users/me/messages/send.post({
        to: [opsEmail],
        'from: gmailFromAddress,
        subject: subject,
        bodyInText: body
    });
    log:printInfo("[gmail] delivery report sent",
            messageId = result.messageId, gmailMessageId = sent.id);
    return sent.id;
}

// -----------------------------------------------------------------------------
// Helpers — wrap callActivity so T is inferred from the function return type
// -----------------------------------------------------------------------------

# Probes the EHR with a single delivery attempt (no retries).
# Returns the HTTP status code on success, or the error as a value so the
# caller can branch rather than letting the workflow fail immediately.
#
# + ctx - Workflow context
# + msg - Clinical message to deliver
# + return - HTTP status code, or an error if the EHR is unreachable / non-2xx
function probeEhrDelivery(workflow:Context ctx, ClinicalMessageDispatch msg) returns int|error {
    return ctx->callActivity(deliverToEhr, {"msg": msg}, retryOnError = false);
}

# Re-attempts EHR delivery with exponential backoff.
# The workflow runtime persists in-progress state across restarts — a
# retrying workflow IS the durable "queue slot" for the message.
#
# + ctx - Workflow context
# + msg - Clinical message to deliver
# + return - HTTP status code on eventual success, or an error when retries
#   are exhausted
function retryEhrDelivery(workflow:Context ctx, ClinicalMessageDispatch msg) returns int|error {
    return ctx->callActivity(deliverToEhr, {"msg": msg},
            retryOnError = true, maxRetries = 20, retryDelay = 30.0, retryBackoff = 1.5);
}

# Sends a downtime alert only when notifications are enabled.
#
# + ctx - Workflow context
# + msg - Clinical message metadata
# + failureReason - First-attempt failure reason
# + return - Error if notification fails while enabled
function notifyEhrDownIfEnabled(workflow:Context ctx, ClinicalMessageDispatch msg, string failureReason)
        returns error? {
    if !enableDispatchNotifications {
        return;
    }
    string _ = check ctx->callActivity(notifySlackEhrDown,
            {"messageId": msg.messageId, "sourceSystem": msg.sourceSystem,
             "messageType": msg.messageType, "failureReason": failureReason},
            retryOnError = true, maxRetries = 3, retryDelay = 5.0, retryBackoff = 2.0);
}

# Sends a recovery alert only when notifications are enabled.
#
# + ctx - Workflow context
# + msg - Clinical message metadata
# + statusCode - Successful EHR status code
# + return - Error if notification fails while enabled
function notifyEhrRecoveredIfEnabled(workflow:Context ctx, ClinicalMessageDispatch msg, int statusCode)
        returns error? {
    if !enableDispatchNotifications {
        return;
    }
    string _ = check ctx->callActivity(notifySlackEhrRecovered,
            {"messageId": msg.messageId, "sourceSystem": msg.sourceSystem,
             "httpStatusCode": statusCode},
            retryOnError = true, maxRetries = 3, retryDelay = 5.0, retryBackoff = 2.0);
}

# Sends a delivery report only when notifications are enabled.
#
# + ctx - Workflow context
# + result - Final dispatch result
# + return - Error if report delivery fails while enabled
function sendDeliveryReportIfEnabled(workflow:Context ctx, DispatchResult result) returns error? {
    if !enableDispatchNotifications {
        return;
    }
    string _ = check ctx->callActivity(sendDeliveryReport, {"result": result},
            retryOnError = true, maxRetries = 3, retryDelay = 5.0, retryBackoff = 2.0);
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Dispatches a clinical message to the downstream EHR/EMR and automatically
# recovers from transient downtime by retrying with exponential backoff.
#
# The workflow acts as the durable message queue — no external queue or manual
# operator replay is required when the target system returns to service.
#
# + ctx - Workflow context
# + msg - Clinical message dispatch request
# + return - Dispatch result on success, or an error if all retries are exhausted
@workflow:Workflow
function dispatchClinicalMessage(workflow:Context ctx, ClinicalMessageDispatch msg)
        returns DispatchResult|error {

    // ── Step 1: First delivery attempt (no automatic retry) ──────────────────
    // Capture the result as a value rather than failing the workflow immediately.
    // This lets us distinguish "never reached EHR" from "retried and failed".
    int|error firstAttempt = probeEhrDelivery(ctx, msg);

    if firstAttempt is int {
        // Happy path — EHR accepted the message on first try.
        check notifyEhrRecoveredIfEnabled(ctx, msg, firstAttempt);
        return {
            messageId: msg.messageId,
            status: "DELIVERED",
            httpStatusCode: firstAttempt,
            summary: string `Message ${msg.messageId} delivered to EHR on first attempt (HTTP ${firstAttempt}).`
        };
    }

    // ── Step 2: EHR is offline — alert ops and enter retry mode ──────────────
    // The workflow suspends here and will be retried automatically by the
    // workflow runtime. This IS the "queued" state — no physical queue needed.
    string failureReason = firstAttempt.message();
    log:printWarn("[workflow] EHR delivery failed, entering retry mode",
            messageId = msg.messageId, reason = failureReason);

        check notifyEhrDownIfEnabled(ctx, msg, failureReason);

    // ── Step 3: Retry with exponential backoff ────────────────────────────────
    // Retry window: 30 s → 45 s → 67 s → 101 s → … (factor 1.5, 20 attempts
    // ≈ up to ~10 hours).  Tune maxRetries / retryDelay for your SLA.
    // The workflow runtime persists this in-progress state across restarts —
    // a "retrying workflow" is equivalent to a "queued message" in a
    // traditional pipeline — except no operator drain is required.
    int statusCode = check retryEhrDelivery(ctx, msg);

    // ── Step 4: EHR recovered — notify and report ────────────────────────────
    DispatchResult result = {
        messageId: msg.messageId,
        status: "RECOVERED",
        httpStatusCode: statusCode,
        summary: string `Message ${msg.messageId} delivered after EHR recovered (HTTP ${statusCode}).`
    };

        check notifyEhrRecoveredIfEnabled(ctx, msg, statusCode);
        check sendDeliveryReportIfEnabled(ctx, result);

    return result;
}

// -----------------------------------------------------------------------------
// HTTP service
// -----------------------------------------------------------------------------

service /interop on new http:Listener(servicePort) {

    # Dispatches a clinical message to the downstream EHR.
    # The workflow handles transient EHR downtime by retrying automatically —
    # no operator replay required.
    #
    # + msg - Clinical message dispatch request
    # + return - Accepted with workflow id, or InternalServerError on startup failure
    resource function post dispatch(@http:Payload ClinicalMessageDispatch msg)
            returns http:Accepted|http:InternalServerError {
        string|error workflowId = workflow:run(dispatchClinicalMessage, msg);
        if workflowId is error {
            log:printError("[http] failed to start dispatch workflow",
                    messageId = msg.messageId, err = workflowId.message());
            return <http:InternalServerError>{body: {message: workflowId.message()}};
        }
        log:printInfo("[http] dispatch workflow started",
                messageId = msg.messageId, workflowId = workflowId);
        return <http:Accepted>{body: {workflowId, messageId: msg.messageId}};
    }

    # Returns the workflow execution result for a dispatch workflow.
    # Blocks until the workflow completes.
    #
    # + workflowId - Workflow id returned by the dispatch endpoint
    # + return - Workflow execution info, or an error
    resource function get dispatch/[string workflowId]()
            returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
