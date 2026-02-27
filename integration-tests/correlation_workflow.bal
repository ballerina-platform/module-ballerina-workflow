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
// CORRELATION WORKFLOW - Multi-Signal Workflow with Multiple Signal Types
// ================================================================================
// 
// This workflow demonstrates a multi-signal workflow pattern where multiple
// signal types are used to coordinate workflow progression.
//
// Key concepts:
// 1. Workflow receives multiple signal types (payment, shipment)
// 2. Workflow ID is generated as UUID v7
// 3. Signals are sent using workflowId-based sendData
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Order workflow input.
# + customerId - The customer identifier
#
# + customerId - The customer identifier
# + orderId - The order identifier
# + product - The product name
# + quantity - The quantity ordered
# + price - The unit price
type CorrelatedOrderInput record {|
    string customerId;  // 
    string orderId;     // 
    string product;              // 
    int quantity;                // 
    decimal price;               // 
|};

# Payment signal .

#
# + customerId - The customer identifier
# + orderId - The order identifier
# + txnId - The transaction identifier
# + amount - The payment amount
# + paymentMethod - The payment method used
type CorrelatedPaymentSignal record {|
    string customerId;  // 
    string orderId;     // 
    string txnId;                // 
    decimal amount;              // 
    string paymentMethod;        // 
|};

# Shipment signal .
#
# + customerId - The customer identifier
# + orderId - The order identifier
# + trackingNumber - The shipment tracking number
# + carrier - The carrier name
type CorrelatedShipmentSignal record {|
    string customerId;
    string orderId;
    string trackingNumber;
    string carrier;
|};

# Result of the correlated order workflow.
#
# + customerId - The customer identifier
# + orderId - The order identifier
# + status - The order status
# + txnId - The transaction identifier (optional)
# + trackingNumber - The shipment tracking number (optional)
type CorrelatedOrderResult record {|
    string customerId;
    string orderId;
    string status;
    string? txnId;
    string? trackingNumber;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity that processes a correlated order.
#
# + customerId - The customer identifier
# + orderId - The order identifier
# + return - Processing result or error
@workflow:Activity
function processCorrelatedOrderActivity(string customerId, string orderId) returns string|error {
    // Simulate order processing
    return "Order " + orderId + " processed for customer " + customerId;
}

# Activity that validates payment.
#
# + orderId - The order identifier
# + amount - The payment amount
# + return - Validation result
@workflow:Activity
function validatePaymentActivity(string orderId, decimal amount) returns boolean|error {
    // Simulate payment validation
    return amount > 0d;
}

// ================================================================================
// CORRELATED WORKFLOW DEFINITION
// ================================================================================

# A workflow that uses fields as fields.
# 
# This demonstrates:
# 1. Readonly fields (customerId, orderId) are extracted as fields
# 2. Workflow ID is generated as: correlatedOrderWorkflow-customerId=C123-orderId=O456
# 3. Signals are sent using workflowId
# 4. No explicit "id" field needed when using fields
#
# + ctx - The workflow context for calling activities
# + input - The workflow input with fields
# + signals - Record containing futures for each expected signal
# + return - The order result or error
@workflow:Workflow
function correlatedOrderWorkflow(
    workflow:Context ctx, 
    CorrelatedOrderInput input,
    record {|
        future<CorrelatedPaymentSignal> payment;
        future<CorrelatedShipmentSignal> shipment;
    |} signals
) returns CorrelatedOrderResult|error {
    
    // Process the order using fields
    string _ = check ctx->callActivity(
        processCorrelatedOrderActivity, 
        {"customerId": input.customerId, "orderId": input.orderId}
    );
    
    // Wait for payment signal - will be matched by fields
    CorrelatedPaymentSignal paymentConfirmation = check wait signals.payment;
    
    // Validate payment
    boolean isValid = check ctx->callActivity(
        validatePaymentActivity,
        {"orderId": paymentConfirmation.orderId, "amount": paymentConfirmation.amount}
    );
    
    if !isValid {
        return {
            customerId: input.customerId,
            orderId: input.orderId,
            status: "PAYMENT_INVALID",
            txnId: paymentConfirmation.txnId,
            trackingNumber: ()
        };
    }
    
    // Wait for shipment signal
    CorrelatedShipmentSignal shipmentInfo = check wait signals.shipment;
    
    return {
        customerId: input.customerId,
        orderId: input.orderId,
        status: "COMPLETED",
        txnId: paymentConfirmation.txnId,
        trackingNumber: shipmentInfo.trackingNumber
    };
}

// ================================================================================
// SIMPLE CORRELATION WORKFLOW (SINGLE KEY)
// ================================================================================

# Simple input with single field.
#
# + requestId - The request identifier
# + message - The message content
type SimpleCorrelatedInput record {|
    string requestId;
    string message;
|};

# Simple response signal with matching field.
#
# + requestId - The request identifier
# + response - The response content
type SimpleCorrelatedResponse record {|
    string requestId;
    string response;
|};

# Simple correlated result.
#
# + requestId - The request identifier
# + originalMessage - The original input message
# + response - The signal response
type SimpleCorrelatedResult record {|
    string requestId;
    string originalMessage;
    string response;
|};

# A simple workflow demonstrating single field.
#
# + ctx - The workflow context
# + input - The input with single field
# + signals - Record with the response signal future
# + return - The result or error
@workflow:Workflow
function simpleCorrelatedWorkflow(
    workflow:Context ctx,
    SimpleCorrelatedInput input,
    record {| future<SimpleCorrelatedResponse> response; |} signals
) returns SimpleCorrelatedResult|error {
    
    // Wait for the response signal
    SimpleCorrelatedResponse resp = check wait signals.response;
    
    return {
        requestId: input.requestId,
        originalMessage: input.message,
        response: resp.response
    };
}
