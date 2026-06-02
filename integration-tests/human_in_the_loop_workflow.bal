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
// HUMAN-IN-THE-LOOP WORKFLOW (Forward Recovery + Timeout)
// ================================================================================
//
// Covers the doc examples from patterns/human-in-the-loop.md:
//
//   1. Forward recovery: payment fails → notify review team → wait for
//      human signal → act on decision (approve or cancel).
//
//   2. Timeout via alternate wait: race a signal future against a durable
//      timer using `wait f1|f2` so the workflow auto-cancels if no decision
//      arrives in time.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Input for the human-in-the-loop workflow.
#
# + id - Unique test identifier
# + orderId - Order identifier
# + amount - Payment amount
# + cardToken - Tokenized card reference
# + shouldFailPayment - Whether the payment activity should fail
type HitlInput record {|
    string id;
    string orderId;
    decimal amount;
    string cardToken;
    boolean shouldFailPayment;
|};

# Result from the human-in-the-loop workflow.
#
# + orderId - Order identifier
# + status - Final status (COMPLETED, CANCELLED, etc.)
# + message - Human-readable message
type HitlResult record {|
    string orderId;
    string status;
    string message;
|};

# Review decision signal sent by a human reviewer.
#
# + reviewerId - Identifier of the reviewer
# + approved - true = retry the failed step; false = cancel the order
# + note - Optional note from the reviewer
type HitlReviewDecision record {|
    string reviewerId;
    boolean approved;
    string? note;
|};



// ================================================================================
// ACTIVITIES
// ================================================================================

# Charges a credit card. Fails when shouldFail is true.
#
# + cardToken - Tokenized card reference
# + amount - Amount to charge
# + shouldFail - Whether to simulate a failure
# + return - Transaction ID or error
@workflow:Activity
function hitlChargeCard(string cardToken, decimal amount, boolean shouldFail) returns string|error {
    if shouldFail {
        return error("Payment gateway timeout");
    }
    return string `TXN-${cardToken}-${amount}`;
}

# Manual retry of card charge (always succeeds).
#
# + cardToken - Tokenized card reference
# + amount - Amount to charge
# + return - Transaction ID
@workflow:Activity
function hitlManualRetry(string cardToken, decimal amount) returns string|error {
    return string `TXN-MANUAL-${cardToken}`;
}

# Notifies the review team about a payment failure.
#
# + orderId - Order identifier
# + reason - Failure reason
# + return - Confirmation string
@workflow:Activity
function hitlNotifyReviewTeam(string orderId, string reason) returns string|error {
    return "Notified";
}

// ================================================================================
// WORKFLOW 1 — Forward Recovery (human-in-the-loop.md main example)
// ================================================================================

# Full human-in-the-loop forward recovery workflow.
#
# If payment fails after retries, the workflow notifies a review team and
# pauses until a human sends a review decision signal:
#   - Approved  → retry payment one more time via manual retry
#   - Rejected  → cancel the order
#
# If payment succeeds, the workflow completes immediately.
#
# + ctx - Workflow context
# + input - Order and payment details
# + events - Record containing the review decision future
# + return - Final order result
@workflow:Workflow
function hitlForwardRecoveryWorkflow(
    workflow:Context ctx,
    HitlInput input,
    record {| future<HitlReviewDecision> review; |} events
) returns HitlResult|error {

    // Attempt payment with Temporal retries
    string|error paymentResult = ctx->callActivity(hitlChargeCard, {
        "cardToken": input.cardToken,
        "amount": input.amount,
        "shouldFail": input.shouldFailPayment
    }, retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d, retryBackoff: 2.0d});

    if paymentResult is error {
        // Notify the review team
        string _ = check ctx->callActivity(hitlNotifyReviewTeam, {
            "orderId": input.orderId,
            "reason": paymentResult.message()
        });

        // Workflow pauses here until a human sends the "review" signal
        HitlReviewDecision decision = check wait events.review;

        if !decision.approved {
            return {
                orderId: input.orderId,
                status: "CANCELLED",
                message: string `Cancelled by ${decision.reviewerId}: ${decision.note ?: "no note"}`
            };
        }

        // Reviewer approved — attempt one manual retry
        string retryResult = check ctx->callActivity(hitlManualRetry, {
            "cardToken": input.cardToken,
            "amount": input.amount
        });
        return {orderId: input.orderId, status: "COMPLETED", message: retryResult};
    }

    return {orderId: input.orderId, status: "COMPLETED", message: paymentResult};
}


