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
// Use case: CRM → ERP customer sync (Sales operations / RevOps)
// =============================================================================
//
// Trigger      : HTTP webhook from an upstream CRM (e.g. a sign-up form, a
//                self-service portal, or another CRM) — `POST /crm/customers`.
// Connectors   : ballerinax/salesforce, ballerinax/slack,
//                ballerinax/googleapis.gmail
// Pattern      : Pure transactional automation across three SaaS systems.
//                  1) Create a Salesforce `Account`.
//                  2) Create a Salesforce `Contact` linked to the Account.
//                  3) Notify RevOps in Slack.
//                  4) Send a welcome email to the primary contact via Gmail.
//                Each step is a real connector call; intermittent failures
//                are absorbed by activity retries, and the workflow runtime
//                holds state across worker restarts.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/salesforce;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable int servicePort = 8120;

configurable string slackBotToken = "";
configurable string crmOpsChannel = "#crm-ops";

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "crm-ops@example.com";

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final slack:Client slackClient = check new ({auth: {token: slackBotToken}});

final salesforce:Client salesforceClient = check new ({
    baseUrl: salesforceBaseUrl,
    auth: {token: salesforceAccessToken}
});

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

# Inbound customer payload from the upstream CRM webhook.
#
# + requestId - Idempotency key supplied by the caller
# + companyName - Customer company / account name
# + industry - Industry classification
# + annualRevenueUsd - Reported annual revenue in USD
# + region - Sales region
# + primaryContactName - Primary contact display name
# + primaryContactEmail - Primary contact email address
public type CustomerRequest record {|
    string requestId;
    string companyName;
    string industry;
    decimal annualRevenueUsd;
    string region;
    string primaryContactName;
    string primaryContactEmail;
|};

# Result of a customer sync workflow.
#
# + requestId - Idempotency key from the request
# + accountId - Salesforce Account Id
# + contactId - Salesforce Contact Id
# + slackTs - Slack message timestamp
# + welcomeMessageId - Gmail message id of the welcome email
public type CustomerSyncResult record {|
    string requestId;
    string accountId;
    string contactId;
    string slackTs;
    string welcomeMessageId;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Creates a Salesforce `Account` for the customer.
#
# + req - Inbound customer request
# + return - Salesforce Account Id, or an error
@workflow:Activity
isolated function createSalesforceAccount(CustomerRequest req) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Account", {
        "Name": req.companyName,
        "Industry": req.industry,
        "AnnualRevenue": req.annualRevenueUsd,
        "BillingState": req.region
    });
    log:printInfo("[salesforce] Account created",
            requestId = req.requestId, accountId = resp.id);
    return resp.id;
}

# Creates a Salesforce `Contact` linked to the given Account.
#
# + accountId - Salesforce Account Id from the previous step
# + req - Inbound customer request
# + return - Salesforce Contact Id, or an error
@workflow:Activity
isolated function createSalesforceContact(string accountId, CustomerRequest req)
        returns string|error {
    string firstName;
    string lastName;
    int? sep = req.primaryContactName.indexOf(" ");
    if sep is int {
        firstName = req.primaryContactName.substring(0, sep);
        lastName = req.primaryContactName.substring(sep + 1);
    } else {
        firstName = "";
        lastName = req.primaryContactName;
    }
    salesforce:CreationResponse resp = check salesforceClient->create("Contact", {
        "AccountId": accountId,
        "FirstName": firstName,
        "LastName": lastName,
        "Email": req.primaryContactEmail
    });
    log:printInfo("[salesforce] Contact created",
            requestId = req.requestId, contactId = resp.id);
    return resp.id;
}

# Notifies the RevOps Slack channel that a customer was synced.
#
# + text - Slack message text
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function postCrmOpsSlack(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: crmOpsChannel,
        text
    });
    log:printInfo("[slack] CRM sync posted", channel = crmOpsChannel, ts = resp.ts);
    return resp.ts;
}

# Sends a welcome email to the primary contact.
#
# + to - Primary contact email
# + subject - Email subject
# + body - Email body (plain text)
# + return - Gmail message id, or an error
@workflow:Activity
isolated function sendWelcomeEmail(string to, string subject, string body)
        returns string|error {
    gmail:Message sent = check gmailClient->/users/me/messages/send.post({
        to: [to],
        'from: gmailFromAddress,
        subject,
        bodyInText: body
    });
    log:printInfo("[gmail] welcome email sent", to = to, gmailMessageId = sent.id);
    return sent.id;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Synchronizes a customer record into Salesforce and notifies downstream
# operations channels.
#
# + ctx - Workflow context
# + req - Inbound customer request
# + return - Customer sync result, or an error
@workflow:Workflow
function syncCustomer(workflow:Context ctx, CustomerRequest req)
        returns CustomerSyncResult|error {
    string accountId = check ctx->callActivity(createSalesforceAccount,
            {"req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string contactId = check ctx->callActivity(createSalesforceContact,
            {"accountId": accountId, "req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string slackTs = check ctx->callActivity(postCrmOpsSlack,
            {"text": string `:white_check_mark: New customer *${req.companyName}* ` +
                    string `(${req.region}) synced. Account ${accountId}, Contact ${contactId}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    string welcomeMessageId = check ctx->callActivity(sendWelcomeEmail,
            {
                "to": req.primaryContactEmail,
                "subject": string `Welcome to our platform, ${req.companyName}!`,
                "body": string `Hi ${req.primaryContactName},\n\n` +
                        string `Your account has been provisioned. ` +
                        string `Salesforce Account: ${accountId}.\n\n` +
                        string `– CRM Operations`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    return {
        requestId: req.requestId,
        accountId,
        contactId,
        slackTs,
        welcomeMessageId
    };
}

// -----------------------------------------------------------------------------
// HTTP listener — webhook entry point
// -----------------------------------------------------------------------------

# REST API for the CRM → ERP customer sync workflow.
service /crm on new http:Listener(servicePort) {

    # Webhook entry point. Starts one workflow per inbound customer.
    #
    # + req - Customer payload
    # + return - Workflow id wrapper, or an error
    resource function post customers(@http:Payload CustomerRequest req)
            returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(syncCustomer, req);
        log:printInfo("customer sync workflow started",
                requestId = req.requestId, workflowId = workflowId);
        return {workflowId};
    }

    # Returns the workflow execution result. Blocks until the workflow ends.
    #
    # + workflowId - Workflow id
    # + return - Workflow execution info, or an error
    resource function get customers/[string workflowId]()
            returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
