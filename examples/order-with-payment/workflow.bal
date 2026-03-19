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

import ballerina/workflow;
import ballerina/io;

// Order Processing with Payment Workflow
// Demonstrates future-based data handling

# Process order and wait for payment confirmation
# This workflow demonstrates data handling:
# 1. Checks inventory
# 2. Waits for payment data using future-based pattern
# 3. Completes order after payment received
#
# + ctx - Workflow context for calling activities
# + request - Order request with orderId and item
# + dataEvents - Record containing futures for expected data events
# + return - Order result or error
@workflow:Workflow
function processOrderWithPayment(
    workflow:Context ctx, 
    OrderRequest request,
    record {| future<PaymentConfirmation> paymentReceived; |} dataEvents
) returns OrderResult|error {
    io:println(string `[Workflow] Processing order: ${request.orderId}`);

    // Step 1: Check inventory
    int stock = check ctx->callActivity(checkInventory, {"item": request.item});

    if stock <= 0 {
        io:println(string `[Workflow] Order ${request.orderId} failed: Out of stock`);
        return {
            orderId: request.orderId,
            status: "FAILED",
            message: "Out of stock"
        };
    }

    // Step 2: Wait for payment data event using Ballerina's native wait
    // The field name 'paymentReceived' maps to the data event name
    io:println(string `[Workflow] Waiting for payment for order: ${request.orderId}`);
    PaymentConfirmation payment = check wait dataEvents.paymentReceived;
    
    io:println(string `[Workflow] Payment received for order: ${request.orderId}, amount: ${payment.amount}`);

    return {
        orderId: request.orderId,
        status: "COMPLETED",
        message: "Order completed successfully"
    };
}
