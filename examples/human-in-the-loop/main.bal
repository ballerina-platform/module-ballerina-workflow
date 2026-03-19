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

// Human-in-the-Loop (Forward Recovery) Example
//
// Demonstrates forward recovery: when an activity fails and automated retry
// cannot resolve the problem, the workflow pauses and waits for a human
// decision before continuing. The workflow resumes based on the
// reviewer's choice — approve (retry once more) or cancel.
//
// Start the service:
//   bal run
//
// Then use the HTTP API to drive the workflow:
//   POST /api/orders              — start a new order
//   POST /api/orders/{id}/review  — send reviewer decision
//   GET  /api/orders/{id}         — get the final result

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

# Data sent by a reviewer to decide how to handle an activity failure.
#
# + reviewerId - ID of the reviewer making the decision
# + approved - true to retry the failed step, false to cancel the order
# + note - Optional note explaining the decision
type ReviewDecision record {|
    string reviewerId;
    boolean approved;
    string? note;
|};

// ---------------------------------------------------------------------------
// ACTIVITIES
// ---------------------------------------------------------------------------

# Charges a credit card for an order.
# Simulates a transient payment gateway failure to trigger the review path.
#
# + cardToken - The tokenized card reference
# + amount - The amount to charge
# + return - Transaction ID or error
@workflow:Activity
function chargeCard(string cardToken, decimal amount) returns string|error {
    io:println(string `Charging card ${cardToken} for amount ${amount}`);
    // Simulate payment gateway down — triggers the human-in-the-loop path
    return error("Payment gateway timeout: no response from acquirer");
}

# Charges a credit card for a manual retry approved by a reviewer.
# Uses the same logic as chargeCard but modelled as a separate activity
# so it is clearly labelled as "manual retry" in the workflow event history.
#
# + cardToken - The tokenized card reference
# + amount - The amount to charge
# + return - Transaction ID or error
@workflow:Activity
function chargeCardManualRetry(string cardToken, decimal amount) returns string|error {
    io:println(string `Manual retry: charging card ${cardToken} for amount ${amount}`);
    // Second attempt — simulate recovery (gateway is back)
    return string `TXN-MANUAL-${cardToken}`;
}

# Notifies the review team that a payment has failed and needs attention.
#
# + orderId - The order identifier
# + reason - The failure reason to include in the notification
# + return - Confirmation or error
@workflow:Activity
function notifyReviewTeam(string orderId, string reason) returns string|error {
    io:println(string `[REVIEW NEEDED] Order ${orderId} payment failed: ${reason}`);
    io:println("Review team notified. Waiting for decision...");
    return "Notified";
}

// ---------------------------------------------------------------------------
// WORKFLOW
// ---------------------------------------------------------------------------

# Processes an order with human-in-the-loop forward recovery for payment failures.
#
# If `chargeCard` exhausts its retries, the workflow:
#   1. Notifies the review team via `notifyReviewTeam`
#   2. Pauses by waiting on `events.review`
#   3. On approval: retries the charge one more time with `chargeCardManualRetry`
#   4. On rejection: returns a CANCELLED result
#
# The workflow is fully durable while paused — worker restarts do not lose state.
#
# + ctx - Workflow context for calling activities
# + input - Order details
# + events - Record containing the review decision future
# + return - Final order result or error
@workflow:Workflow
function processOrder(
    workflow:Context ctx,
    OrderInput input,
    record {| future<ReviewDecision> review; |} events
) returns OrderResult|error {

    // Attempt payment with 3 retries
    string|error paymentResult = ctx->callActivity(chargeCard, {
        "cardToken": input.cardToken,
        "amount": input.amount
    }, retryOnError = true, maxRetries = 3, retryDelay = 1.0, retryBackoff = 2.0);

    if paymentResult is error {
        io:println(string `Payment failed after retries: ${paymentResult.message()}`);

        // Notify the review team and pause for a human decision
        string _ = check ctx->callActivity(notifyReviewTeam, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow durably pauses here until the "review" data arrives
        ReviewDecision decision = check wait events.review;
        io:println(string `Review decision received from ${decision.reviewerId}: approved=${decision.approved}`);

        if !decision.approved {
            return {
                orderId: input.orderId,
                status: "CANCELLED",
                message: string `Cancelled by ${decision.reviewerId}: ${decision.note ?: "no note"}`
            };
        }

        // Reviewer approved — attempt one manual retry
        string retryResult = check ctx->callActivity(chargeCardManualRetry, {
            "cardToken": input.cardToken,
            "amount": input.amount
        });
        return {orderId: input.orderId, status: "COMPLETED", message: retryResult};
    }

    return {orderId: input.orderId, status: "COMPLETED", message: paymentResult};
}

// ---------------------------------------------------------------------------
// HTTP SERVICE
// ---------------------------------------------------------------------------

# HTTP service that exposes the human-in-the-loop workflow over REST.
#
# Endpoints:
#   POST /api/orders              — creates a new order workflow
#   POST /api/orders/{id}/review  — sends a reviewer decision
#   GET  /api/orders/{id}         — retrieves the workflow result (blocks until complete)
service /api on new http:Listener(8090) {

    # Starts a new order processing workflow.
    resource function post orders(@http:Payload OrderInput input) returns record {|string workflowId;|}|error {
        string workflowId = check workflow:run(processOrder, input);
        io:println(string `Workflow started: ${workflowId}`);
        return {workflowId};
    }

    # Sends the reviewer's decision to a paused workflow.
    resource function post orders/[string workflowId]/review(@http:Payload ReviewDecision decision)
            returns record {|string status; string message;|}|error {
        check workflow:sendData(processOrder, workflowId, "review", decision);
        io:println(string `Review decision sent to workflow ${workflowId}`);
        return {status: "accepted", message: "Review decision delivered to workflow"};
    }

    # Retrieves the final result of a workflow. Blocks until the workflow completes.
    resource function get orders/[string workflowId]() returns workflow:WorkflowExecutionInfo|error {
        return workflow:getWorkflowResult(workflowId);
    }
}
