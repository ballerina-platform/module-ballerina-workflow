// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// =============================================================================
// Use case: Support Case Resolution (Customer support automation)
// =============================================================================
//
// Trigger      : REST API from a support portal, chatbot, or mobile app
//                (`POST /support/cases`).
// Connectors   : ballerinax/salesforce, ballerinax/slack, ballerinax/googleapis.gmail
// Pattern      : Long-running workflow with durable checkpoints.
//                  1) Create a Salesforce Case and request diagnostics.
//                  2) Pause until the customer provides diagnostics.
//                  3) Pause until an engineer records triage.
//                  4) If needed, pause until a deployment webhook arrives.
//                  5) Pause until customer confirmation, then close the Case.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/salesforce;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable string slackBotToken = "";
configurable string supportChannel = "#support-engineering";
configurable string incidentChannel = "#incident-response";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "support@example.com";

configurable int diagnosticsTimeoutHours = 48;
configurable int confirmationTimeoutHours = 72;
configurable int servicePort = 8105;

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final salesforce:Client salesforceClient = check new ({
    baseUrl: salesforceBaseUrl,
    auth: {token: salesforceAccessToken}
});

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final gmail:Client gmailClient = check new ({
    auth: {
        refreshToken: gmailRefreshToken,
        clientId: gmailClientId,
        clientSecret: gmailClientSecret
    }
});

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Inbound support request from a portal, chatbot, or mobile app.
#
# + caseRef - External support reference or idempotency key
# + customerName - Customer contact name
# + customerEmail - Customer contact email
# + companyName - Customer company
# + subject - Short issue summary
# + description - Customer-provided description
# + severity - `Low`, `Medium`, `High`, or `Critical`
public type SupportRequest record {|
    string caseRef;
    string customerName;
    string customerEmail;
    string companyName;
    string subject;
    string description;
    string severity;
|};

# Diagnostics supplied later by the customer.
#
# + logsUrl - URL to logs or attachment storage
# + reproductionSteps - Steps to reproduce the issue
# + environment - Affected environment
public type DiagnosticsProvided record {|
    string logsUrl;
    string reproductionSteps;
    string environment;
|};

# Engineer triage decision from an internal support console.
#
# + engineerName - Engineer who triaged the case
# + classification - `Configuration`, `Product Bug`, `Customer Error`, etc.
# + requiresDeployment - `true` when a product fix or release is required
# + notes - Triage notes
public type EngineerTriaged record {|
    string engineerName;
    string classification;
    boolean requiresDeployment;
    string notes = "";
|};

# Deployment event from release, CI/CD, or incident-management tooling.
#
# + deploymentId - Release or deployment id
# + version - Version or artifact name
# + deployedBy - Actor that completed the deployment
public type FixDeployed record {|
    string deploymentId;
    string version;
    string deployedBy;
|};

# Customer confirmation from the portal, chatbot, or email-link handler.
#
# + confirmed - Whether the customer confirmed resolution
# + comment - Optional customer comment
public type CustomerConfirmed record {|
    boolean confirmed;
    string comment = "";
|};

# Final support-case result.
#
# + caseRef - External support reference
# + status - `RESOLVED`, `ACTION_REQUIRED`, or `TIMED_OUT`
# + salesforceCaseId - Salesforce Case Id
# + message - Human-readable summary
public type SupportResult record {|
    string caseRef;
    string status;
    string salesforceCaseId;
    string message;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Creates a Salesforce Case for the incoming support request.
#
# + workflowId - Workflow id embedded in the Case description
# + req - Inbound support request
# + return - Salesforce Case Id, or an error
@workflow:Activity
isolated function createSupportCase(string workflowId, SupportRequest req) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Case", {
        "Subject": req.subject,
        "Description": string `${req.description}\n\nWorkflow: ${workflowId}\nExternal ref: ${req.caseRef}`,
        "Origin": "Workflow",
        "Priority": req.severity == "Critical" || req.severity == "High" ? "High" : "Medium",
        "Status": "New"
    });
    log:printInfo("[salesforce] support Case created",
            caseId = resp.id, workflowId = workflowId, caseRef = req.caseRef);
    return resp.id;
}

# Updates the Salesforce Case status and description.
#
# + caseId - Salesforce Case Id
# + status - New Case status
# + description - Updated description
# + return - `()` on success, or an error
@workflow:Activity
isolated function updateSupportCase(string caseId, string status, string description) returns error? {
    error? result = salesforceClient->update("Case", caseId, {
        "Status": status,
        "Description": description
    });
    if result is error {
        return result;
    }
    log:printInfo("[salesforce] support Case updated", caseId = caseId, status = status);
}

# Sends a customer email through Gmail.
#
# + to - Recipient email address
# + subject - Email subject
# + body - Plain-text body
# + return - Gmail message id, or an error
@workflow:Activity
isolated function sendCustomerEmail(string to, string subject, string body) returns string|error {
    gmail:Message sent = check gmailClient->/users/["me"]/messages.post({
        to: [to],
        'from: gmailFromAddress,
        subject,
        bodyInText: body
    });
    string messageId = sent.id;
    log:printInfo("[gmail] support email sent", to = to, gmailMessageId = messageId);
    return messageId;
}

