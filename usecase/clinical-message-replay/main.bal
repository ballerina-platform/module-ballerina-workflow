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
// Use case: Clinical Message Replay (Healthcare interoperability)
// =============================================================================
//
// Trigger      : HTTP API from an integration gateway, replay console, or
//                operator dashboard (`POST /interop/messages`).
// Connectors   : ballerina/http, ballerinax/jira, ballerinax/slack.
// Human step   : Replay-after-failure. The workflow:
//                  1) Attempts delivery to the downstream EMR over HTTP.
//                  2) If validation or delivery fails, creates a Jira replay
//                     task as the system of record.
//                  3) Optionally notifies the interoperability Slack channel
//                     when `enableInteropNotifications=true`.
//                  4) Pauses until an analyst resolves the task and calls
//                     back with replay instructions.
//                  5) Retries delivery with corrected values or an explicit
//                     filter override, keeping the entire replay trail inside
//                     workflow history.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/jira;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable int servicePort = 8123;

configurable string slackBotToken = "";
configurable string interopOpsChannel = "#interop-ops";
configurable boolean enableInteropNotifications = true;

configurable string jiraBaseUrl = "https://your-org.atlassian.net/rest";
configurable string jiraEmail = "";
configurable string jiraApiToken = "";
configurable string jiraProjectKey = "INTEROP";
configurable string jiraReplayIssueType = "Task";
configurable string jiraWebhookSecret = "";

configurable string emrBaseUrl = "http://localhost:9080";

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final jira:Client jiraClient = check new (
        {auth: {username: jiraEmail, password: jiraApiToken}},
        jiraBaseUrl);

final http:Client emrClient = check new (emrBaseUrl);

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Inbound clinical message request from the integration layer.
#
# + messageId - Integration message id or idempotency key
# + sourceSystem - Upstream system that produced the message
# + messageType - Message type such as `ADT_A01` or `ORU_R01`
# + patientId - Target patient id after transformation
# + encounterId - Optional encounter / visit id
# + routingOutcome - `ALLOW` for normal delivery or `FILTERED` when an
#   over-strict rule initially blocked the message
# + downstreamPath - Resource path on the EMR endpoint
# + hl7Payload - Transformed HL7 payload or canonical JSON representation
public type ClinicalMessage record {|
    string messageId;
    string sourceSystem;
    string messageType;
    string patientId;
    string encounterId = "";
    string routingOutcome = "ALLOW";
    string downstreamPath = "/messages";
    string hl7Payload;
|};

# Replay instructions returned by the analyst after investigating the task.
#
# + jiraIssueKey - Jira issue used as the auditable system of record
# + analystName - Human operator resolving the replay task
# + action - `RETRY_AS_IS`, `RETRY_WITH_PATCH`, or `CANCEL`
# + correctedPatientId - Corrected patient id when a mapping fix is needed
# + correctedEncounterId - Corrected encounter id when needed
# + overrideFilter - Whether a previously filtered message should be replayed
# + notes - Analyst notes to preserve in workflow history and Slack
public type ReplayInstruction record {|
    string jiraIssueKey;
    string analystName;
    string action;
    string correctedPatientId = "";
    string correctedEncounterId = "";
    boolean overrideFilter = false;
    string notes = "";
|};

# Final replay outcome.
#
# + messageId - Integration message id
# + status - `DELIVERED`, `REPLAYED`, `CANCELLED`, or `REPLAY_FAILED`
# + jiraIssueKey - Jira replay task key when a human task was created
# + downstreamStatusCode - HTTP status code from the downstream EMR when available
# + message - Human-readable summary of the outcome
public type ReplayResult record {|
    string messageId;
    string status;
    string jiraIssueKey = "";
    int downstreamStatusCode = 0;
    string message;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Validates whether the replay candidate can be sent downstream.
#
# + req - Clinical message request
# + return - `VALID` when the message can be delivered, or an error
@workflow:Activity
isolated function validateReplayCandidate(ClinicalMessage req) returns string|error {
    if req.patientId.trim() == "" {
        return error("Mapped patientId is empty; operator must correct the mapping before replay.");
    }
    if req.routingOutcome.toUpperAscii() == "FILTERED" {
        return error("Message was filtered by routing rules and requires explicit override before replay.");
    }
    return "VALID";
}

# Delivers the transformed message to the downstream EMR over HTTP.
#
# + req - Clinical message request
# + return - Downstream HTTP status code on success, or an error
@workflow:Activity
isolated function deliverToEmr(ClinicalMessage req) returns int|error {
    http:Request request = new;
    request.setJsonPayload({
        messageId: req.messageId,
        sourceSystem: req.sourceSystem,
        messageType: req.messageType,
        patientId: req.patientId,
        encounterId: req.encounterId,
        hl7Payload: req.hl7Payload
    });
    http:Response response = check emrClient->post(req.downstreamPath, request);
    int statusCode = response.statusCode;
    if statusCode < 200 || statusCode >= 300 {
        return error(string `Downstream EMR returned HTTP ${statusCode} for message ${req.messageId}.`);
    }
    log:printInfo("[http] clinical message delivered",
            messageId = req.messageId, statusCode = statusCode, path = req.downstreamPath);
    return statusCode;
}

# Creates a Jira issue for replay investigation and operator input.
#
# + workflowId - Workflow id waiting for the replay instruction
# + req - Clinical message request
# + failureReason - Captured validation or delivery error
# + return - Jira issue key, or an error
@workflow:Activity
isolated function createReplayTask(string workflowId, ClinicalMessage req, string failureReason)
        returns string|error {
    string summary = string `[Replay] ${req.messageType} ${req.messageId} from ${req.sourceSystem}`;
    string description = string `Workflow ${workflowId} is waiting for replay instructions. ` +
            string `Failure reason: ${failureReason}\n` +
            string `Message type: ${req.messageType}\n` +
            string `Patient id: ${req.patientId}\n` +
            string `Routing outcome: ${req.routingOutcome}\n` +
            string `Callback endpoint: POST /interop/messages/${workflowId}/replay-resolution`;
    jira:CreatedIssue created = check jiraClient->/api/'3/issue.post({
        fields: {
            "project": {"key": jiraProjectKey},
            "summary": summary,
            "description": description,
            "issuetype": {"name": jiraReplayIssueType}
        }
    });
    string issueKey = created["key"] ?: "";
    if issueKey == "" {
        log:printError("[jira] replay task created but key is missing or empty",
                workflowId = workflowId, messageId = req.messageId);
        return error("Jira issue created but response did not include a key.");
    }
    log:printInfo("[jira] replay task created",
            workflowId = workflowId, issueKey = issueKey, messageId = req.messageId);
    return issueKey;
}

