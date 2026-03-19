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

// Forward Recovery Example
//
// Demonstrates forward recovery: when an activity fails, the workflow pauses
// and waits for a human to supply corrected data, then retries the failed
// activity with the updated values. This shows how external data can drive
// workflow recovery without rolling back.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/orders                    — start a new order
//   POST /api/orders/{id}/retry-payment — send corrected payment details
//   GET  /api/orders/{id}               — get the final result

import ballerina/http;
import ballerina/io;
import ballerina/workflow;

// ---------------------------------------------------------------------------
// TYPES
// ---------------------------------------------------------------------------

type OrderInput record {|
    string orderId;
    string item;
    decimal amount;
    string cardToken;
|};

type OrderResult record {|
    string orderId;
    string status;
    string message;
|};

# Corrected payment details sent by a user after a payment failure.
# The user can update the card token, the amount, or both.
#
# + cardToken - New or corrected card token
# + amount - Corrected payment amount (optional — keeps original if nil)
type PaymentCorrection record {|
    string cardToken;
    decimal? amount;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Processes a credit-card payment.
# The first call simulates a card-declined error to trigger the
# human-in-the-loop path; the retry with corrected data succeeds.
#
# + cardToken - The tokenized card reference
# + amount - The amount to charge
# + return - Transaction ID or error
@workflow:Activity
function processPayment(string cardToken, decimal amount) returns string|error {
    io:println(string `[Activity] Processing payment: card=${cardToken}, amount=${amount}`);
    if cardToken == "tok_declined" {
        return error("Card declined: insufficient funds");
    }
    return string `TXN-${cardToken}-${amount}`;
}

# Notifies the user that payment has failed and corrected details are needed.
#
# + orderId - The order identifier
# + reason - The failure reason
# + return - Confirmation or error
@workflow:Activity
function notifyPaymentFailure(string orderId, string reason) returns string|error {
    io:println(string `[PAYMENT FAILED] Order ${orderId}: ${reason}`);
    io:println("Waiting for corrected payment details...");
    return "Notified";
}

# Fulfills a paid order.
#
# + orderId - The order identifier
# + item - The item to fulfill
# + return - Fulfillment confirmation or error
@workflow:Activity
function fulfillOrder(string orderId, string item) returns string|error {
    io:println(string `[Activity] Fulfilling order ${orderId}: ${item}`);
    return string `FULFILLED-${orderId}`;
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes an order with human-in-the-loop forward recovery for payment failures.
#
# 1. Attempts payment with the original card details
# 2. If payment fails — notifies the user and pauses for corrected data
# 3. On receiving corrected payment details — retries the payment
# 4. On success — fulfills the order
#
# The workflow is fully durable while paused — worker restarts do not lose state.
#
# + ctx - Workflow context for calling activities
# + input - Order details including card token
# + events - Record containing the payment correction future
# + return - Final order result or error
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentCorrection> paymentRetry; |} events
) returns OrderResult|error {

    // Step 1: Attempt payment
    string|error paymentResult = ctx->callActivity(processPayment, {
        "cardToken": input.cardToken,
        "amount": input.amount
    });

    if paymentResult is error {
        io:println(string `[Workflow] Payment failed: ${paymentResult.message()}`);

        // Step 2: Notify the user and wait for corrected payment data
        string _ = check ctx->callActivity(notifyPaymentFailure, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow durably pauses here until the user sends corrected details
        PaymentCorrection correction = check wait events.paymentRetry;
        io:println(string `[Workflow] Received corrected payment: card=${correction.cardToken}`);

        // Step 3: Retry payment with corrected values
        string txnId = check ctx->callActivity(processPayment, {
            "cardToken": correction.cardToken,
            "amount": correction.amount ?: input.amount
        });

        // Step 4: Fulfill the order
        string fulfillmentId = check ctx->callActivity(fulfillOrder, {
            "orderId": input.orderId,
            "item": input.item
        });
        return {
            orderId: input.orderId,
            status: "COMPLETED",
            message: string `Payment succeeded (${txnId}), order fulfilled (${fulfillmentId})`
        };
    }

    // Happy path — first payment succeeded
    string fulfillmentId = check ctx->callActivity(fulfillOrder, {
        "orderId": input.orderId,
        "item": input.item
    });
    return {
        orderId: input.orderId,
        status: "COMPLETED",
        message: string `Payment succeeded (${paymentResult}), order fulfilled (${fulfillmentId})`
    };
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the human-in-the-loop workflow over REST.
#
# Endpoints:
#   POST /api/orders                    — creates a new order workflow
#   POST /api/orders/{id}/retry-payment — sends corrected payment details
#   GET  /api/orders/{id}               — retrieves the workflow result
service /api on new http:Listener(8090) {

    # Starts a new order processing workflow.
    resource function post orders(@http:Payload OrderInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processOrder, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Sends corrected payment details to a waiting workflow.
    resource function post orders/[string workflowId]/retryPayment(@http:Payload PaymentCorrection correction)
            returns record {|string status; string message;|}|error {
        check workflow:sendData(processOrder, workflowId, "paymentRetry", correction);
        io:println(string `Corrected payment sent to workflow ${workflowId}`);
        return {status: "accepted", message: "Corrected payment details delivered to workflow"};
    }

    # Retrieves the final result of a workflow. Blocks until the workflow completes.
    resource function get orders/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
