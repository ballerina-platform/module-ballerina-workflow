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
// Use case: Finance Invoice Processing (Finance automation, file-based)
// =============================================================================
//
// Trigger      : `ftp:Listener` polling an SFTP `/incoming/invoices`
//                directory. Each new CSV invoice file starts one workflow.
// Connectors   : ballerina/ftp, ballerina/email (SMTP), ballerinax/salesforce
// Human step   : Approval of the AP invoice. The workflow:
//                  1) Emails the finance team via SMTP with a summary.
//                  2) Creates a Salesforce `Case` (record type `AP_Invoice`)
//                     assigned to the AP queue.
//                  3) Pauses on a typed signal channel until the Salesforce
//                     webhook calls back with the Case's resolution.
// Final step   : Marks the Salesforce Case as posted/closed and emails the
//                finance team with the outcome.

import ballerina/email;
import ballerina/ftp;
import ballerina/http;
import ballerina/log;
import ballerina/workflow;
import ballerinax/salesforce;

// -----------------------------------------------------------------------------
// Configurables
// -----------------------------------------------------------------------------

configurable string sftpHost = "sftp.example.com";
configurable int sftpPort = 22;
configurable string sftpUser = "";
configurable string sftpPassword = "";
configurable string sftpPath = "/incoming/invoices";
configurable decimal sftpPollingIntervalSeconds = 30.0;

configurable string smtpHost = "smtp.example.com";
configurable string smtpUser = "";
configurable string smtpPassword = "";
configurable string financeFromAddress = "ap@example.com";
configurable string financeNotifyAddress = "finance-team@example.com";

configurable string salesforceBaseUrl = "https://your-domain.my.salesforce.com";
configurable string salesforceAccessToken = "";

configurable int servicePort = 8103;
configurable decimal autoApproveThresholdUsd = 500.00;

// -----------------------------------------------------------------------------
// Connector clients
// -----------------------------------------------------------------------------

final email:SmtpClient smtpClient = check new (
        smtpHost,
        smtpUser == "" ? () : smtpUser,
        smtpPassword == "" ? () : smtpPassword);

final salesforce:Client salesforceClient = check new ({
    baseUrl: salesforceBaseUrl,
    auth: {token: salesforceAccessToken}
});

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

# Metadata extracted from the invoice CSV file picked up by the SFTP listener.
#
# + fileName - File name on the SFTP server
# + invoiceNumber - Vendor invoice number (column `invoice_number`)
# + vendorName - Vendor display name (column `vendor`)
# + amountUsd - Total amount (column `amount_usd`)
# + dueDate - Due date (column `due_date`, `YYYY-MM-DD`)
public type Invoice record {|
    string fileName;
    string invoiceNumber;
    string vendorName;
    decimal amountUsd;
    string dueDate;
|};

# Approval payload from the Salesforce webhook callback.
#
# + caseId - Salesforce Case Id
# + caseStatus - Final case status (`Closed - Approved`, `Closed - Rejected`, …)
# + approverName - Approver display name
public type InvoiceApproval record {|
    string caseId;
    string caseStatus;
    string approverName;
|};

# Workflow result.
#
# + invoiceNumber - Vendor invoice number
# + status - `POSTED`, `REJECTED`, or `AUTO_APPROVED`
# + caseId - Salesforce Case Id (when applicable)
# + amountUsd - Invoice amount
public type InvoiceResult record {|
    string invoiceNumber;
    string status;
    string caseId?;
    decimal amountUsd;
|};

// -----------------------------------------------------------------------------
// Activities
// -----------------------------------------------------------------------------

# Sends a notification email to the finance team via SMTP.
#
# + subject - Email subject
# + body - Email body (plain text)
# + return - `()` on success, or an error
@workflow:Activity
isolated function emailFinanceTeam(string subject, string body) returns error? {
    email:Message msg = {
        to: financeNotifyAddress,
        subject: subject,
        'from: financeFromAddress,
        body: body
    };
    check smtpClient->sendMessage(msg);
    log:printInfo("[smtp] finance team emailed", subject = subject);
}

# Creates a Salesforce `Case` for AP invoice approval.
#
# + workflowId - Workflow id (embedded in description for the webhook callback)
# + inv - Invoice metadata
# + return - Salesforce Case Id, or an error
@workflow:Activity
isolated function createApprovalCase(string workflowId, Invoice inv) returns string|error {
    salesforce:CreationResponse resp = check salesforceClient->create("Case", {
        "Subject": string `AP invoice ${inv.invoiceNumber} from ${inv.vendorName} ($${inv.amountUsd})`,
        "Description": string `Workflow ${workflowId} is awaiting approval. ` +
                string `Due ${inv.dueDate}. File: ${inv.fileName}.`,
        "Origin": "Workflow",
        "Priority": "High",
        "Status": "New"
    });
    log:printInfo("[salesforce] Case created", caseId = resp.id, workflowId = workflowId);
    return resp.id;
}

# Updates a Salesforce Case to a final status (`Closed`).
#
# + caseId - Salesforce Case Id
# + finalStatus - Final status string
# + return - `()` on success, or an error
@workflow:Activity
isolated function closeSalesforceCase(string caseId, string finalStatus) returns error? {
    error? r = salesforceClient->update("Case", caseId, {"Status": finalStatus});
    if r is error {
        return r;
    }
    log:printInfo("[salesforce] Case closed", caseId = caseId, status = finalStatus);
}

