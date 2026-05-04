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
// Use case: MSSQL Inventory Synchronization (Supply-chain automation)
// =============================================================================
//
// Trigger      : SQL Server CDC events from the inventory table.
// Connectors   : ballerinax/mssql, ballerinax/mssql.cdc.driver,
//                ballerinax/slack, ballerinax/salesforce,
//                ballerinax/googleapis.gmail
// Pattern      : Pure database-change-driven workflow orchestration.
//                  1) CDC emits create/update/delete events.
//                  2) Workflow writes a Salesforce inventory snapshot.
//                  3) Slack and Gmail publish downstream notifications.

import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/googleapis.gmail;
import ballerinax/mssql;
import ballerinax/mssql.cdc.driver as _;
import ballerinax/salesforce;
import ballerinax/slack;

configurable string mssqlHost = "localhost";
configurable int mssqlPort = 1433;
configurable string mssqlUser = "";
configurable string mssqlPassword = "";
configurable string mssqlDatabase = "warehouse";
configurable string[] mssqlIncludedTables = ["dbo.inventory"];

configurable string slackBotToken = "";
configurable string operationsChannel = "#warehouse-ops";

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable string gmailRefreshToken = "";
configurable string gmailClientId = "";
configurable string gmailClientSecret = "";
configurable string gmailFromAddress = "warehouse@example.com";
configurable string procurementEmail = "procurement@example.com";

configurable int reorderThreshold = 25;
configurable int servicePort = 8106;

listener mssql:CdcListener inventoryListener = new (database = {
    hostname: mssqlHost,
    port: mssqlPort,
    username: mssqlUser,
    password: mssqlPassword,
    databaseNames: [mssqlDatabase],
    includedTables: mssqlIncludedTables
});

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

# Inventory row after a CDC create or update event.
#
# + sku - Stock keeping unit
# + productName - Product display name
# + quantityOnHand - Current inventory quantity
# + reorderPoint - Item-specific reorder point
# + supplierEmail - Preferred supplier email
public type InventoryChange record {|
    string sku;
    string productName;
    int quantityOnHand;
    int reorderPoint;
    string supplierEmail;
|};

# Deleted inventory row.
#
# + sku - Stock keeping unit
# + productName - Product display name if present in the CDC event
public type InventoryDelete record {|
    string sku;
    string productName = "";
|};

# Result of an inventory synchronization workflow.
#
# + sku - Stock keeping unit
# + action - CDC action processed
# + lowStock - Whether reorder notification was sent
# + salesforceSnapshotId - Salesforce inventory snapshot id when created
public type InventorySyncResult record {|
    string sku;
    string action;
    boolean lowStock;
    string? salesforceSnapshotId = ();
|};

# Input for create/update inventory synchronization workflows.
#
# + action - CDC action being processed
# + item - Inventory row after the CDC event
public type InventorySyncInput record {|
    string action;
    InventoryChange item;
|};

@workflow:Activity
isolated function createInventorySnapshot(string action, InventoryChange item) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Inventory_Snapshot__c", {
        "Sku__c": item.sku,
        "Product_Name__c": item.productName,
        "Quantity_On_Hand__c": item.quantityOnHand,
        "Reorder_Point__c": item.reorderPoint,
        "Change_Action__c": action
    });
    log:printInfo("[salesforce] inventory snapshot created", sku = item.sku, snapshotId = resp.id);
    return resp.id;
}

@workflow:Activity
isolated function postOperationsSlack(string text) returns string|error {
    slack:ChatPostMessageResponse resp = check slackClient->/chat\.postMessage.post({
        channel: operationsChannel,
        text
    });
    log:printInfo("[slack] inventory update posted", channel = operationsChannel, ts = resp.ts);
    return resp.ts;
}