# Posts a support update to Slack.
#
# + channel - Slack channel
# + text - Message text
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function postSlackUpdate(string channel, string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel,
        text
    });
    log:printInfo("[slack] support update posted", channel = channel, ts = resp.ts);
    return resp.ts;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Resolves a support case through asynchronous customer, engineer, and release
# checkpoints.
#
# + ctx - Workflow context
# + req - Inbound support request
# + events - Typed checkpoint events delivered by external callbacks
# + return - Final support result
@workflow:Workflow
function resolveSupportCase(
        workflow:Context ctx,
        SupportRequest req,
        record {|
            future<DiagnosticsProvided> diagnosticsProvided;
            future<EngineerTriaged> engineerTriaged;
            future<FixDeployed> fixDeployed;
            future<CustomerConfirmed> customerConfirmed;
        |} events
) returns SupportResult|error {

    string wfId = check ctx.getWorkflowId();
    string caseId = check ctx->callActivity(createSupportCase,
            {"workflowId": wfId, "req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string _ = check ctx->callActivity(sendCustomerEmail,
            {
                "to": req.customerEmail,
                "subject": string `Support case ${req.caseRef}: diagnostics needed`,
                "body": string `Hi ${req.customerName},\n\nWe opened support case ${req.caseRef} ` +
                        string `for ${req.companyName}. Please upload logs and reproduction steps ` +
                        string `from your portal or chatbot so our engineers can continue.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string slackText = string `:ticket: New ${req.severity} support case *${req.caseRef}* ` +
            string `for *${req.companyName}*: ${req.subject}. Workflow: ${wfId}`;
    string _ = check ctx->callActivity(postSlackUpdate,
            {"channel": supportChannel, "text": slackText},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    [DiagnosticsProvided]|error diagnosticsAwaited = ctx->await(
            [events.diagnosticsProvided],
            timeout = {hours: diagnosticsTimeoutHours});

    if diagnosticsAwaited is error {
        string _ = check ctx->callActivity(updateSupportCase,
                {
                    "caseId": caseId,
                    "status": "Waiting on Customer",
                    "description": string `${req.description}\n\nTimed out waiting for diagnostics.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        string _ = check ctx->callActivity(sendCustomerEmail,
                {
                    "to": req.customerEmail,
                    "subject": string `Support case ${req.caseRef}: waiting for diagnostics`,
                    "body": string `Hi ${req.customerName},\n\nWe still need diagnostics for ` +
                            string `${req.caseRef}. The case is waiting on your response.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        return {
            caseRef: req.caseRef,
            status: "TIMED_OUT",
            salesforceCaseId: caseId,
            message: "Diagnostics were not received before the timeout."
        };
    }

    [DiagnosticsProvided] [diagnostics] = diagnosticsAwaited;
    string diagnosticDescription = string `${req.description}\n\nDiagnostics:\n` +
            string `Logs: ${diagnostics.logsUrl}\nEnvironment: ${diagnostics.environment}\n` +
            string `Steps: ${diagnostics.reproductionSteps}`;
    string _ = check ctx->callActivity(updateSupportCase,
            {"caseId": caseId, "status": "In Progress", "description": diagnosticDescription},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    EngineerTriaged triage = check wait events.engineerTriaged;
    string _ = check ctx->callActivity(updateSupportCase,
            {
                "caseId": caseId,
                "status": triage.requiresDeployment ? "Escalated" : "Working",
                "description": string `${diagnosticDescription}\n\nTriage by ${triage.engineerName}: ` +
                        string `${triage.classification}. ${triage.notes}`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    if triage.requiresDeployment {
        string _ = check ctx->callActivity(postSlackUpdate,
                {
                    "channel": incidentChannel,
                    "text": string `:rotating_light: ${req.caseRef} requires deployment. ` +
                            string `Classification: ${triage.classification}. Owner: ${triage.engineerName}.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

        FixDeployed deployment = check wait events.fixDeployed;
        string _ = check ctx->callActivity(updateSupportCase,
                {
                    "caseId": caseId,
                    "status": "Solution Provided",
                    "description": string `${diagnosticDescription}\n\nFix deployed: ` +
                            string `${deployment.version} (${deployment.deploymentId}) by ${deployment.deployedBy}.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        string _ = check ctx->callActivity(sendCustomerEmail,
                {
                    "to": req.customerEmail,
                    "subject": string `Support case ${req.caseRef}: fix deployed`,
                    "body": string `Hi ${req.customerName},\n\nWe deployed ${deployment.version} ` +
                            string `for ${req.caseRef}. Please confirm whether the issue is resolved.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    } else {
        string _ = check ctx->callActivity(sendCustomerEmail,
                {
                    "to": req.customerEmail,
                    "subject": string `Support case ${req.caseRef}: solution provided`,
                    "body": string `Hi ${req.customerName},\n\nOur engineer ${triage.engineerName} ` +
                            string `reviewed the case and provided this guidance: ${triage.notes}\n\n` +
                            string `Please confirm whether this resolves the issue.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    }

    [CustomerConfirmed]|error confirmationAwaited = ctx->await(
            [events.customerConfirmed],
            timeout = {hours: confirmationTimeoutHours});

    if confirmationAwaited is error {
        string _ = check ctx->callActivity(updateSupportCase,
                {
                    "caseId": caseId,
                    "status": "Solution Provided",
                    "description": string `${diagnosticDescription}\n\nWaiting for customer confirmation.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        return {
            caseRef: req.caseRef,
            status: "ACTION_REQUIRED",
            salesforceCaseId: caseId,
            message: "Solution provided; customer confirmation was not received before the timeout."
        };
    }

    [CustomerConfirmed] [confirmation] = confirmationAwaited;
    boolean resolved = confirmation.confirmed;
    string finalStatus = resolved ? "Closed" : "Escalated";
    string _ = check ctx->callActivity(updateSupportCase,
            {
                "caseId": caseId,
                "status": finalStatus,
                "description": string `${diagnosticDescription}\n\nCustomer confirmation: ${confirmation.comment}`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string _ = check ctx->callActivity(postSlackUpdate,
            {
                "channel": supportChannel,
                "text": string `${resolved ? ":white_check_mark:" : ":warning:"} Support case ` +
                        string `*${req.caseRef}* ${resolved ? "resolved" : "needs more work"}.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string _ = check ctx->callActivity(sendCustomerEmail,
            {
                "to": req.customerEmail,
                "subject": string `Support case ${req.caseRef}: ${resolved ? "closed" : "escalated"}`,
                "body": resolved
                        ? string `Hi ${req.customerName},\n\nThanks for confirming. We closed ${req.caseRef}.`
                        : string `Hi ${req.customerName},\n\nThanks for the update. We escalated ` +
                                string `${req.caseRef} for further investigation.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    return {
        caseRef: req.caseRef,
        status: resolved ? "RESOLVED" : "ACTION_REQUIRED",
        salesforceCaseId: caseId,
        message: resolved ? "Customer confirmed resolution." : "Customer reported that the issue remains."
    };
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

# REST API for the support-case workflow.
service /support on new http:Listener(servicePort) {

    # Starts a support workflow from a portal, chatbot, or mobile app.
    #
    # + req - Support request
    # + return - Workflow id and case reference, `400 Bad Request`, or an error
    resource function post cases(@http:Payload SupportRequest req)
            returns record {| string workflowId; string caseRef; |}|http:BadRequest|error {
        if req.caseRef.trim() == "" || req.customerEmail.trim() == "" {
            return <http:BadRequest>{body: "caseRef and customerEmail are required"};
        }
        string workflowId = check workflow:run(resolveSupportCase, req);
        log:printInfo("support-case workflow started",
                workflowId = workflowId, caseRef = req.caseRef);
        return {workflowId, caseRef: req.caseRef};
    }

    # Delivers customer diagnostics from the portal or chatbot.
    #
    # + workflowId - Target workflow id
    # + diagnostics - Diagnostic payload
    # + return - Accepted envelope, or an error
    resource function post cases/[string workflowId]/diagnostics(
            @http:Payload DiagnosticsProvided diagnostics) returns record {| string status; |}|error {
        check workflow:sendData(resolveSupportCase, workflowId, "diagnosticsProvided", diagnostics);
        return {status: "accepted"};
    }

    # Delivers engineer triage from the internal support console.
    #
    # + workflowId - Target workflow id
    # + triage - Triage payload
    # + return - Accepted envelope, or an error
    resource function post cases/[string workflowId]/triage(
            @http:Payload EngineerTriaged triage) returns record {| string status; |}|error {
        check workflow:sendData(resolveSupportCase, workflowId, "engineerTriaged", triage);
        return {status: "accepted"};
    }

    # Delivers deployment completion from CI/CD or release tooling.
    #
    # + workflowId - Target workflow id
    # + deployment - Deployment payload
    # + return - Accepted envelope, or an error
    resource function post cases/[string workflowId]/deployment(
            @http:Payload FixDeployed deployment) returns record {| string status; |}|error {
        check workflow:sendData(resolveSupportCase, workflowId, "fixDeployed", deployment);
        return {status: "accepted"};
    }

    # Delivers customer confirmation from the portal, chatbot, or email-link
    # handler.
    #
    # + workflowId - Target workflow id
    # + confirmation - Customer confirmation payload
    # + return - Accepted envelope, or an error
    resource function post cases/[string workflowId]/confirmation(
            @http:Payload CustomerConfirmed confirmation) returns record {| string status; |}|error {
        check workflow:sendData(resolveSupportCase, workflowId, "customerConfirmed", confirmation);
        return {status: "accepted"};
    }

    # Returns the workflow's final result.
    #
    # + workflowId - Target workflow id
    # + return - Final execution info, or an error
    resource function get cases/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
