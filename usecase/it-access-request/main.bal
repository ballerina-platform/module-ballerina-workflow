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
// Use case: IT Access Request (IT automation)
// =============================================================================
//
// Trigger      : HTTP webhook from an internal service portal
//                (`POST /it/access-requests`).
// Connectors   : ballerinax/slack, ballerinax/jira, ballerinax/twilio
// Human step   : Approval of the privileged-access grant. The workflow:
//                  1) Notifies the security approvers' Slack channel.
//                  2) Creates a Jira `Service Request` task on the IT/SEC
//                     project assigned to the on-call approver.
//                  3) Pauses on a typed signal channel until the Jira webhook
//                     calls back with the approval decision.
// Final step   : Sends an SMS confirmation to the requester via Twilio.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/jira;
import ballerinax/slack;
import ballerinax/twilio;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable string slackBotToken = "";
configurable string itApproversChannel = "#it-approvals";

configurable string jiraBaseUrl = "https://your-org.atlassian.net/rest";
configurable string jiraEmail = "";
configurable string jiraApiToken = "";
configurable string jiraProjectKey = "ITSEC";
configurable string jiraAccessIssueType = "Service Request";

configurable string twilioAccountSid = "";
configurable string twilioAuthToken = "";
configurable string twilioFromNumber = "+15555550100";

# Shared secret that Jira must send in the `X-Webhook-Secret` header.
# Leave empty to disable secret verification (development only).
configurable string jiraWebhookSecret = "";

configurable int servicePort = 8102;

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final jira:Client jiraClient = check new (
        {auth: {username: jiraEmail, password: jiraApiToken}},
        jiraBaseUrl);

final twilio:Client twilioClient = check new ({
    auth: {accountSid: twilioAccountSid, authToken: twilioAuthToken}
});

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Inbound access-request payload from the service portal webhook.
#
# + requestId - Portal request id (idempotency key)
# + requesterEmail - Requester's email
# + requesterPhone - Requester's mobile number in E.164 format
# + requesterName - Requester's display name
# + targetSystem - System for which access is being requested (e.g. `prod-aws`)
# + accessLevel - Requested access level (e.g. `read-only`, `admin`)
# + justification - Free-text business justification
# + approverJiraAccountId - Jira accountId of the on-call security approver
public type AccessRequest record {|
    string requestId;
    string requesterEmail;
    string requesterPhone;
    string requesterName;
    string targetSystem;
    string accessLevel;
    string justification;
    string approverJiraAccountId;
|};

# Approval decision received from Jira via the webhook callback.
#
# + jiraIssueKey - Jira issue key
# + resolution - `Done`, `Approved`, `Won't Do`, `Rejected`, …
# + approverDisplayName - Display name of the approver
# + comment - Optional approval comment
public type AccessDecision record {|
    string jiraIssueKey;
    string resolution;
    string approverDisplayName;
    string comment = "";
|};

# Final access-request result.
#
# + requestId - Portal request id
# + status - `GRANTED` or `DENIED`
# + jiraIssueKey - Approval Jira issue
# + smsSid - Twilio SID of the confirmation SMS
public type AccessResult record {|
    string requestId;
    string status;
    string jiraIssueKey;
    string smsSid;
|};

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

# Masks a phone number to avoid logging PII. Returns all but last 4 digits masked.
isolated function maskPhone(string phone) returns string {
    if phone.length() <= 4 {
        return "****";
    }
    return string `${"*".repeat(phone.length() - 4)}${phone.substring(phone.length() - 4)}`;
}

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Notifies the IT/SEC approvers' Slack channel.
#
# + req - Inbound access request
# + return - Slack message timestamp on success, or an error
@workflow:Activity
isolated function notifyApprovers(AccessRequest req) returns string|error {
    string text = string `:lock: *Access request* — ${req.requesterName} ` +
            string `requests *${req.accessLevel}* on *${req.targetSystem}*. ` +
            string `Reason: ${req.justification}.`;
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: itApproversChannel,
        text
    });
    log:printInfo("[slack] approver notification posted",
            channel = itApproversChannel, ts = resp.ts);
    return resp.ts;
}

# Creates the Jira approval task assigned to the on-call approver. The
# workflow id is embedded in the description so a Jira automation can call
# back to the right workflow instance.
#
# + workflowId - Workflow id
# + req - Inbound access request
# + return - Jira issue key, or an error
@workflow:Activity
isolated function createAccessApprovalTask(string workflowId, AccessRequest req)
        returns string|error {
    string summary = string `[Access] ${req.requesterName} → ${req.accessLevel} on ${req.targetSystem}`;
    string description = string `Workflow ${workflowId} is awaiting approval for ` +
            string `request ${req.requestId}. Justification: ${req.justification}.`;
    jira:CreatedIssue created = check jiraClient->/api/'3/issue.post({
        fields: {
            "project": {"key": jiraProjectKey},
            "summary": summary,
            "description": description,
            "issuetype": {"name": jiraAccessIssueType},
            "assignee": {"accountId": req.approverJiraAccountId}
        }
    });
    string issueKey = created["key"] ?: "";
    log:printInfo("[jira] access-approval task created",
            issueKey = issueKey, workflowId = workflowId);
    return issueKey;
}

