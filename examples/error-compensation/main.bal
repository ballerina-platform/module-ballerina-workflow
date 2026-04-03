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

// Error Compensation (Saga Pattern) Example
//
// Demonstrates the Saga pattern for distributed transactions: each committed
// step has a corresponding compensating activity. When a later step fails
// after retries, the workflow runs compensating activities in reverse order
// to undo the committed work, then completes with a rolled-back status.
//
// Start the service:
//   bal run
//
// Then use the HTTP API:
//   POST /api/transfers       — start a fund transfer workflow
//   GET  /api/transfers/{id}  — get the workflow result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type TransferInput record {|
    string transferId;
    string sourceAccount;
    string destAccount;
    decimal amount;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Debits an amount from a bank account.
#
# + accountId - The account to debit
# + amount - The amount to subtract (positive = debit, negative = credit-back)
# + return - Confirmation message or error
@workflow:Activity
function debitAccount(string accountId, decimal amount) returns string|error {
    io:println(string `Debiting ${amount} from account ${accountId}`);
    return string `Debited ${amount} from ${accountId}`;
}

# Credits an amount to a bank account.
# Simulates an intermittent failure to trigger the compensation path.
#
# + accountId - The account to credit
# + amount - The amount to add
# + return - Confirmation message or error
@workflow:Activity
function creditAccount(string accountId, decimal amount) returns string|error {
    io:println(string `Crediting ${amount} to account ${accountId}`);
    // Simulate a failing destination bank — triggers the compensation path
    return error(string `Destination bank unavailable for account ${accountId}`);
}

# Compensating activity: reverses a previously committed debit.
# Called when a downstream step fails and the debit must be undone.
#
# + accountId - The account that was debited
# + amount - The amount to credit back
# + return - Compensation confirmation or error
@workflow:Activity
function reverseDebit(string accountId, decimal amount) returns string|error {
    io:println(string `Reversing debit of ${amount} on account ${accountId}`);
    return string `Reversed debit of ${amount} on ${accountId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Transfers funds between two accounts using the Saga pattern.
#
# Step 1 (debit) runs first. Step 2 (credit) is retried twice on transient
# failures. If step 2 exhausts all retries, the compensation activity
# (`reverseDebit`) is called to reverse the committed debit, and the workflow
# completes with a "ROLLED_BACK" status rather than failing.
#
# + ctx - Workflow context for calling activities
# + input - Transfer details
# + return - Final transfer status message or error
@workflow:Workflow
function transferFunds(workflow:Context ctx, TransferInput input) returns string|error {
    // Step 1: Debit source account — must succeed before we proceed
    string debitConfirm = check ctx->callActivity(debitAccount, {
        "accountId": input.sourceAccount,
        "amount": input.amount
    });
    // NOTE: io:println is used here for demo purposes only. Side effects inside
    // workflow logic will repeat if the process crashes and recovers. In production,
    // move logging to an activity or use the workflow-safe logging mechanism.
    io:println("Step 1 committed: " + debitConfirm);

    // Step 2: Credit destination — retry twice on transient failures
    string|error creditResult = ctx->callActivity(creditAccount, {
        "accountId": input.destAccount,
        "amount": input.amount
    }, retryOnError = true, maxRetries = 2, retryDelay = 1.0, retryBackoff = 2.0);

    if creditResult is error {
        io:println("Step 2 failed after retries: " + creditResult.message());
        io:println("Running compensation for step 1...");

        // Compensate: reverse the debit committed in step 1
        string compensation = check ctx->callActivity(reverseDebit, {
            "accountId": input.sourceAccount,
            "amount": input.amount
        });

        return string `Transfer ${input.transferId} ROLLED_BACK. ${compensation}`;
    }

    return string `Transfer ${input.transferId} COMPLETED. ${debitConfirm} -> ${creditResult}`;
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

service /api on new http:Listener(8092) {

    # Starts a new fund transfer workflow.
    resource function post transfers(@http:Payload TransferInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(transferFunds, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Retrieves the workflow result. Blocks until complete.
    resource function get transfers/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
