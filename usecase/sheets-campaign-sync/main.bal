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
// Use case: Sheets Campaign Sync (Marketing operations)
// =============================================================================
//
// Trigger      : Google Sheets row append and update events.
// Connectors   : ballerinax/trigger.google.sheets, ballerinax/slack,
//                ballerinax/salesforce, ballerinax/googleapis.gmail
// Pattern      : Pure spreadsheet-driven automation.
//                  1) A new row creates a Salesforce Campaign.
//                  2) Slack and Gmail publish synchronization results.
//                  3) A row update synchronizes Salesforce Campaign status.

import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/salesforce;
import ballerinax/slack;
import ballerinax/trigger.google.sheets;

configurable string spreadsheetId = "REPLACE_WITH_SPREADSHEET_ID";

configurable string slackBotToken = "";
configurable string marketingOpsChannel = "#marketing-ops";

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "marketing-ops@example.com";

listener sheets:Listener sheetListener = new ({spreadsheetId});

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

# Campaign data represented by a row in the Google Sheet.
#
# + requestId - Spreadsheet request id
# + campaignName - Campaign display name
# + ownerEmail - Campaign owner email address
# + region - Target region
# + budgetUsd - Campaign budget
# + launchDate - Planned launch date
# + status - Campaign status
# + salesforceCampaignId - Existing Salesforce Campaign Id for updates
public type CampaignRow record {|
    string requestId;
    string campaignName;
    string ownerEmail;
    string region;
    decimal budgetUsd;
    string launchDate;
    string status;
    string salesforceCampaignId = "";
|};

# Result of a campaign synchronization workflow.
#
# + requestId - Spreadsheet request id
# + action - `CREATED` or `UPDATED`
# + salesforceCampaignId - Salesforce Campaign Id
public type CampaignSyncResult record {|
    string requestId;
    string action;
    string salesforceCampaignId;
|};

@workflow:Activity
isolated function createSalesforceCampaign(CampaignRow row) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Campaign", {
        "Name": row.campaignName,
        "Status": row.status,
        "Type": "Marketing Operations",
        "BudgetedCost": row.budgetUsd,
        "StartDate": row.launchDate
    });
    log:printInfo("[salesforce] Campaign created", requestId = row.requestId, campaignId = resp.id);
    return resp.id;
}

@workflow:Activity
isolated function updateSalesforceCampaign(CampaignRow row) returns error? {
    error? result = salesforceClient->update("Campaign", row.salesforceCampaignId, {
        "Status": row.status,
        "BudgetedCost": row.budgetUsd,
        "StartDate": row.launchDate
    });
    if result is error {
        return result;
    }
    log:printInfo("[salesforce] Campaign updated",
            requestId = row.requestId, campaignId = row.salesforceCampaignId);
}

@workflow:Activity
isolated function postMarketingSlack(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: marketingOpsChannel,
        text
    });
    log:printInfo("[slack] campaign sync posted", ts = resp.ts);
    return resp.ts;
}

@workflow:Activity
isolated function emailOwner(string to, string subject, string body) returns string|error {
    gmail:Message sent = check gmailClient->/users/me/messages/send.post({
        to: [to],
        'from: gmailFromAddress,
        subject,
        bodyInText: body
    });
    log:printInfo("[gmail] campaign owner emailed", gmailMessageId = sent.id);
    return sent.id;
}

@workflow:Workflow
function createCampaignFromSheet(workflow:Context ctx, CampaignRow row)
        returns CampaignSyncResult|error {
    string campaignId = check ctx->callActivity(createSalesforceCampaign, {"row": row});
    string _ = check ctx->callActivity(postMarketingSlack,
            {"text": string `:mega: Created Salesforce Campaign *${row.campaignName}* ` +
                    string `from sheet request ${row.requestId}. Campaign id: ${campaignId}`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    string _ = check ctx->callActivity(emailOwner,
            {
                "to": row.ownerEmail,
                "subject": string `Campaign synced: ${row.campaignName}`,
                "body": string `Salesforce Campaign ${campaignId} was created from request ${row.requestId}.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    return {requestId: row.requestId, action: "CREATED", salesforceCampaignId: campaignId};
}

@workflow:Workflow
function updateCampaignFromSheet(workflow:Context ctx, CampaignRow row)
        returns CampaignSyncResult|error {
    () _ = check ctx->callActivity(updateSalesforceCampaign,
            {"row": row},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    string _ = check ctx->callActivity(postMarketingSlack,
            {"text": string `:arrows_counterclockwise: Synced campaign ${row.salesforceCampaignId} ` +
                    string `to status ${row.status} from sheet request ${row.requestId}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    return {
        requestId: row.requestId,
        action: "UPDATED",
        salesforceCampaignId: row.salesforceCampaignId
    };
}

service sheets:SheetRowService on sheetListener {

    remote function onAppendRow(sheets:GSheetEvent payload) returns error? {
        CampaignRow row = check campaignRowFromEvent(payload);
        string workflowId = check workflow:run(createCampaignFromSheet, row);
        log:printInfo("campaign create workflow started",
                requestId = row.requestId, workflowId = workflowId);
    }

    remote function onUpdateRow(sheets:GSheetEvent payload) returns error? {
        CampaignRow row = check campaignRowFromEvent(payload);
        if row.salesforceCampaignId.trim() == "" {
            log:printWarn("campaign update ignored; Salesforce Campaign Id is empty",
                    requestId = row.requestId);
            return;
        }
        string workflowId = check workflow:run(updateCampaignFromSheet, row);
        log:printInfo("campaign update workflow started",
                requestId = row.requestId, workflowId = workflowId);
    }
}

function campaignRowFromEvent(sheets:GSheetEvent payload) returns CampaignRow|error {
    return {
        requestId: check cell(payload, 0),
        campaignName: check cell(payload, 1),
        ownerEmail: check cell(payload, 2),
        region: check cell(payload, 3),
        budgetUsd: check decimal:fromString(check cell(payload, 4)),
        launchDate: check cell(payload, 5),
        status: check cell(payload, 6, defaultValue = "Planned"),
        salesforceCampaignId: check cell(payload, 7, defaultValue = "")
    };
}

function cell(sheets:GSheetEvent payload, int column, string? defaultValue = ()) returns string|error {
    (int|string|float)[][]? values = payload.newValues;
    // startingColumnPosition is 1-based; convert to 0-based and compute offset into newValues.
    int startCol = (payload.startingColumnPosition ?: 1) - 1;
    int offset = column - startCol;
    if values is () || values.length() == 0 || offset < 0 || values[0].length() <= offset {
        if defaultValue is string {
            return defaultValue;
        }
        return error(string `Google Sheets event does not contain column ${column}`);
    }
    return values[0][offset].toString();
}
