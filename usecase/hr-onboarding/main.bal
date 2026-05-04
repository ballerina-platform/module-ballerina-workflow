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
// Use case: HR Onboarding (HR automation)
// =============================================================================
//
// Trigger      : HTTP webhook from the HRIS (`POST /hr/new-hire`).
// Connectors   : ballerinax/slack, ballerinax/jira, ballerinax/googleapis.gmail
// Human step   : Equipment approval — workflow notifies the team on Slack,
//                creates a Jira task assigned to the hiring manager, and
//                pauses on a typed signal channel until the Jira webhook
//                calls back with the issue's resolution.
// Final step   : Sends a personalised welcome email via Gmail.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/jira;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable string slackBotToken = "";
configurable string onboardingChannel = "#onboarding";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "hr@example.com";

configurable string jiraBaseUrl = "https://your-org.atlassian.net/rest";
configurable string jiraEmail = "";
configurable string jiraApiToken = "";
configurable string jiraProjectKey = "HR";
configurable string jiraEquipmentIssueType = "Task";

configurable decimal equipmentApprovalThresholdUsd = 1500.00;
configurable int servicePort = 8101;

// -----------------------------------------------------------------------------
// Connector clients (created once at module load)
// -----------------------------------------------------------------------------

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final jira:Client jiraClient = check new (
        {auth: {username: jiraEmail, password: jiraApiToken}},
        jiraBaseUrl);

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

# A new-hire event delivered by the HRIS webhook.
#
# + employeeId - Stable HRIS identifier of the new hire
# + fullName - Full legal name
# + workEmail - Pre-allocated work email address
# + department - Department/team
# + jobTitle - Job title
# + managerJiraAccountId - Hiring manager's Jira `accountId` (used as Jira assignee)
# + startDate - Start date in `YYYY-MM-DD` format
# + equipmentRequestUsd - Estimated equipment cost; `0` if none requested
public type NewHireEvent record {|
    string employeeId;
    string fullName;
    string workEmail;
    string department;
    string jobTitle;
    string managerJiraAccountId;
    string startDate;
    decimal equipmentRequestUsd = 0;
|};

# Equipment-approval payload delivered by the Jira webhook callback.
#
# + id - Correlation id (the workflow id, echoed back by the webhook)
# + jiraIssueKey - Resolved Jira issue key (e.g. `HR-123`)
# + resolution - `"Done"`, `"Approved"`, `"Won't Do"`, …
# + approverDisplayName - Jira display name of the resolver
# + budgetUsd - Approved budget; `0` when rejected
public type EquipmentApproval record {|
    string id;
    string jiraIssueKey;
    string resolution;
    string approverDisplayName;
    decimal budgetUsd = 0;
|};

# Final result returned by the onboarding workflow.
#
# + employeeId - The new hire's id
# + status - One of `COMPLETED`, `COMPLETED_WITHOUT_EQUIPMENT`, `REJECTED`
# + jiraIssueKey - Created equipment-approval Jira issue (when applicable)
# + message - Human-readable summary
public type OnboardingResult record {|
    string employeeId;
    string status;
    string jiraIssueKey?;
    string message;
|};

// -----------------------------------------------------------------------------
// Activities — each one is a real connector call with configurable retries
// -----------------------------------------------------------------------------

# Sends an `#onboarding` Slack message announcing the new hire.
#
# + fullName - New hire's full name
# + department - Department they're joining
# + startDate - Start date string
# + return - Slack message timestamp on success, or an error
@workflow:Activity
isolated function announceOnSlack(string fullName, string department, string startDate)
        returns string|error {
    string text = string `:wave: Welcome ${fullName} to the ${department} team! ` +
            string `Start date: ${startDate}.`;
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: onboardingChannel,
        text
    });
    string ts = resp.ts;
    log:printInfo("[slack] onboarding announcement posted",
            channel = onboardingChannel, ts = ts);
    return ts;
}

