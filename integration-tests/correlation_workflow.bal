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
// CORRELATION WORKFLOW - Readonly Field Based Correlation
// ================================================================================
// 
// This workflow demonstrates the correlation key pattern where readonly fields
// in record types are used for workflow-signal correlation.
//
// Key concepts:
// 1. Readonly fields in process input become correlation keys
// 2. Signal types must have the same readonly fields (name AND type)
// 3. Composite workflow ID is generated: processName-key1=value1-key2=value2
// 4. Correlation keys become Temporal Search Attributes for visibility
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES WITH CORRELATION KEYS (READONLY FIELDS)
// ================================================================================

# Order workflow input with correlation keys.
# The readonly fields (customerId, orderId) become correlation keys.
#
# + customerId - The customer identifier (correlation key)
# + orderId - The order identifier (correlation key)
# + product - The product name
# + quantity - The quantity ordered
# + price - The unit price
type CorrelatedOrderInput record {|
    readonly string customerId;  // Correlation key
    readonly string orderId;     // Correlation key
    string product;              // Regular field
    int quantity;                // Regular field
    decimal price;               // Regular field
|};

# Payment signal with matching correlation keys.
# Must have same readonly fields (name AND type) as CorrelatedOrderInput.
#
# + customerId - The customer identifier (must match CorrelatedOrderInput)
# + orderId - The order identifier (must match CorrelatedOrderInput)
# + txnId - The transaction identifier
# + amount - The payment amount
# + paymentMethod - The payment method used
type CorrelatedPaymentSignal record {|
    readonly string customerId;  // Must match CorrelatedOrderInput
    readonly string orderId;     // Must match CorrelatedOrderInput
    string txnId;                // Signal-specific data
    decimal amount;              // Signal-specific data
    string paymentMethod;        // Signal-specific data
|};

# Shipment signal with matching correlation keys.
#
# + customerId - The customer identifier (must match CorrelatedOrderInput)
# + orderId - The order identifier (must match CorrelatedOrderInput)
# + trackingNumber - The shipment tracking number
# + carrier - The carrier name
type CorrelatedShipmentSignal record {|
    readonly string customerId;
    readonly string orderId;
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

# A workflow that uses readonly fields as correlation keys.
# 
# This demonstrates:
# 1. Readonly fields (customerId, orderId) are extracted as correlation keys
# 2. Workflow ID is generated as: correlatedOrderWorkflow-customerId=C123-orderId=O456
# 3. Signals must have matching correlation keys to be routed correctly
# 4. No explicit "id" field needed when using readonly correlation keys
#
# + ctx - The workflow context for calling activities
# + input - The workflow input with correlation keys
# + signals - Record containing futures for each expected signal
# + return - The order result or error
@workflow:Process
function correlatedOrderWorkflow(
    workflow:Context ctx, 
    CorrelatedOrderInput input,
    record {|
        future<CorrelatedPaymentSignal> payment;
        future<CorrelatedShipmentSignal> shipment;
    |} signals
) returns CorrelatedOrderResult|error {
    
    // Process the order using correlation keys
    string _ = check ctx->callActivity(
        processCorrelatedOrderActivity, 
        {"customerId": input.customerId, "orderId": input.orderId}
    );
    
    // Wait for payment signal - will be matched by correlation keys
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

# Simple input with single correlation key.
#
# + requestId - The request identifier (correlation key)
# + message - The message content
type SimpleCorrelatedInput record {|
    readonly string requestId;
    string message;
|};

# Simple response signal with matching correlation key.
#
# + requestId - The request identifier (must match SimpleCorrelatedInput)
# + response - The response content
type SimpleCorrelatedResponse record {|
    readonly string requestId;
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

# A simple workflow demonstrating single correlation key.
#
# + ctx - The workflow context
# + input - The input with single readonly correlation key
# + signals - Record with the response signal future
# + return - The result or error
@workflow:Process
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
