// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

// ================================================================================
// HUMAN TASK WORKFLOW — Types, Activities, and Workflows
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Input for the expense approval workflow.
#
# + id - Unique test identifier
# + orderId - Order identifier
# + amount - Expense amount
# + requester - Name of the person requesting reimbursement
type ExpenseRequest record {|
    string id;
    string orderId;
    decimal amount;
    string requester;
|};

# The human decision submitted via the task inbox.
#
# + approved - Whether the expense was approved
# + comment - Optional comment from the approver
type ApprovalDecision record {|
    boolean approved;
    string comment;
|};

# Result returned by the expense approval workflow.
#
# + orderId - Order identifier
# + status - Final status: COMPLETED | REJECTED | TIMED_OUT
# + message - Human-readable description of the outcome
type ExpenseResult record {|
    string orderId;
    string status;
    string message;
|};

// ================================================================================
// ACTIVITIES
// ================================================================================

@workflow:Activity
isolated function htValidateExpense(string orderId) returns string|error {
    return "Validated: " + orderId;
}

@workflow:Activity
isolated function htProcessReimbursement(string orderId) returns string|error {
    return "Reimbursement processed for " + orderId;
}

@workflow:Activity
isolated function htNotifyEscalation(string orderId, string taskName, string timedOutAfter) returns string|error {
    return "Escalated task '" + taskName + "' for order " + orderId + " after " + timedOutAfter;
}

// ================================================================================
// WORKFLOWS
// ================================================================================

# Standard expense approval: waits for a human decision, processes or rejects.
#
# + ctx - Workflow context
# + input - Expense request details
# + return - Approval result, or an error if a step fails
@workflow:Workflow
function expenseApprovalWorkflow(workflow:Context ctx, ExpenseRequest input) returns ExpenseResult|error {
    string _ = check ctx->callActivity(htValidateExpense, {"orderId": input.orderId});

    ApprovalDecision decision = check ctx->callHumanTask({
        taskName:  "approveExpense",
        title:     "Approve $" + input.amount.toString() + " for " + input.requester,
        userRoles: ["FINANCE_APPROVER"],
        payload:   {orderId: input.orderId, amount: input.amount.toString()}
    });

    if decision.approved {
        string msg = check ctx->callActivity(htProcessReimbursement, {"orderId": input.orderId});
        return {orderId: input.orderId, status: "COMPLETED", message: msg};
    }
    return {orderId: input.orderId, status: "REJECTED", message: decision.comment};
}

# Expense approval with a 5-second timeout and escalation on timeout.
#
# + ctx - Workflow context
# + input - Expense request details
# + return - Approval result, or an error if a step fails
@workflow:Workflow
function expenseApprovalWithTimeoutWorkflow(workflow:Context ctx, ExpenseRequest input) returns ExpenseResult|error {
    string _ = check ctx->callActivity(htValidateExpense, {"orderId": input.orderId});

    ApprovalDecision decision;
    do {
        decision = check ctx->callHumanTask({
            taskName:  "approveExpenseWithTimeout",
            title:     "Approve $" + input.amount.toString() + " for " + input.requester,
            userRoles: ["FINANCE_APPROVER"],
            payload:   {orderId: input.orderId},
            timeout:   {seconds: 5}
        });
    } on fail workflow:HumanTaskTimeoutError e {
        string _ = check ctx->callActivity(htNotifyEscalation, {
            "orderId":       input.orderId,
            "taskName":      e.detail().taskName,
            "timedOutAfter": e.detail().timedOutAfter
        });
        return {
            orderId: input.orderId,
            status:  "TIMED_OUT",
            message: string `Task ${e.detail().taskName} escalated after ${e.detail().timedOutAfter}`
        };
    }

    if decision.approved {
        string msg = check ctx->callActivity(htProcessReimbursement, {"orderId": input.orderId});
        return {orderId: input.orderId, status: "COMPLETED", message: msg};
    }
    return {orderId: input.orderId, status: "REJECTED", message: decision.comment};
}

# Expense approval using only the required taskName field (all other fields take defaults).
#
# + ctx - Workflow context
# + input - Expense request details
# + return - Approval result, or an error if a step fails
@workflow:Workflow
function expenseApprovalMinimalWorkflow(workflow:Context ctx, ExpenseRequest input) returns ExpenseResult|error {
    ApprovalDecision decision = check ctx->callHumanTask({
        taskName: "approveExpenseMinimal"
    });

    if decision.approved {
        string msg = check ctx->callActivity(htProcessReimbursement, {"orderId": input.orderId});
        return {orderId: input.orderId, status: "COMPLETED", message: msg};
    }
    return {orderId: input.orderId, status: "REJECTED", message: decision.comment};
}

# Expense approval requiring either a FINANCE_APPROVER or MANAGER role.
#
# + ctx - Workflow context
# + input - Expense request details
# + return - Approval result, or an error if a step fails
@workflow:Workflow
function expenseApprovalMultiRoleWorkflow(workflow:Context ctx, ExpenseRequest input) returns ExpenseResult|error {
    ApprovalDecision decision = check ctx->callHumanTask({
        taskName:  "approveExpenseMultiRole",
        title:     "High-value approval for $" + input.amount.toString(),
        userRoles: ["FINANCE_APPROVER", "MANAGER"],
        payload:   {orderId: input.orderId, amount: input.amount.toString(), requester: input.requester}
    });

    if decision.approved {
        string msg = check ctx->callActivity(htProcessReimbursement, {"orderId": input.orderId});
        return {orderId: input.orderId, status: "COMPLETED", message: msg};
    }
    return {orderId: input.orderId, status: "REJECTED", message: decision.comment};
}