# Posts an audit update to the interoperability Slack channel.
#
# + text - Slack message text
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function postInteropAudit(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: interopOpsChannel,
        text
    });
    log:printInfo("[slack] interop audit posted", channel = interopOpsChannel, ts = resp.ts);
    return resp.ts;
}

// -----------------------------------------------------------------------------
// Workflow helpers
// -----------------------------------------------------------------------------

# Validates and then delivers a message to the downstream EMR.
#
# + ctx - Workflow context
# + req - Clinical message request
# + return - Downstream HTTP status code, or an error
function attemptDelivery(workflow:Context ctx, ClinicalMessage req) returns int|error {
    string _ = check ctx->callActivity(validateReplayCandidate,
            {"req": req});
    return ctx->callActivity(deliverToEmr,
            {"req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
}

# Posts an interop audit message only when notifications are enabled.
#
# + ctx - Workflow context
# + text - Notification text
# + return - Error if posting fails while notifications are enabled
function postInteropAuditIfEnabled(workflow:Context ctx, string text) returns error? {
    if !enableInteropNotifications {
        return;
    }
    string _ = check ctx->callActivity(postInteropAudit,
            {"text": text},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
}

# Applies analyst instructions to the original message before replay.
#
# + req - Original clinical message request
# + instruction - Replay instruction from the human task
# + return - Updated message used for the replay attempt
function applyReplayInstruction(ClinicalMessage req, ReplayInstruction instruction) returns ClinicalMessage {
    return {
        messageId: req.messageId,
        sourceSystem: req.sourceSystem,
        messageType: req.messageType,
        patientId: instruction.correctedPatientId != "" ? instruction.correctedPatientId : req.patientId,
        encounterId: instruction.correctedEncounterId != "" ? instruction.correctedEncounterId : req.encounterId,
        routingOutcome: instruction.overrideFilter ? "ALLOW" : req.routingOutcome,
        downstreamPath: req.downstreamPath,
        hl7Payload: req.hl7Payload
    };
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Delivers a clinical message, creates an auditable replay task on failure,
# then waits for a human replay instruction before retrying.
#
# + ctx - Workflow context
# + req - Clinical message request
# + events - Typed callback carrying the replay instruction
# + return - Final replay result
@workflow:Workflow
function replayClinicalMessage(
        workflow:Context ctx,
        ClinicalMessage req,
        record {| future<ReplayInstruction> replayInstruction; |} events
) returns ReplayResult|error {
    int|error initialAttempt = attemptDelivery(ctx, req);
    if initialAttempt is int {
        check postInteropAuditIfEnabled(ctx,
                string `:white_check_mark: Message *${req.messageId}* ` +
                string `delivered to the EMR on the first attempt (${initialAttempt}).`);
        return {
            messageId: req.messageId,
            status: "DELIVERED",
            downstreamStatusCode: initialAttempt,
            message: "Message delivered without needing replay."
        };
    }

    string failureReason = initialAttempt.message();
    string workflowId = check ctx.getWorkflowId();
    string issueKey = check ctx->callActivity(createReplayTask,
            {"workflowId": workflowId, "req": req, "failureReason": failureReason},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string alertText = string `:warning: Replay required for *${req.messageId}* ` +
            string `(${req.messageType}) from *${req.sourceSystem}*. Jira: ${issueKey}. ` +
            string `Reason: ${failureReason}`;
    check postInteropAuditIfEnabled(ctx, alertText);

    ReplayInstruction instruction = check wait events.replayInstruction;
    if issueKey != instruction.jiraIssueKey {
        log:printWarn("[clinical-replay] Jira issue mismatch",
                expected = issueKey, received = instruction.jiraIssueKey, messageId = req.messageId);
    }

    if instruction.action.toUpperAscii() == "CANCEL" {
        check postInteropAuditIfEnabled(ctx,
                string `:no_entry: Replay cancelled for *${req.messageId}* ` +
                string `by ${instruction.analystName}. Jira: ${issueKey}. Notes: ${instruction.notes}`);
        return {
            messageId: req.messageId,
            status: "CANCELLED",
            jiraIssueKey: issueKey,
            message: string `Replay cancelled by ${instruction.analystName}.`
        };
    }

    string upperAction = instruction.action.toUpperAscii();
    if upperAction != "RETRY_AS_IS" && upperAction != "RETRY_WITH_PATCH" {
        return error(string `Unknown replay action '${instruction.action}'; expected RETRY_AS_IS, RETRY_WITH_PATCH, or CANCEL.`);
    }

    ClinicalMessage replayRequest = applyReplayInstruction(req, instruction);
    int|error replayAttempt = attemptDelivery(ctx, replayRequest);
    if replayAttempt is error {
        string replayFailure = replayAttempt.message();
        check postInteropAuditIfEnabled(ctx,
                string `:x: Replay failed again for *${req.messageId}*. ` +
                string `Jira: ${issueKey}. Analyst: ${instruction.analystName}. ` +
                string `Reason: ${replayFailure}`);
        return {
            messageId: req.messageId,
            status: "REPLAY_FAILED",
            jiraIssueKey: issueKey,
            message: replayFailure
        };
    }

    check postInteropAuditIfEnabled(ctx,
            string `:repeat: Message *${req.messageId}* replayed successfully ` +
            string `by ${instruction.analystName}. Jira: ${issueKey}. ` +
            string `Downstream status: ${replayAttempt}.`);
    return {
        messageId: req.messageId,
        status: "REPLAYED",
        jiraIssueKey: issueKey,
        downstreamStatusCode: replayAttempt,
        message: string `Replay succeeded after operator intervention from ${instruction.analystName}.`
    };
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

// In-memory idempotency store: messageId -> workflowId.
// This prevents duplicate replay workflows when the caller retries the same
// failed message id. Use a durable store in production.
isolated map<string> messageWorkflowIds = {};

# REST API for the clinical-message-replay workflow.
service /interop on new http:Listener(servicePort) {

    # Starts a replay-capable workflow for one failed clinical message.
    #
    # + req - Clinical message payload
    # + return - Workflow id and message id, or an error
    resource function post messages(@http:Payload ClinicalMessage req)
            returns record {| string workflowId; string messageId; |}|http:BadRequest|error {
        if req.messageId.trim() == "" || req.messageType.trim() == "" || req.hl7Payload.trim() == "" {
            return <http:BadRequest>{body: "messageId, messageType, and hl7Payload are required"};
        }
        lock {
            string? existing = messageWorkflowIds[req.messageId];
            if existing is string {
                log:printInfo("clinical replay workflow already started (idempotent hit)",
                        workflowId = existing, messageId = req.messageId);
                return {workflowId: existing, messageId: req.messageId};
            }
            // Reserve the slot atomically to prevent concurrent duplicate starts.
            messageWorkflowIds[req.messageId] = "in-progress";
        }

        string|error workflowIdOrError = workflow:run(replayClinicalMessage, req);
        if workflowIdOrError is error {
            lock {
                _ = messageWorkflowIds.remove(req.messageId);
            }
            return workflowIdOrError;
        }
        string workflowId = workflowIdOrError;
        lock {
            messageWorkflowIds[req.messageId] = workflowId;
        }
        log:printInfo("clinical replay workflow started",
                workflowId = workflowId, messageId = req.messageId);
        return {workflowId, messageId: req.messageId};
    }

    # Jira webhook callback with the replay resolution.
    #
    # + workflowId - Target workflow id
    # + request - Incoming HTTP request for optional secret verification
    # + instruction - Replay instruction from the operator or Jira automation
    # + return - Accepted envelope, or unauthorized when the secret mismatches
    resource function post messages/[string workflowId]/'replay\-resolution(
            http:Request request, @http:Payload ReplayInstruction instruction)
            returns record {| string status; |}|http:Unauthorized|error {
        if jiraWebhookSecret == "" {
            return <http:Unauthorized>{};
        }
        string|http:HeaderNotFoundError secret = request.getHeader("X-Webhook-Secret");
        if secret is http:HeaderNotFoundError || secret != jiraWebhookSecret {
            return <http:Unauthorized>{};
        }
        check workflow:sendData(replayClinicalMessage, workflowId, "replayInstruction", instruction);
        return {status: "accepted"};
    }

    # Returns the workflow's final result.
    #
    # + workflowId - Target workflow id
    # + return - Workflow execution info, or an error
    resource function get messages/[string workflowId]()
            returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}