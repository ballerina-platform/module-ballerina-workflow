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
// Use case: Subscription Provisioning Saga (Error handling — compensation)
// =============================================================================
//
// Trigger      : HTTP API from a self-service signup or sales hand-off —
//                `POST /subscriptions`.
// Connectors   : ballerinax/salesforce (system of record),
//                ballerinax/slack (provisioning audit feed),
//                ballerinax/googleapis.gmail (billing-ops notifications).
// Pattern      : Saga / compensation across multiple Salesforce writes.
//                  1) Create a Salesforce `Account` for the new customer.
//                  2) Create a Salesforce `Contact` linked to the Account.
//                  3) Create a Salesforce `Contract` for the subscription.
//                If step 3 fails after retries, run compensating activities
//                in reverse order to delete the Contact and the Account, so
//                the system of record never holds partially provisioned
//                state. The workflow finishes with a `ROLLED_BACK` status
//                instead of `Failed`, and the rollback is auditable on the
//                Slack provisioning channel and via email.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/salesforce;
import ballerinax/slack;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable int servicePort = 8122;

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable string slackBotToken = "";
configurable string provisioningChannel = "#provisioning";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "billing@example.com";
configurable string billingOpsAddress = "billing-ops@example.com";

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

# Inbound subscription request.
#
# + requestId - Idempotency key from the caller
# + companyName - Company name (Salesforce Account name)
# + industry - Industry classification
# + primaryContactFirstName - Primary contact first name
# + primaryContactLastName - Primary contact last name
# + primaryContactEmail - Primary contact email
# + planName - Subscription plan name
# + contractTermMonths - Contract length in months
# + contractStartDate - Contract start date (`YYYY-MM-DD`)
public type SubscriptionRequest record {|
    string requestId;
    string companyName;
    string industry;
    string primaryContactFirstName;
    string primaryContactLastName;
    string primaryContactEmail;
    string planName;
    int contractTermMonths;
    string contractStartDate;
|};

# Result of a subscription provisioning workflow.
#
# + requestId - Idempotency key from the request
# + status - `PROVISIONED` or `ROLLED_BACK`
# + accountId - Salesforce Account Id (empty on rollback)
# + contactId - Salesforce Contact Id (empty on rollback)
# + contractId - Salesforce Contract Id (empty on rollback)
# + failureReason - Error message captured when rollback ran
public type SubscriptionResult record {|
    string requestId;
    string status;
    string accountId = "";
    string contactId = "";
    string contractId = "";
    string failureReason = "";
|};

// -----------------------------------------------------------------------------
// Forward activities (each one creates a Salesforce object)
// -----------------------------------------------------------------------------

# Creates a Salesforce `Account` for the new subscriber.
#
# + req - Subscription request
# + return - Salesforce Account Id, or an error
@workflow:Activity
isolated function createAccount(SubscriptionRequest req) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Account", {
        "Name": req.companyName,
        "Industry": req.industry,
        "Type": "Customer"
    });
    log:printInfo("[salesforce] Account created",
            requestId = req.requestId, accountId = resp.id);
    return resp.id;
}

# Creates a Salesforce `Contact` linked to the given Account.
#
# + accountId - Salesforce Account Id
# + req - Subscription request
# + return - Salesforce Contact Id, or an error
@workflow:Activity
isolated function createContact(string accountId, SubscriptionRequest req)
        returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Contact", {
        "AccountId": accountId,
        "FirstName": req.primaryContactFirstName,
        "LastName": req.primaryContactLastName,
        "Email": req.primaryContactEmail
    });
    log:printInfo("[salesforce] Contact created",
            requestId = req.requestId, contactId = resp.id);
    return resp.id;
}

# Creates a Salesforce `Contract` for the new subscription.
#
# + accountId - Salesforce Account Id
# + req - Subscription request
# + return - Salesforce Contract Id, or an error
@workflow:Activity
isolated function createContract(string accountId, SubscriptionRequest req)
        returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Contract", {
        "AccountId": accountId,
        "Status": "Activated",
        "ContractTerm": req.contractTermMonths,
        "StartDate": req.contractStartDate,
        "Description": string `Plan: ${req.planName} (request ${req.requestId})`
    });
    log:printInfo("[salesforce] Contract created",
            requestId = req.requestId, contractId = resp.id);
    return resp.id;
}

// -----------------------------------------------------------------------------
// Compensating activities (each one deletes what its forward partner created)
// -----------------------------------------------------------------------------

