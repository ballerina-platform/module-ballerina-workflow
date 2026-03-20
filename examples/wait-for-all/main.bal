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

// Wait for All Example — Dual Authorization
//
// Demonstrates a workflow that waits for data from multiple sources before
// proceeding. A fund transfer requires authorization from both the Operations
// team and the Compliance team. The workflow proceeds only after both teams
// have sent their decisions.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/transfers                              — submit a transfer request
//   POST /api/transfers/{id}/operationsApproval      — operations team sends decision
//   POST /api/transfers/{id}/complianceApproval      — compliance team sends decision
//   GET  /api/transfers/{id}                         — get the final result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type TransferInput record {|
    string transferId;
    string fromAccount;
    string toAccount;
    decimal amount;
|};

type TransferResult record {|
    string transferId;
    string status;
    string message;
|};

# Authorization decision sent by an approval team.
#
# + approverId - ID of the approver
# + approved - true to authorize, false to reject
# + reason - Optional reason for the decision
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Validates a transfer request.
#
# + transferId - Transfer identifier
# + fromAccount - Source account
# + toAccount - Destination account
# + amount - Transfer amount
# + return - Error if validation fails
@workflow:Activity
function validateTransfer(string transferId, string fromAccount, string toAccount, decimal amount) returns string|error {
    io:println(string `[Activity] Validating transfer ${transferId}: ${fromAccount} → ${toAccount}, $${amount}`);
    if amount <= 0d {
        return error("Invalid amount: must be positive");
    }
    if fromAccount == toAccount {
        return error("Source and destination accounts must differ");
    }
    return "valid";
}

# Notifies both approval teams that a transfer needs authorization.
#
# + transferId - Transfer identifier
# + amount - Transfer amount
# + return - Confirmation or error
@workflow:Activity
function notifyApprovalTeams(string transferId, decimal amount) returns string|error {
    io:println(string `[AUTHORIZATION NEEDED] Transfer ${transferId}: $${amount}`);
    io:println("  → Sent to: Operations team and Compliance team");
    return "Both teams notified";
}

# Executes the approved fund transfer.
#
# + transferId - Transfer identifier
# + fromAccount - Source account
# + toAccount - Destination account
# + amount - Transfer amount
# + return - Transaction reference or error
@workflow:Activity
function executeTransfer(string transferId, string fromAccount, string toAccount, decimal amount) returns string|error {
    io:println(string `[Activity] Executing transfer ${transferId}: ${fromAccount} → ${toAccount}, $${amount}`);
    return string `TXN-${transferId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes a fund transfer requiring dual authorization.
#
# 1. Validates the transfer
# 2. Notifies both the Operations and Compliance teams
# 3. Waits for both teams using `workflow:waitNOutOfM` (wait for all)
# 4. If both approve — executes the transfer
# 5. If either rejects — returns REJECTED
#
# Data arrival order does not matter: if Compliance responds before Operations,
# the data is stored and delivered when waitNOutOfM resolves.
#
# + ctx - Workflow context for calling activities
# + input - Transfer request details
# + events - Record containing data futures for both teams
# + return - Final transfer result or error
@workflow:Workflow
function transferApproval(
    workflow:Context ctx,
    TransferInput input,
    record {|
        future<ApprovalDecision> operationsApproval;
        future<ApprovalDecision> complianceApproval;
    |} events
) returns TransferResult|error {

    // Step 1: Validate the transfer
    string _ = check ctx->callActivity(validateTransfer, {
        "transferId": input.transferId,
        "fromAccount": input.fromAccount,
        "toAccount": input.toAccount,
        "amount": input.amount
    });

    // Step 2: Notify both approval teams
    string _ = check ctx->callActivity(notifyApprovalTeams, {
        "transferId": input.transferId,
        "amount": input.amount
    });

    // Step 3: Wait for both teams using ctx->await (wait for all — default)
    io:println(string `[Workflow] Waiting for both authorizations for: ${input.transferId}`);
    [ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] = check ctx->await(
        [events.operationsApproval, events.complianceApproval]
    );
    io:println(string `[Workflow] Operations: approved=${opsDecision.approved}, Compliance: approved=${compDecision.approved}`);

    // Step 4: Both must approve
    if !opsDecision.approved {
        return {
            transferId: input.transferId,
            status: "REJECTED",
            message: string `Rejected by Operations (${opsDecision.approverId}): ${opsDecision.reason ?: "no reason given"}`
        };
    }
    if !compDecision.approved {
        return {
            transferId: input.transferId,
            status: "REJECTED",
            message: string `Rejected by Compliance (${compDecision.approverId}): ${compDecision.reason ?: "no reason given"}`
        };
    }

    // Step 5: Execute the transfer
    string txnRef = check ctx->callActivity(executeTransfer, {
        "transferId": input.transferId,
        "fromAccount": input.fromAccount,
        "toAccount": input.toAccount,
        "amount": input.amount
    });

    return {
        transferId: input.transferId,
        status: "COMPLETED",
        message: string `Transfer executed: ${txnRef}`
    };
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the dual-authorization workflow over REST.
#
# Endpoints:
#   POST /api/transfers                              — submit a transfer request
#   POST /api/transfers/{id}/operationsApproval      — operations sends decision
#   POST /api/transfers/{id}/complianceApproval      — compliance sends decision
#   GET  /api/transfers/{id}                         — get the final result
service /api on new http:Listener(8090) {

    # Submits a new fund transfer request.
    resource function post transfers(@http:Payload TransferInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(transferApproval, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Sends the Operations team's authorization decision.
    resource function post transfers/[string workflowId]/operationsApproval(
            @http:Payload ApprovalDecision decision) returns record {|string status; string message;|}|error {
        check workflow:sendData(transferApproval, workflowId, "operationsApproval", decision);
        io:println(string `Operations approval sent to workflow ${workflowId}`);
        return {status: "accepted", message: "Operations authorization delivered to workflow"};
    }

    # Sends the Compliance team's authorization decision.
    resource function post transfers/[string workflowId]/complianceApproval(
            @http:Payload ApprovalDecision decision) returns record {|string status; string message;|}|error {
        check workflow:sendData(transferApproval, workflowId, "complianceApproval", decision);
        io:println(string `Compliance approval sent to workflow ${workflowId}`);
        return {status: "accepted", message: "Compliance authorization delivered to workflow"};
    }

    # Retrieves the final result of a transfer workflow.
    resource function get transfers/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
