// Copyright (c) 2025 WSO2 LLC. (http://www.wso2.com).
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

import ballerina/http;
import ballerina/workflow;
import ballerina/io;

# HTTP Service for Order Processing with Payment
# 
# This service provides REST endpoints for:
# - Placing new orders (which start workflows)
# - Sending payment confirmations (which send signals to waiting workflows)
# - Health check monitoring
#
# The service demonstrates how to integrate Ballerina workflows with HTTP APIs,
# particularly the pattern of starting a workflow and later sending signals to it.
service /orders on new http:Listener(9094) {

    # Place a new order and start the order processing workflow.
    # 
    # This endpoint initiates an asynchronous workflow that will:
    # 1. Check inventory availability
    # 2. Wait for payment confirmation signal
    # 3. Complete the order once payment is received
    #
    # + request - Order request containing orderId and item name
    # + return - JSON response with workflow ID and status, or error
    #
    # Example:
    # ```
    # POST /orders
    # Body: {"orderId": "ORD-001", "item": "laptop"}
    # ```
    resource function post .(OrderRequest request) returns json|error {
        // Start workflow using @workflow:Process function
        string workflowId = check workflow:createInstance(processOrderWithPayment, request);

        io:println(string `Started order workflow: ${workflowId}`);

        return {
            "status": "success",
            "workflowId": workflowId,
            "orderId": request.orderId,
            "message": "Order placed. Awaiting payment."
        };
    }

    # Send payment confirmation signal to a waiting workflow.
    # 
    # This endpoint sends a signal to the workflow processing the specified order.
    # The workflow must be waiting for the payment signal, otherwise the signal
    # will be queued or fail depending on the workflow state.
    #
    # + orderId - The order ID to send payment confirmation for
    # + paymentData - Payment information containing the amount
    # + return - JSON response indicating success or failure, or error
    #
    # Example:
    # ```
    # POST /orders/ORD-001/payment
    # Body: {"amount": 1999.99}
    # ```
    resource function post [string orderId]/payment(record {decimal amount;} paymentData) returns json|error {
        // Send payment signal
        // The field name 'paymentReceived' in the events record determines the signal name
        PaymentConfirmation payment = {orderId: orderId, amount: paymentData.amount};
        boolean sent = check workflow:sendEvent(processOrderWithPayment, payment, "paymentReceived");

        if sent {
            io:println(string `Payment signal sent for order: ${orderId}`);
            return {
                "status": "success",
                "message": "Payment received"
            };
        }

        return {
            "status": "error",
            "message": "Failed to send payment signal"
        };
    }

    # Health check endpoint to verify service availability.
    #
    # + return - Status message indicating service is running
    resource function get health() returns string {
        return "Order Payment Service is running";
    }
}

# Main entry point for the Order with Payment sample application.
# 
# This function starts the HTTP service and displays usage instructions.
# The service will listen on port 9094 for incoming order and payment requests.
#
# + return - Error if startup fails
public function main() returns error? {
    io:println("Starting Order with Payment Sample...");
    io:println("Worker started. HTTP Service listening on http://localhost:9094/orders");
    io:println("Test with:");
    io:println("  1. Place order: curl -X POST http://localhost:9094/orders -H 'Content-Type: application/json' -d '{\"orderId\":\"ORD-001\",\"item\":\"laptop\"}'");
    io:println("  2. Send payment: curl -X POST http://localhost:9094/orders/ORD-001/payment -H 'Content-Type: application/json' -d '{\"amount\":1999.99}'");
}