// -----------------------------------------------------------------------------
// Workflow
// -----------------------------------------------------------------------------

# Processes one AP invoice end-to-end.
#
# + ctx - Workflow context
# + inv - Invoice metadata extracted from the SFTP CSV
# + events - Approval signal channel populated by the Salesforce webhook
# + return - Workflow result
@workflow:Workflow
function processInvoice(
        workflow:Context ctx,
        Invoice inv,
        record {| future<InvoiceApproval> approval; |} events
) returns InvoiceResult|error {

    if inv.amountUsd <= autoApproveThresholdUsd {
        () _ = check ctx->callActivity(emailFinanceTeam,
                {
                    "subject": string `Invoice ${inv.invoiceNumber} auto-approved`,
                    "body": string `Auto-approved invoice from ${inv.vendorName} for $${inv.amountUsd}.`
                },
                retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);
        return {invoiceNumber: inv.invoiceNumber, status: "AUTO_APPROVED", amountUsd: inv.amountUsd};
    }

    string wfId = check ctx.getWorkflowId();
    string caseId = check ctx->callActivity(createApprovalCase,
            {"workflowId": wfId, "inv": inv},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    () _ = check ctx->callActivity(emailFinanceTeam,
            {
                "subject": string `[Action required] Invoice ${inv.invoiceNumber} ($${inv.amountUsd})`,
                "body": string `An AP invoice from ${inv.vendorName} has been filed as ` +
                        string `Salesforce Case ${caseId}. Please review and approve.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    InvoiceApproval decision = check wait events.approval;
    boolean approved = decision.caseStatus.toLowerAscii().includes("approved");
    string finalStatus = approved ? "Closed - Approved" : "Closed - Rejected";

    () _ = check ctx->callActivity(closeSalesforceCase,
            {"caseId": caseId, "finalStatus": finalStatus},
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    () _ = check ctx->callActivity(emailFinanceTeam,
            {
                "subject": string `Invoice ${inv.invoiceNumber} ${approved ? "approved" : "rejected"}`,
                "body": string `Invoice ${inv.invoiceNumber} from ${inv.vendorName} ` +
                        string `was ${approved ? "approved" : "rejected"} by ${decision.approverName}.`
            },
            retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    return {
        invoiceNumber: inv.invoiceNumber,
        status: approved ? "POSTED" : "REJECTED",
        caseId,
        amountUsd: inv.amountUsd
    };
}

// -----------------------------------------------------------------------------
// SFTP listener — picks up invoice CSV files
// -----------------------------------------------------------------------------

listener ftp:Listener invoiceListener = check new ({
    protocol: ftp:SFTP,
    host: sftpHost,
    port: sftpPort,
    auth: {credentials: {username: sftpUser, password: sftpPassword}},
    path: sftpPath,
    fileNamePattern: "(.*).csv",
    pollingInterval: sftpPollingIntervalSeconds
});

# CSV row schema for invoice files dropped on the SFTP server.
#
# + invoice_number - Vendor invoice number
# + vendor - Vendor display name
# + amount_usd - Total amount in USD
# + due_date - Due date (`YYYY-MM-DD`)
type InvoiceRow record {|
    string invoice_number;
    string vendor;
    decimal amount_usd;
    string due_date;
|};

# SFTP service that fans out one workflow per row of each new CSV file.
service on invoiceListener {

    # Invoked by the FTP listener for each new `.csv` file in the watched
    # directory. The CSV is deserialized to `InvoiceRow[]` and one workflow
    # is started per row.
    #
    # + content - Parsed CSV rows
    # + fileInfo - File metadata
    # + return - `()` on success, or an error
    remote function onFileCsv(InvoiceRow[] content, ftp:FileInfo fileInfo) returns error? {
        log:printInfo("[sftp] invoice file detected",
                file = fileInfo.name, rows = content.length());
        foreach InvoiceRow row in content {
            Invoice inv = {
                fileName: fileInfo.name,
                invoiceNumber: row.invoice_number,
                vendorName: row.vendor,
                amountUsd: row.amount_usd,
                dueDate: row.due_date
            };
            string workflowId = check workflow:run(processInvoice, inv);
            log:printInfo("invoice workflow started",
                    workflowId = workflowId, invoice = inv.invoiceNumber);
        }
    }
}

// -----------------------------------------------------------------------------
// HTTP listener — Salesforce webhook callback + result lookup
// -----------------------------------------------------------------------------

# REST API for the invoice-processing workflow.
service /finance on new http:Listener(servicePort) {

    # Salesforce webhook callback: configure a Salesforce Process Builder /
    # Flow on `Case` to POST here when the Case is closed (approved or
    # rejected). The workflow id is read from the Case description by the
    # Flow and passed in the URL.
    #
    # + workflowId - Target workflow id
    # + decision - Approval decision payload
    # + return - `accepted` envelope, or an error
    resource function post invoices/[string workflowId]/approval(@http:Payload InvoiceApproval decision)
            returns record {| string status; |}|error {
        check workflow:sendData(processInvoice, workflowId, "approval", decision);
        return {status: "accepted"};
    }

    # Returns the workflow's final result.
    #
    # + workflowId - Target workflow id
    # + return - Final execution info, or an error
    resource function get invoices/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