# Creates a Jira "Equipment approval" task assigned to the hiring manager.
# The Jira issue's *issue key* is returned so we can correlate the resolution.
#
# + workflowId - Workflow id, embedded in the issue description so the
#                manager (or a Jira automation rule) can call back to the
#                right workflow instance
# + employeeId - HRIS id of the new hire
# + fullName - New hire's full name
# + amountUsd - Requested equipment amount
# + managerAccountId - Jira `accountId` of the manager (assignee)
# + return - Jira issue key (e.g. `HR-123`), or an error
@workflow:Activity
isolated function createEquipmentApprovalTask(string workflowId, string employeeId, string fullName,
        decimal amountUsd, string managerAccountId) returns string|error {
    string summary = string `Approve equipment for ${fullName} ($${amountUsd})`;
    string description = string `New hire onboarding workflow ${workflowId} ` +
            string `is awaiting approval for ${employeeId}'s equipment request ` +
            string `of $${amountUsd}.`;

    jira:CreatedIssue created = check jiraClient->/api/'3/issue.post({
        fields: {
            "project": {"key": jiraProjectKey},
            "summary": summary,
            "description": description,
            "issuetype": {"name": jiraEquipmentIssueType},
            "assignee": {"accountId": managerAccountId}
        }
    });
    string issueKey = created["key"] ?: "";
    log:printInfo("[jira] equipment-approval task created",
            issueKey = issueKey, workflowId = workflowId);
    return issueKey;
}