# Deletes a Salesforce `Contact` previously created by `createContact`.
#
# + contactId - Salesforce Contact Id to delete
# + return - `()` on success, or an error
@workflow:Activity
isolated function deleteContact(string contactId) returns error? {
    error? r = salesforceClient->delete("Contact", contactId);
    if r is error {
        return r;
    }
    log:printInfo("[salesforce][compensation] Contact deleted", contactId = contactId);
}

# Deletes a Salesforce `Account` previously created by `createAccount`.
#
# + accountId - Salesforce Account Id to delete
# + return - `()` on success, or an error
@workflow:Activity
isolated function deleteAccount(string accountId) returns error? {
    error? r = salesforceClient->delete("Account", accountId);
    if r is error {
        return r;
    }
    log:printInfo("[salesforce][compensation] Account deleted", accountId = accountId);
}

// -----------------------------------------------------------------------------
// Side-effect activities (audit trail)
// -----------------------------------------------------------------------------

# Posts a provisioning-audit message to Slack.
#
# + text - Slack message text
# + return - Slack message timestamp, or an error
@workflow:Activity
isolated function postProvisioningSlack(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: provisioningChannel,
        text
    });
    log:printInfo("[slack] provisioning event posted",
            channel = provisioningChannel, ts = resp.ts);
    return resp.ts;
}