# Sends an SMS to the requester with the final outcome.
#
# + toNumber - Requester phone number (E.164)
# + body - SMS body
# + return - Twilio message SID, or an error
@workflow:Activity
isolated function sendSmsToRequester(string toNumber, string body) returns string|error {
    twilio:Message msg = check twilioClient->createMessage({
        To: toNumber,
        From: twilioFromNumber,
        Body: body
    });
    string sid = msg?.sid ?: "";
    log:printInfo("[twilio] SMS sent", to = maskPhone(toNumber), sid = sid);
    return sid;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Coordinates the access-request approval flow end-to-end.
#
# + ctx - Workflow context
# + req - Inbound access request
# + events - Approval signal channel populated by the Jira webhook
# + return - Final result
@workflow:Workflow
function handleAccessRequest(
        workflow:Context ctx,
        AccessRequest req,
        record {| future<AccessDecision> approval; |} events
) returns AccessResult|error {

    string _ = check ctx->callActivity(notifyApprovers,
            {"req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string wfId = check ctx.getWorkflowId();
    string issueKey = check ctx->callActivity(createAccessApprovalTask,
            {"workflowId": wfId, "req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    AccessDecision decision = check wait events.approval;
    string lower = decision.resolution.toLowerAscii();
    boolean granted = (issueKey == decision.jiraIssueKey) && (lower == "done" || lower == "approved");
    if issueKey != decision.jiraIssueKey {
        log:printWarn("[it-access] issueKey mismatch",
                expected = issueKey, received = decision.jiraIssueKey);
    }

    string smsBody = granted
            ? string `Hi ${req.requesterName}, your ${req.accessLevel} access to ` +
                    string `${req.targetSystem} has been approved (Jira: ${decision.jiraIssueKey}).`
            : string `Hi ${req.requesterName}, your ${req.accessLevel} access request to ` +
                    string `${req.targetSystem} was denied (Jira: ${decision.jiraIssueKey}).`;
    string smsSid = check ctx->callActivity(sendSmsToRequester,
            {"toNumber": req.requesterPhone, "body": smsBody},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    return {
        requestId: req.requestId,
        status: granted ? "GRANTED" : "DENIED",
        jiraIssueKey: issueKey,
        smsSid
    };
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

// In-memory idempotency store: requestId → workflowId.
// Prevents duplicate workflows when the portal retries the same requestId.
// Note: this store is not persisted across restarts; use a DB for production.
isolated map<string> requestWorkflowIds = {};

# REST API for the IT access-request workflow.
service /it on new http:Listener(servicePort) {

    # Trigger endpoint called by the service portal when a user requests access.
    #
    # + req - Access-request payload
    # + return - Workflow id, or `400 Bad Request` / error
    resource function post 'access\-requests(@http:Payload AccessRequest req)
            returns record {| string workflowId; string requestId; |}|http:BadRequest|error {
        if req.requestId.trim() == "" || req.requesterPhone.trim() == "" {
            return <http:BadRequest>{body: "requestId and requesterPhone are required"};
        }
        lock {
            string? existing = requestWorkflowIds[req.requestId];
            if existing is string {
                log:printInfo("access-request workflow already started (idempotent hit)",
                        workflowId = existing, requestId = req.requestId);
                return {workflowId: existing, requestId: req.requestId};
            }
        }
        string|error workflowIdOrError = workflow:run(handleAccessRequest, req);
        if workflowIdOrError is error {
            return workflowIdOrError;
        }
        string workflowId = workflowIdOrError;
        lock {
            requestWorkflowIds[req.requestId] = workflowId;
        }
        log:printInfo("access-request workflow started",
                workflowId = workflowId, requestId = req.requestId);
        return {workflowId, requestId: req.requestId};
    }

    # Jira webhook callback. Configure a Jira automation rule on the
    # IT/SEC project to POST here on issue resolution.
    #
    # + workflowId - Target workflow id
    # + decision - Approval decision payload
    # + request - Incoming HTTP request (used for secret verification)
    # + return - `accepted` envelope, or an error
    resource function post 'access\-requests/[string workflowId]/'jira\-resolved(
            http:Request request, @http:Payload AccessDecision decision)
            returns record {| string status; |}|http:Unauthorized|error {
        if jiraWebhookSecret != "" {
            string|http:HeaderNotFoundError secret = request.getHeader("X-Webhook-Secret");
            if secret is http:HeaderNotFoundError || secret != jiraWebhookSecret {
                return <http:Unauthorized>{};
            }
        }
        check workflow:sendData(handleAccessRequest, workflowId, "approval", decision);
        return {status: "accepted"};
    }

    # Returns the workflow's final result.
    #
    # + workflowId - Target workflow id
    # + return - Final execution info, or an error
    resource function get 'access\-requests/[string workflowId]()
            returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
