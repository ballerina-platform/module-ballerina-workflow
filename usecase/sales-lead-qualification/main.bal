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
// Use case: Sales Lead Qualification (Sales automation, with SLA timeout)
// =============================================================================
//
// Trigger      : HTTP webhook from a marketing form (`POST /sales/leads`).
// Connectors   : ballerinax/salesforce, ballerinax/twilio, ballerinax/slack
// Pattern      : SLA-bounded human interaction.
//                  1) Create a Salesforce `Lead` and notify the assigned
//                     rep on Slack and via Twilio SMS.
//                  2) Pause on a typed signal channel for up to `slaHours`.
//                  3) On rep response within SLA → update the Lead and
//                     post the outcome on `#sales`.
//                  4) On SLA breach → update the Lead status to
//                     `Stale - Escalated` and post on `#sales-leadership`.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/salesforce;
import ballerinax/slack;
import ballerinax/twilio;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceClientId = "";
configurable string salesforceClientSecret = "";
configurable string salesforceRefreshToken = "";
configurable string salesforceRefreshUrl = "https://login.salesforce.com/services/oauth2/token";

configurable string slackBotToken = "";
configurable string salesChannel = "#sales";
configurable string managerChannel = "#sales-leadership";

configurable string twilioAccountSid = "";
configurable string twilioApiKey = "";
configurable string twilioApiSecret = "";
configurable string twilioFromNumber = "+15555550100";

configurable int slaHours = 24;
configurable int servicePort = 8104;

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final salesforce:Client salesforceClient = check new ({
    baseUrl: salesforceBaseUrl,
    auth: {
        clientId: salesforceClientId,
        clientSecret: salesforceClientSecret,
        refreshToken: salesforceRefreshToken,
        refreshUrl: salesforceRefreshUrl
    }
});

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final twilio:Client twilioClient = check new ({
    auth: {accountSid: twilioAccountSid, apiKey: twilioApiKey, apiSecret: twilioApiSecret}
});

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Inbound lead payload from a marketing-form webhook.
#
# + leadId - External lead id (idempotency key)
# + firstName - Lead first name
# + lastName - Lead last name
# + company - Company
# + email - Lead email
# + phone - Lead phone (E.164)
# + source - Lead source (e.g. `Web`, `Trade Show`)
# + estimatedValueUsd - Estimated deal value
# + assignedRepName - Display name of the assigned sales rep
# + assignedRepPhone - Sales rep phone (E.164) for SMS notification
public type LeadInput record {|
    string leadId;
    string firstName;
    string lastName;
    string company;
    string email;
    string phone;
    string 'source = "Web";
    decimal estimatedValueUsd = 0;
    string assignedRepName;
    string assignedRepPhone;
|};

# Qualification decision from the rep, delivered by an HTTP callback.
#
# + outcome - `Qualified`, `Disqualified`, or `Nurture`
# + notes - Free-text notes the rep recorded
public type QualificationDecision record {|
    string outcome;
    string notes = "";
|};

# Final workflow result.
#
# + leadId - External lead id
# + salesforceLeadId - Salesforce Lead Id
# + status - `QUALIFIED`, `DISQUALIFIED`, `NURTURE`, or `ESCALATED`
# + slaBreached - `true` if the rep didn't respond within `slaHours`
public type LeadResult record {|
    string leadId;
    string salesforceLeadId;
    string status;
    boolean slaBreached;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Creates a Salesforce `Lead` from the inbound payload.
#
# + lead - Inbound lead payload
# + return - Salesforce Lead Id, or an error
@workflow:Activity
isolated function createSalesforceLead(LeadInput lead) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Lead", {
        "FirstName": lead.firstName,
        "LastName": lead.lastName,
        "Company": lead.company,
        "Email": lead.email,
        "Phone": lead.phone,
        "LeadSource": lead.'source,
        "Status": "Open - Not Contacted"
    });
    log:printInfo("[salesforce] Lead created", leadId = resp.id, externalId = lead.leadId);
    return resp.id;
}

# Updates the Salesforce Lead's `Status` field.
#
# + salesforceLeadId - Salesforce Lead Id
# + status - New `Status` value
# + return - `()` on success, or an error
@workflow:Activity
isolated function updateLeadStatus(string salesforceLeadId, string status) returns error? {
    check salesforceClient->update("Lead", salesforceLeadId, {"Status": status});
    log:printInfo("[salesforce] Lead status updated",
            salesforceLeadId = salesforceLeadId, status = status);
}

# Posts a message to a Slack channel.
#
# + channel - Channel name or id
# + text - Message text
# + return - Slack timestamp, or an error
@workflow:Activity
isolated function postSlackMessage(string channel, string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel,
        text
    });
    log:printInfo("[slack] message posted", channel = channel, ts = resp.ts);
    return resp.ts;
}