@workflow:Activity
isolated function emailReorder(InventoryChange item) returns string|error {
    gmail:Message sent = check gmailClient->/users/["me"]/messages.post({
        to: [procurementEmail, item.supplierEmail],
        'from: gmailFromAddress,
        subject: string `Reorder required: ${item.sku}`,
        bodyInText: string `Inventory for ${item.productName} (${item.sku}) is below threshold.\n` +
                string `Quantity on hand: ${item.quantityOnHand}\n` +
                string `Reorder point: ${item.reorderPoint}`
    });
    log:printInfo("[gmail] reorder email sent", sku = item.sku, gmailMessageId = sent.id);
    return sent.id;
}

@workflow:Workflow
function syncInventoryChange(workflow:Context ctx, InventorySyncInput input)
        returns InventorySyncResult|error {
    string action = input.action;
    InventoryChange item = input.item;
    string snapshotId = check ctx->callActivity(createInventorySnapshot,
            {"action": action, "item": item},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    boolean lowStock = item.quantityOnHand <= item.reorderPoint ||
            item.quantityOnHand <= reorderThreshold;
    string _ = check ctx->callActivity(postOperationsSlack,
            {"text": string `:package: Inventory ${action} for *${item.sku}* ` +
                    string `${item.productName}: ${item.quantityOnHand}/${item.reorderPoint}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    if lowStock {
        string _ = check ctx->callActivity(emailReorder,
                {"item": item},
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    }

    return {
        sku: item.sku,
        action,
        lowStock,
        salesforceSnapshotId: snapshotId
    };
}

@workflow:Workflow
function syncInventoryDelete(workflow:Context ctx, InventoryDelete deleted)
        returns InventorySyncResult|error {
    string _ = check ctx->callActivity(postOperationsSlack,
            {"text": string `:warning: Inventory tracking deleted for *${deleted.sku}* ${deleted.productName}.`},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
    return {
        sku: deleted.sku,
        action: "DELETE",
        lowStock: false
    };
}

service on inventoryListener {

    remote function onCreate(record {} after) returns error? {
        InventoryChange item = check inventoryChangeFromRecord(after);
        string workflowId = check workflow:run(syncInventoryChange, {action: "CREATE", item});
        log:printInfo("inventory create workflow started", sku = item.sku, workflowId = workflowId);
    }

    remote function onUpdate(record {} before, record {} after) returns error? {
        InventoryChange item = check inventoryChangeFromRecord(after);
        string workflowId = check workflow:run(syncInventoryChange, {action: "UPDATE", item});
        log:printInfo("inventory update workflow started", sku = item.sku, workflowId = workflowId);
    }

    remote function onDelete(record {} before) returns error? {
        InventoryDelete deleted = {
            sku: check stringCell(before, "sku"),
            productName: check stringCell(before, "product_name", defaultValue = "")
        };
        string workflowId = check workflow:run(syncInventoryDelete, deleted);
        log:printInfo("inventory delete workflow started", sku = deleted.sku, workflowId = workflowId);
    }
}

function inventoryChangeFromRecord(record {} row) returns InventoryChange|error {
    return {
        sku: check stringCell(row, "sku"),
        productName: check stringCell(row, "product_name"),
        quantityOnHand: check intCell(row, "quantity_on_hand"),
        reorderPoint: check intCell(row, "reorder_point"),
        supplierEmail: check stringCell(row, "preferred_supplier_email")
    };
}

function stringCell(record {} row, string column, string? defaultValue = ()) returns string|error {
    anydata? value = row[column];
    if value is () {
        if defaultValue is string {
            return defaultValue;
        }
        return error(string `CDC row does not contain column ${column}`);
    }
    return value.toString();
}

function intCell(record {} row, string column) returns int|error {
    anydata? value = row[column];
    if value is int {
        return value;
    }
    if value is string {
        return int:fromString(value);
    }
    return error(string `CDC row column ${column} is not an int`);
}

service /inventory on new http:Listener(servicePort) {

    resource function get syncs/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