# Sends a personalised welcome email via Gmail.
#
# + workEmail - Recipient mailbox
# + fullName - Recipient name
# + summaryText - Pre-formatted summary text included in the email body
# + return - Gmail message id, or an error
@workflow:Activity
isolated function sendWelcomeEmail(string workEmail, string fullName, string summaryText)
        returns string|error {
    gmail:Message sent = check gmailClient->/users/["me"]/messages.post({
        to: [workEmail],
        'from: gmailFromAddress,
        subject: string `Welcome to the team, ${fullName}!`,
        bodyInText: summaryText
    });
    string mid = sent.id;
    log:printInfo("[gmail] welcome email sent", to = workEmail, gmailMessageId = mid);
    return mid;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Onboards a new hire end-to-end.
#
# 1. Slack announcement.
# 2. If equipment cost > threshold, create a Jira approval task assigned to
#    the hiring manager and durably pause on the `equipmentApproval` signal.
# 3. On the Jira webhook callback, branch on the resolution.
# 4. Send a welcome email via Gmail.
#
# + ctx - Workflow context
# + hire - HRIS payload
# + events - Equipment-approval signal channel populated by the Jira webhook
# + return - Onboarding result
@workflow:Workflow
function onboardEmployee(
        workflow:Context ctx,
        NewHireEvent hire,
        record {| future<EquipmentApproval> equipmentApproval; |} events
) returns OnboardingResult|error {

    string _ = check ctx->callActivity(announceOnSlack,
            {"fullName": hire.fullName, "department": hire.department, "startDate": hire.startDate},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string? jiraIssueKey = ();
    boolean equipmentApproved = false;
    decimal approvedBudget = 0;

    if hire.equipmentRequestUsd > 0d {
        if hire.equipmentRequestUsd > equipmentApprovalThresholdUsd {
            // 1) Notify the team & 2) create the task in Jira.
            string wfId = check ctx.getWorkflowId();
            string issueKey = check ctx->callActivity(createEquipmentApprovalTask,
                    {
                        "workflowId": wfId,
                        "employeeId": hire.employeeId,
                        "fullName": hire.fullName,
                        "amountUsd": hire.equipmentRequestUsd,
                        "managerAccountId": hire.managerJiraAccountId
                    },
                    retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
            jiraIssueKey = issueKey;

            // 3) Wait for the Jira webhook callback.
            EquipmentApproval decision = check wait events.equipmentApproval;
            if decision.resolution == "Done" || decision.resolution.toLowerAscii() == "approved" {
                equipmentApproved = true;
                approvedBudget = decision.budgetUsd;
            }
            if !equipmentApproved {
                string body = string `Hi ${hire.fullName}, your accounts are ready, but your ` +
                        string `equipment request was not approved (Jira: ${decision.jiraIssueKey}).`;
                string _ = check ctx->callActivity(sendWelcomeEmail,
                        {"workEmail": hire.workEmail, "fullName": hire.fullName, "summaryText": body},
                        retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
                return {
                    employeeId: hire.employeeId,
                    status: "REJECTED",
                    jiraIssueKey: decision.jiraIssueKey,
                    message: string `Equipment request rejected by ${decision.approverDisplayName}`
                };
            }
        } else {
            // Auto-approved: under threshold.
            equipmentApproved = true;
            approvedBudget = hire.equipmentRequestUsd;
        }
    }

    string outcome = equipmentApproved
            ? string `with equipment (budget $${approvedBudget})`
            : "without equipment";
    string body = string `Welcome ${hire.fullName}! Your accounts have been provisioned (${outcome}).`;
    string _ = check ctx->callActivity(sendWelcomeEmail,
            {"workEmail": hire.workEmail, "fullName": hire.fullName, "summaryText": body},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    OnboardingResult result = {
        employeeId: hire.employeeId,
        status: equipmentApproved ? "COMPLETED" : "COMPLETED_WITHOUT_EQUIPMENT",
        message: body
    };
    if jiraIssueKey is string {
        result.jiraIssueKey = jiraIssueKey;
    }
    return result;
}

// -----------------------------------------------------------------------------
// HTTP listener — webhook trigger and Jira completion callback
// -----------------------------------------------------------------------------

# REST API for the HR-onboarding workflow.
#
# Endpoints:
#   `POST /hr/new-hire`                                — HRIS webhook trigger
#   `POST /hr/onboarding/{workflowId}/jira-resolved`   — Jira webhook callback
#   `GET  /hr/onboarding/{workflowId}`                 — fetch result
service /hr on new http:Listener(servicePort) {

    # Webhook receiver for new-hire events from the HRIS. Starts a durable
    # onboarding workflow and immediately returns the workflow id.
    #
    # + hire - HRIS new-hire payload
    # + return - Workflow id and echoed employee id, `400 Bad Request`, or an error
    resource function post 'new\-hire(@http:Payload NewHireEvent hire)
            returns record {| string workflowId; string employeeId; |}|http:BadRequest|error {
        if hire.employeeId.trim() == "" || hire.workEmail.trim() == "" {
            return <http:BadRequest>{body: "employeeId and workEmail are required"};
        }
        string workflowId = check workflow:run(onboardEmployee, hire);
        log:printInfo("onboarding workflow started",
                workflowId = workflowId, employeeId = hire.employeeId);
        return {workflowId, employeeId: hire.employeeId};
    }

    # Jira webhook callback. Configure a Jira automation rule on the
    # equipment-approval Jira project so that, when an issue is transitioned
    # to `Done` (approved) or `Won't Do` (rejected), Jira POSTs to this
    # endpoint with the workflow id (read from the issue description) and
    # the resolution.
    #
    # + workflowId - Target workflow id
    # + decision - Mapped resolution payload
    # + return - `accepted` envelope, or an error
    resource function post onboarding/[string workflowId]/'jira\-resolved(@http:Payload EquipmentApproval decision)
            returns record {| string status; |}|error {
        check workflow:sendData(onboardEmployee, workflowId, "equipmentApproval", decision);
        return {status: "accepted"};
    }

    # Returns the workflow's final result. Blocks until the workflow completes.
    #
    # + workflowId - Target workflow id
    # + return - Final execution info, or an error
    resource function get onboarding/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