# Sends an SMS via Twilio.
#
# + toNumber - Recipient phone number (E.164)
# + body - SMS body
# + return - Twilio message SID, or an error
@workflow:Activity
isolated function sendSms(string toNumber, string body) returns string|error {
    twilio:Message msg = check twilioClient->createMessage({
        To: toNumber,
        From: twilioFromNumber,
        Body: body
    });
    string sid = msg?.sid ?: "";
    log:printInfo("[twilio] SMS sent", to = toNumber, sid = sid);
    return sid;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Qualifies an inbound lead within an SLA window.
#
# + ctx - Workflow context
# + lead - Inbound lead payload
# + events - Qualification signal channel populated by the rep's response
# + return - Final lead result
@workflow:Workflow
function qualifyLead(
        workflow:Context ctx,
        LeadInput lead,
        record {| future<QualificationDecision> qualification; |} events
) returns LeadResult|error {

    string sfLeadId = check ctx->callActivity(createSalesforceLead,
            {"lead": lead},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string wfId = check ctx.getWorkflowId();
    string slackText = string `:dart: New lead *${lead.firstName} ${lead.lastName}* ` +
            string `(${lead.company}, $${lead.estimatedValueUsd}) assigned to ` +
            string `${lead.assignedRepName}. Workflow: ${wfId}`;
    string _ = check ctx->callActivity(postSlackMessage,
            {"channel": salesChannel, "text": slackText},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string smsBody = string `New lead assigned: ${lead.firstName} ${lead.lastName} ` +
            string `at ${lead.company} (${lead.phone}). Please respond within ${slaHours}h.`;
    string _ = check ctx->callActivity(sendSms,
            {"toNumber": lead.assignedRepPhone, "body": smsBody},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    // Wait for the rep's response, bounded by SLA.
    [QualificationDecision]|error awaited = ctx->await(
            [events.qualification],
            timeout = {hours: slaHours});

    if awaited is error {
        // SLA breach — escalate.
        () _ = check ctx->callActivity(updateLeadStatus,
                {"salesforceLeadId": sfLeadId, "status": "Stale - Escalated"},
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        string escalateText = string `:rotating_light: SLA breached: lead *${lead.firstName} ` +
                string `${lead.lastName}* (${lead.company}) was not contacted by ` +
                string `${lead.assignedRepName} within ${slaHours}h.`;
        string _ = check ctx->callActivity(postSlackMessage,
                {"channel": managerChannel, "text": escalateText},
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        return {
            leadId: lead.leadId,
            salesforceLeadId: sfLeadId,
            status: "ESCALATED",
            slaBreached: true
        };
    }

    [QualificationDecision] [decision] = awaited;
    string normalizedOutcome = decision.outcome.toLowerAscii();
    string status = normalizedOutcome == "qualified"
            ? "Working - Contacted"
            : (normalizedOutcome == "disqualified"
                    ? "Closed - Not Converted"
                    : "Open - Not Contacted");
    () _ = check ctx->callActivity(updateLeadStatus,
            {"salesforceLeadId": sfLeadId, "status": status},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string outcomeText = string `:white_check_mark: Lead *${lead.firstName} ${lead.lastName}* ` +
            string `marked *${decision.outcome}* by ${lead.assignedRepName}. ${decision.notes}`;
    string _ = check ctx->callActivity(postSlackMessage,
            {"channel": salesChannel, "text": outcomeText},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string finalStatus = normalizedOutcome == "qualified" ? "QUALIFIED"
            : normalizedOutcome == "disqualified" ? "DISQUALIFIED"
            : "NURTURE";
    return {
        leadId: lead.leadId,
        salesforceLeadId: sfLeadId,
        status: finalStatus,
        slaBreached: false
    };
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

# REST API for the lead-qualification workflow.
service /sales on new http:Listener(servicePort) {

    # Inbound lead webhook from the marketing form.
    #
    # + lead - Inbound lead payload
    # + return - Workflow id and lead id, `400 Bad Request`, or an error
    resource function post leads(@http:Payload LeadInput lead)
            returns record {| string workflowId; string leadId; |}|http:BadRequest|error {
        if lead.leadId.trim() == "" || lead.assignedRepPhone.trim() == "" {
            return <http:BadRequest>{body: "leadId and assignedRepPhone are required"};
        }
        string workflowId = check workflow:run(qualifyLead, lead);
        log:printInfo("lead-qualification workflow started",
                workflowId = workflowId, leadId = lead.leadId);
        return {workflowId, leadId: lead.leadId};
    }

    # Rep-response callback. The rep's UI (or a Salesforce Flow on `Lead`
    # status change) POSTs the qualification decision here, which delivers
    # the `qualification` signal to the workflow.
    #
    # + workflowId - Target workflow id
    # + decision - Qualification decision payload
    # + return - `accepted` envelope, or an error
    resource function post leads/[string workflowId]/qualification(
            @http:Payload QualificationDecision decision) returns record {| string status; |}|error {
        check workflow:sendData(qualifyLead, workflowId, "qualification", decision);
        return {status: "accepted"};
    }

    # Returns the workflow's final result.
    #
    # + workflowId - Target workflow id
    # + return - Final execution info, or an error
    resource function get leads/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