# Emails billing operations with the provisioning outcome.
#
# + subject - Email subject
# + body - Email body (plain text)
# + return - Gmail message id, or an error
@workflow:Activity
isolated function emailBillingOps(string subject, string body) returns string|error {
    gmail:Message sent = check gmailClient->/users/me/messages/send.post({
        to: [billingOpsAddress],
        'from: gmailFromAddress,
        subject,
        bodyInText: body
    });
    log:printInfo("[gmail] billing-ops emailed", gmailMessageId = sent.id);
    return sent.id;
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Provisions a subscription end-to-end. If the contract step fails after
# retries, deletes the Contact and the Account in reverse order and
# completes with a `ROLLED_BACK` status.
#
# + ctx - Workflow context
# + req - Inbound subscription request
# + return - Subscription result, or an error if the rollback itself fails
@workflow:Workflow
function provisionSubscription(workflow:Context ctx, SubscriptionRequest req)
        returns SubscriptionResult|error {

    // Step 1 — create Account. If this fails the workflow fails outright,
    // because there is nothing committed to compensate yet.
    string accountId = check ctx->callActivity(createAccount,
            {"req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    // Step 2 — create Contact. If this fails, compensate step 1 (Account).
    string|error contactResult = ctx->callActivity(createContact,
            {"accountId": accountId, "req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if contactResult is error {
        string reason = contactResult.message();
        return rollbackAfterContactFailure(ctx, req, accountId, reason);
    }
    string contactId = contactResult;

    // Step 3 — create Contract. If this fails, compensate steps 2 and 1
    // (Contact then Account) in reverse order.
    string|error contractResult = ctx->callActivity(createContract,
            {"accountId": accountId, "req": req},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if contractResult is error {
        string reason = contractResult.message();
        return rollbackAfterContractFailure(ctx, req, accountId, contactId, reason);
    }
    string contractId = contractResult;

    // Happy path — audit the success.
    string|error slackRes = ctx->callActivity(postProvisioningSlack,
            {"text": string `:rocket: Subscription *${req.companyName}* provisioned. ` +
                    string `Account ${accountId}, Contact ${contactId}, Contract ${contractId}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if slackRes is error {
        log:printWarn("[saga] Slack audit notification failed",
                requestId = req.requestId, reason = slackRes.message());
    }
    string|error emailRes = ctx->callActivity(emailBillingOps,
            {
                "subject": string `Subscription provisioned: ${req.companyName}`,
                "body": string `Request ${req.requestId} provisioned successfully. ` +
                        string `Account ${accountId}, Contact ${contactId}, Contract ${contractId}.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if emailRes is error {
        log:printWarn("[saga] email billing-ops notification failed",
                requestId = req.requestId, reason = emailRes.message());
    }

    return {
        requestId: req.requestId,
        status: "PROVISIONED",
        accountId,
        contactId,
        contractId
    };
}

# Compensates a step-2 failure: deletes the Account created in step 1.
#
# + ctx - Workflow context
# + req - Inbound subscription request
# + accountId - Salesforce Account Id committed in step 1
# + reason - Captured failure reason
# + return - `ROLLED_BACK` subscription result, or an error if rollback fails
isolated function rollbackAfterContactFailure(workflow:Context ctx,
        SubscriptionRequest req, string accountId, string reason)
        returns SubscriptionResult|error {
    log:printWarn("contact creation failed; rolling back Account",
            requestId = req.requestId, accountId = accountId, reason = reason);
    () _ = check ctx->callActivity(deleteAccount,
            {"accountId": accountId},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    announceRollback(ctx, req, reason);
    return {
        requestId: req.requestId,
        status: "ROLLED_BACK",
        failureReason: reason
    };
}

# Compensates a step-3 failure: deletes the Contact and then the Account.
#
# + ctx - Workflow context
# + req - Inbound subscription request
# + accountId - Salesforce Account Id committed in step 1
# + contactId - Salesforce Contact Id committed in step 2
# + reason - Captured failure reason
# + return - `ROLLED_BACK` subscription result, or an error if rollback fails
isolated function rollbackAfterContractFailure(workflow:Context ctx,
        SubscriptionRequest req, string accountId, string contactId, string reason)
        returns SubscriptionResult|error {
    log:printWarn("contract creation failed; rolling back Contact + Account",
            requestId = req.requestId,
            accountId = accountId, contactId = contactId, reason = reason);
    error? contactDeleteError = ctx->callActivity(deleteContact,
            {"contactId": contactId},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    error? accountDeleteError = ctx->callActivity(deleteAccount,
            {"accountId": accountId},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if contactDeleteError !is () || accountDeleteError !is () {
        string contactMsg = contactDeleteError is error ? contactDeleteError.message() : "";
        string accountMsg = accountDeleteError is error ? accountDeleteError.message() : "";
        return error(string `Compensation partially failed — deleteContact: '${contactMsg}', deleteAccount: '${accountMsg}'`);
    }
    announceRollback(ctx, req, reason);
    return {
        requestId: req.requestId,
        status: "ROLLED_BACK",
        failureReason: reason
    };
}

# Posts the rollback to Slack and emails billing operations.
# Notification failures are logged but do not propagate so the saga
# always completes with a ROLLED_BACK status.
#
# + ctx - Workflow context
# + req - Inbound subscription request
# + reason - Captured failure reason to publish in the audit trail
isolated function announceRollback(workflow:Context ctx,
        SubscriptionRequest req, string reason) {
    string|error slackRes = ctx->callActivity(postProvisioningSlack,
            {"text": string `:warning: Subscription provisioning for *${req.companyName}* ` +
                    string `was rolled back. Reason: ${reason}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if slackRes is error {
        log:printWarn("[rollback] Slack notification failed",
                requestId = req.requestId, reason = slackRes.message());
    }
    string|error emailRes = ctx->callActivity(emailBillingOps,
            {
                "subject": string `Subscription rolled back: ${req.companyName}`,
                "body": string `Request ${req.requestId} could not be provisioned. ` +
                        string `Salesforce state has been compensated. Reason: ${reason}.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    if emailRes is error {
        log:printWarn("[rollback] email notification failed",
                requestId = req.requestId, reason = emailRes.message());
    }
}

// -----------------------------------------------------------------------------
// HTTP listener
// -----------------------------------------------------------------------------

// In-memory idempotency store: requestId → workflowId.
// Prevents duplicate workflows when the caller retries the same requestId.
// Use a durable store in production.
isolated map<string> subscriptionWorkflowIds = {};

# REST API for the subscription-provisioning saga.
service /subscriptions on new http:Listener(servicePort) {

    # Starts a subscription provisioning workflow.
    #
    # + req - Subscription request payload
    # + return - Workflow id wrapper, or an error
    resource function post .(@http:Payload SubscriptionRequest req)
            returns record {|string workflowId;|}|error {
        lock {
            string? existing = subscriptionWorkflowIds[req.requestId];
            if existing is string && existing != "in-progress" {
                log:printInfo("subscription provisioning workflow already started (idempotent hit)",
                        workflowId = existing, requestId = req.requestId);
                return {workflowId: existing};
            }
            // Reserve the slot atomically to prevent concurrent duplicate starts.
            subscriptionWorkflowIds[req.requestId] = "in-progress";
        }
        string|error workflowIdOrError = workflow:run(provisionSubscription, req);
        if workflowIdOrError is error {
            lock {
                _ = subscriptionWorkflowIds.remove(req.requestId);
            }
            return workflowIdOrError;
        }
        string workflowId = workflowIdOrError;
        lock {
            subscriptionWorkflowIds[req.requestId] = workflowId;
        }
        log:printInfo("subscription provisioning workflow started",
                requestId = req.requestId, workflowId = workflowId);
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
