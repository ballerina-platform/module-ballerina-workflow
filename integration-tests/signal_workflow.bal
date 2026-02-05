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
// SIGNAL HANDLING WORKFLOW - Future-Based Signal Handling
// ================================================================================
// 
// This workflow demonstrates the future-based signal handling pattern.
// Signals are injected as a record of futures, which can be awaited using
// Ballerina's native `wait` action.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Input for the approval workflow.
#
# + id - The workflow identifier (readonly for correlation)
# + orderId - The order identifier
# + amount - The order amount
type ApprovalInput record {|
    readonly string id;
    string orderId;
    decimal amount;
|};

# Signal data for approval decision.
#
# + id - The workflow identifier (readonly for correlation)
# + approverId - The approver's identifier
# + approved - Whether the approval was granted
# + reason - The reason for approval/rejection (optional)
type ApprovalSignal record {|
    readonly string id;
    string approverId;
    boolean approved;
    string? reason;
|};

# Signal data for payment confirmation.
#
# + id - The workflow identifier (readonly for correlation)
# + txnId - The transaction identifier
# + amount - The payment amount
type PaymentSignal record {|
    readonly string id;
    string txnId;
    decimal amount;
|};

# Result of the approval workflow.
#
# + orderId - The order identifier
# + status - The workflow status
# + approvedBy - The approver's identifier (optional)
# + txnId - The transaction identifier (optional)
type ApprovalResult record {|
    string orderId;
    string status;
    string? approvedBy;
    string? txnId;
|};

// ================================================================================
// ACTIVITY DEFINITION
// ================================================================================

# Activity that sends a notification.
#
# + orderId - The order identifier
# + message - The message to send
# + return - Success status
@workflow:Activity
function sendNotificationActivity(string orderId, string message) returns boolean|error {
    // In a real implementation, this would send an actual notification
    return true;
}

// ================================================================================
// WORKFLOW DEFINITION WITH SIGNAL HANDLING
// ================================================================================

# A workflow that waits for approval and payment signals.
# 
# This demonstrates the future-based signal handling pattern where:
# 1. Signals are passed as a record of futures
# 2. The workflow uses Ballerina's `wait` action to await signals
# 3. Multiple signals can be handled in sequence or in parallel
#
# + ctx - The workflow context for calling activities
# + input - The workflow input data
# + signals - Record containing futures for each expected signal
# + return - The approval result or error
@workflow:Process
function approvalWorkflow(
    workflow:Context ctx, 
    ApprovalInput input,
    record {|
        future<ApprovalSignal> approval;
        future<PaymentSignal> payment;
    |} signals
) returns ApprovalResult|error {
    
    // Send notification that approval is needed
    boolean _ = check ctx->callActivity(sendNotificationActivity, 
            {"orderId": input.orderId, "message": "Approval required for order"});
    
    // Wait for approval signal using Ballerina's native wait
    ApprovalSignal approvalDecision = check wait signals.approval;
    
    if !approvalDecision.approved {
        // Approval rejected - send rejection notification and return
        boolean _ = check ctx->callActivity(sendNotificationActivity,
                {"orderId": input.orderId, "message": "Order rejected"});
        return {
            orderId: input.orderId,
            status: "REJECTED",
            approvedBy: approvalDecision.approverId,
            txnId: ()
        };
    }
    
    // Approval granted - wait for payment signal
    boolean _ = check ctx->callActivity(sendNotificationActivity,
            {"orderId": input.orderId, "message": "Awaiting payment"});
    
    PaymentSignal paymentConfirmation = check wait signals.payment;
    
    // Validate payment amount matches order amount
    if paymentConfirmation.amount < input.amount {
        return {
            orderId: input.orderId,
            status: "PAYMENT_INSUFFICIENT",
            approvedBy: approvalDecision.approverId,
            txnId: paymentConfirmation.txnId
        };
    }
    
    // Order completed successfully
    boolean _ = check ctx->callActivity(sendNotificationActivity,
            {"orderId": input.orderId, "message": "Order completed"});
    
    return {
        orderId: input.orderId,
        status: "COMPLETED",
        approvedBy: approvalDecision.approverId,
        txnId: paymentConfirmation.txnId
    };
}

// ================================================================================
// SIMPLE SINGLE-SIGNAL WORKFLOW
// ================================================================================

# Simple workflow input for single signal demo.
#
# + id - The workflow identifier (readonly for correlation)
# + message - The input message
type SimpleSignalInput record {|
    readonly string id;
    string message;
|};

# Simple signal data.
#
# + id - The workflow identifier (readonly for correlation)
# + response - The signal response
type SimpleSignalData record {|
    readonly string id;
    string response;
|};

# Simple result.
#
# + originalMessage - The original message from input
# + response - The response from signal
type SimpleSignalResult record {|
    string originalMessage;
    string response;
|};

# A simple workflow that waits for a single signal.
#
# + ctx - The workflow context
# + input - The workflow input
# + signals - Record containing the signal future
# + return - The result combining input and signal data
@workflow:Process
function simpleSignalWorkflow(
    workflow:Context ctx,
    SimpleSignalInput input,
    record {|
        future<SimpleSignalData> response;
    |} signals
) returns SimpleSignalResult|error {
    
    // Wait for the response signal
    SimpleSignalData signalData = check wait signals.response;
    
    return {
        originalMessage: input.message,
        response: signalData.response
    };
}
