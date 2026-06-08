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
// RETRY ACTIVITY WORKFLOW
// ================================================================================
//
// This workflow demonstrates the activity retry behavior and retryOnError options.
// - Default (retryOnError=false): errors are returned as normal values, no Temporal retries
// - retryOnError=true: errors trigger Temporal retries up to maxRetries times
// - Custom retry options (maxRetries, retryDelay, retryBackoff, maxRetryDelay) control retry behavior
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for retry activity workflow.
#
# + id - The workflow identifier
# + mode - Test mode: "default_fail", "retry_on_error_true", "custom_retry"
type RetryActivityInput record {|
    string id;
    string mode;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity that always fails - used to test that errors are treated as failures by default.
#
# + message - The error message
# + return - Always returns an error
@workflow:Activity
function alwaysFailActivity(string message) returns string|error {
    return error("Activity error: " + message);
}

# Activity that fails with a detail record attached to the error.
# This tests that Ballerina error details are properly serialized
# and visible in the Temporal UI.
#
# + orderId - The order identifier
# + errorCode - A numeric error code
# + return - Always returns an error with detail record
@workflow:Activity
function failWithDetailsActivity(string orderId, int errorCode) returns string|error {
    return error("Order processing failed",
        orderId = orderId,
        errorCode = errorCode,
        stage = "payment"
    );
}

# Activity that fails with a cause chain (inner error wrapped by outer error).
# This tests that nested Ballerina error cause chains are properly
# serialized in the Temporal failure representation.
#
# + operation - The operation that failed
# + return - Always returns an error with a cause
@workflow:Activity
function failWithCauseActivity(string operation) returns string|error {
    error innerError = error("Connection refused",
        host = "db.example.com",
        port = 5432
    );
    return error("Failed to execute: " + operation, innerError,
        retryable = true
    );
}

# Activity that succeeds - used as a fallback path.
#
# + value - The input value
# + return - The processed value or error
@workflow:Activity
function alwaysSucceedActivity(string value) returns string|error {
    return "Success: " + value;
}

# Compensation activity — undoes a previously completed step.
# Simulates a rollback or undo operation in a Saga pattern.
#
# + reason - The reason the compensation is being triggered
# + return - Compensation result or error
@workflow:Activity
function compensateActivity(string reason) returns string|error {
    return "Compensated: " + reason;
}

# Non-critical activity used to test graceful-completion degradation.
# Always fails, but its failure is intentionally ignored by the workflow.
#
# + label - An identifier for the notification
# + return - Always returns an error
@workflow:Activity
function nonCriticalActivity(string label) returns string|error {
    return error("Non-critical failure for: " + label);
}

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# Workflow that tests default behavior (retryOnError=false, errors returned as values).
# With the default settings, the activity error is propagated to the caller via `check`
# and the workflow fails immediately — no Temporal-level retries are attempted.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryDefaultFailWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Default: retryOnError=false, error returned as value — `check` propagates it as workflow failure
    string result = check ctx->callActivity(alwaysFailActivity, {"message": "test failure"});
    return result;
}

# Workflow that tests retryOnError=true behavior then handles the final failure.
# The activity error should propagate as workflow failure when retries are exhausted.
# Here we explicitly pass retryOnError=false so the error is returned as a value.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryFailOnErrorFalseWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // retryOnError=false (explicit): error is returned as a normal value — no Temporal retries
    string|error result = ctx->callActivity(alwaysFailActivity, {"message": "soft failure"});
    if result is error {
        // Error was returned as a value, not a failure — handle gracefully
        return "Handled error: " + result.message();
    }
    return result;
}

# Workflow that tests custom retry options with retryOnError=true.
# The activity always fails, so the workflow should fail after maxRetries are exhausted.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryCustomPolicyWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // retryOnError=true with custom options: 3 retries, 1-second initial delay, 1.5x backoff
    string result = check ctx->callActivity(alwaysFailActivity, {"message": "custom retry"},
                retryPolicy = <workflow:AutoRetry>{maxRetries: 3, retryDelay: 1.0d, retryBackoff: 1.5d});
    return result;
}

# Workflow that tests activity failure with error details.
# The activity returns an error with a detail record, and the workflow fails so
# that the Temporal UI shows the details payload.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryFailWithDetailsWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string result = check ctx->callActivity(failWithDetailsActivity,
        {"orderId": "ORD-12345", "errorCode": 4001});
    return result;
}

# Workflow that tests activity failure with a cause chain.
# The activity returns an error wrapping an inner cause, and the workflow fails
# so that the Temporal UI shows the full cause hierarchy.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryFailWithCauseWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string result = check ctx->callActivity(failWithCauseActivity,
        {"operation": "fetchUserProfile"});
    return result;
}

# Workflow that tests retryOnError=false with an error that has details.
# The error details should be accessible on the Ballerina side.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryHandleDetailsWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string|error result = ctx->callActivity(failWithDetailsActivity,
        {"orderId": "ORD-99999", "errorCode": 5002});
    if result is error {
        return "Handled: " + result.message();
    }
    return result;
}

# Workflow that tests retryOnError=false with an error that has a cause.
# The error message should be accessible on the Ballerina side.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryHandleCauseWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string|error result = ctx->callActivity(failWithCauseActivity,
        {"operation": "updateInventory"});
    if result is error {
        return "Handled: " + result.message();
    }
    return result;
}

// ================================================================================
// RETRY EXHAUSTION SCENARIOS
// ================================================================================
//
// The following four workflows each demonstrate what happens after a Temporal retry
// policy is fully exhausted (retryOnError=true, maxRetries > 0, activity always fails).
//
// Scenario A — Unhandled: the ActivityFailure propagates up; workflow transitions to FAILED.
// Scenario B1 — Fallback: a secondary activity is tried when the primary is exhausted.
// Scenario B2 — Compensation (Saga): a compensating activity undoes prior committed work.
// Scenario B3 — Graceful completion: the failed activity was non-critical; workflow completes.

# Scenario A — Unhandled retry exhaustion.
# The activity is retried twice (maxRetries=2) and always fails.
# The error is NOT caught, so it propagates via `check` and the workflow
# transitions to FAILED. No subsequent steps execute.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryExhaustUnhandledWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // retryOnError=true: Temporal retries the activity up to maxRetries times.
    // After exhaustion, the error propagates to the workflow via `check`.
    string result = check ctx->callActivity(alwaysFailActivity, {"message": "exhaust unhandled"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d});
    // This line is never reached when the activity always fails.
    return result;
}

# Scenario B1 — Fallback activity after retry exhaustion.
# The primary activity is retried twice and always fails. The failure is
# caught and a fallback (secondary) activity is executed instead.
# The workflow completes successfully via the fallback path.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryExhaustFallbackWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Try the primary activity with 2 retries.
    string|error primaryResult = ctx->callActivity(alwaysFailActivity, {"message": "primary failed"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d});
    if primaryResult is error {
        // Primary exhausted — fall back to a secondary activity.
        string fallbackResult = check ctx->callActivity(alwaysSucceedActivity, {"value": "fallback"});
        return "Fallback: " + fallbackResult;
    }
    return primaryResult;
}

# Scenario B2 — Compensation (Saga pattern) after retry exhaustion.
# Step 1 (pre-commit) succeeds. Step 2 is retried twice and always fails.
# On exhaustion, a compensation activity is executed to undo Step 1,
# and the workflow completes with a compensated result.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryExhaustCompensateWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Step 1: pre-commit succeeds.
    string step1 = check ctx->callActivity(alwaysSucceedActivity, {"value": "step1-commit"});

    // Step 2: always fails after 2 retries — need to undo step 1.
    string|error step2Result = ctx->callActivity(alwaysFailActivity, {"message": "step2 failed"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d});
    if step2Result is error {
        // Compensate step 1 by running the undo activity.
        string compensation = check ctx->callActivity(compensateActivity,
                {"reason": "step2 exhausted retries"});
        return "Compensated after step1=" + step1 + "; " + compensation;
    }
    return step1 + " + " + step2Result;
}

# Scenario B3 — Graceful completion when a non-critical activity fails.
# The notification activity is retried once and always fails, but it is
# not required for the business outcome. The workflow catches the error,
# skips the non-critical step, and completes successfully.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryExhaustGracefulWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Core business step — must succeed.
    string coreResult = check ctx->callActivity(alwaysSucceedActivity, {"value": "core-step"});

    // Non-critical notification — retried once; failure is tolerated.
    string|error notifyResult = ctx->callActivity(nonCriticalActivity, {"label": "notify-user"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 1, retryDelay: 1.0d});
    if notifyResult is error {
        // Log (via return value) that the notification was skipped, but still complete.
        return coreResult + " (notification skipped: " + notifyResult.message() + ")";
    }
    return coreResult + " + " + notifyResult;
}

// ================================================================================
// ADDITIONAL PATTERN COVERAGE
// ================================================================================
//
// The following workflows exercise patterns documented in the pattern guides
// that are not yet covered by the basic retry-exhaustion scenarios above.
//
// Multi-tier fallback — email → SMS → ticket (error-fallback.md "Chaining Multiple Fallbacks")
// Multi-step compensation — 3 steps, compensate in reverse (error-compensation.md "Scaling to More Steps")
// Multi non-critical graceful — email + audit both optional (graceful-completion.md)

// --- Activities for multi-tier fallback ---

# First-tier activity that always fails (simulates email sending failure).
#
# + to - The email recipient
# + message - The message body
# + return - Always returns an error
@workflow:Activity
function sendEmailActivity(string to, string message) returns string|error {
    return error("Email service unavailable for: " + to);
}

# Second-tier activity that always fails (simulates SMS sending failure).
#
# + phone - The phone number
# + message - The message body
# + return - Always returns an error
@workflow:Activity
function sendSmsActivity(string phone, string message) returns string|error {
    return error("SMS gateway timeout for: " + phone);
}

# Third-tier activity that succeeds (simulates creating a support ticket).
#
# + subject - The ticket subject
# + body - The ticket body
# + return - A ticket ID
@workflow:Activity
function createSupportTicketActivity(string subject, string body) returns string|error {
    return "TICKET-001";
}

// --- Activities for multi-step compensation ---

# Step activity that always succeeds (simulates a committed operation).
#
# + stepName - The name of the step
# + return - Confirmation string
@workflow:Activity
function commitStepActivity(string stepName) returns string|error {
    return "committed:" + stepName;
}

# Step activity that always fails (simulates a failure at a later step).
#
# + stepName - The name of the failing step
# + return - Always returns an error
@workflow:Activity
function failingStepActivity(string stepName) returns string|error {
    return error("Step failed: " + stepName);
}

# Compensating activity that undoes a specific committed step.
#
# + stepName - The name of the step to compensate
# + return - Compensation confirmation
@workflow:Activity
function compensateStepActivity(string stepName) returns string|error {
    return "compensated:" + stepName;
}

// --- Activities for multi-non-critical graceful ---

# Non-critical email activity that always fails.
#
# + orderId - The order ID
# + return - Always returns an error
@workflow:Activity
function sendConfirmationEmailActivity(string orderId) returns string|error {
    return error("Email service down for order: " + orderId);
}

# Non-critical audit log activity that always fails.
#
# + orderId - The order ID
# + reservationId - The reservation ID
# + return - Always returns an error
@workflow:Activity
function writeAuditLogActivity(string orderId, string reservationId) returns string|error {
    return error("Audit service unreachable for order: " + orderId);
}

# Critical reservation activity that always succeeds.
#
# + orderId - The order ID
# + item - The item to reserve
# + quantity - The quantity
# + return - A reservation ID
@workflow:Activity
function reserveInventoryActivity(string orderId, string item, int quantity) returns string|error {
    return "RSV-" + orderId;
}

// ================================================================================
// Multi-tier Fallback Workflow (matches error-fallback.md "Chaining Multiple Fallbacks")
// ================================================================================

# Workflow exercising a 3-tier fallback chain: email → SMS → support ticket.
# Primary and secondary always fail; the final tier (support ticket) succeeds.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function multiTierFallbackWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Tier 1: try email with retries
    string|error emailResult = ctx->callActivity(sendEmailActivity,
            {"to": "user@example.com", "message": "Hello"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d, retryBackoff: 2.0d});

    if emailResult is error {
        // Tier 2: try SMS with retries
        string|error smsResult = ctx->callActivity(sendSmsActivity,
                {"phone": "+1234567890", "message": "Hello"},
                retryPolicy = <workflow:AutoRetry>{maxRetries: 1, retryDelay: 1.0d});

        if smsResult is error {
            // Tier 3: final fallback — create support ticket
            // `check` here: if this also fails, the workflow fails
            string ticketId = check ctx->callActivity(createSupportTicketActivity,
                    {"subject": "Notification failed", "body": "All channels exhausted"});
            return "Fallback:ticket:" + ticketId;
        }
        return "Fallback:sms:" + smsResult;
    }
    return "Primary:email:" + emailResult;
}

// ================================================================================
// Multi-step Compensation Workflow (matches error-compensation.md "Scaling to More Steps")
// ================================================================================

# Workflow exercising 3-step compensation in reverse order.
# Step 1 and step 2 commit. Step 3 fails after retries.
# Compensate step 2 first, then step 1 (reverse order).
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function multiStepCompensationWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Step 1: committed
    string step1 = check ctx->callActivity(commitStepActivity, {"stepName": "reserve-inventory"});

    // Step 2: committed
    string step2 = check ctx->callActivity(commitStepActivity, {"stepName": "charge-card"});

    // Step 3: fails after retries — need to compensate step 2, then step 1
    string|error step3Result = ctx->callActivity(failingStepActivity, {"stepName": "ship-order"},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 2, retryDelay: 1.0d});

    if step3Result is error {
        // Compensate in reverse order: step 2 first, then step 1
        string comp2 = check ctx->callActivity(compensateStepActivity, {"stepName": "charge-card"});
        string comp1 = check ctx->callActivity(compensateStepActivity, {"stepName": "reserve-inventory"});
        return "ROLLED_BACK:" + comp2 + "," + comp1;
    }
    return "COMPLETED:" + step1 + "," + step2 + "," + step3Result;
}

// ================================================================================
// Multi Non-Critical Graceful Workflow (matches graceful-completion.md full pattern)
// ================================================================================

# Workflow exercising graceful completion with multiple non-critical steps.
# Critical reservation succeeds. Non-critical email and audit both fail.
# Workflow completes, reporting which steps were skipped.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function multiNonCriticalGracefulWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // CRITICAL — propagate failure
    string reservationId = check ctx->callActivity(reserveInventoryActivity,
            {"orderId": input.id, "item": "widget", "quantity": 1});

    // NON-CRITICAL 1 — tolerate failure
    string|error emailResult = ctx->callActivity(sendConfirmationEmailActivity,
            {"orderId": input.id},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 1, retryDelay: 1.0d});

    // NON-CRITICAL 2 — tolerate failure
    string|error auditResult = ctx->callActivity(writeAuditLogActivity,
            {"orderId": input.id, "reservationId": reservationId},
            retryPolicy = <workflow:AutoRetry>{maxRetries: 1, retryDelay: 1.0d});

    // Build result, noting any skipped steps
    string[] skipped = [];
    if emailResult is error {
        skipped.push("email");
    }
    if auditResult is error {
        skipped.push("audit");
    }

    string suffix = skipped.length() > 0
        ? string ` (skipped: ${string:'join(", ", ...skipped)})`
        : "";
    return reservationId + suffix;
}

// ================================================================================
// MANUAL RETRY (HUMAN-IN-THE-LOOP RETRY POLICY)
// ================================================================================

# Activity that can be recovered by changing input via manual retry decision.
#
# + mode - "fail" to return an error, "ok" to succeed
# + return - Success message or error
@workflow:Activity
function recoverableByInputActivity(string mode) returns string|error {
    if mode == "ok" {
        return "Recovered with manual input";
    }
    return error("Recoverable activity failed");
}

# Workflow that starts with failing input and expects a human manual retry decision
# with updated input to recover.
#
# + ctx - Workflow context
# + input - Workflow input
# + return - Result or error
@workflow:Workflow
function manualRetryWithInputWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string result = check ctx->callActivity(recoverableByInputActivity,
            {"mode": "fail"},
            retryPolicy = <workflow:ManualRetry>{taskName: "recoverInput", userRoles: ["ops", "admin"]});
    return result;
}

# Workflow that always fails unless the human keeps retrying forever.
# Used to test explicit manual retry decision "fail".
#
# + ctx - Workflow context
# + input - Workflow input
# + return - Result or error
@workflow:Workflow
function manualRetryFailDecisionWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string result = check ctx->callActivity(alwaysFailActivity,
            {"message": "manual retry fail decision"},
            retryPolicy = <workflow:ManualRetry>{taskName: "manualFail", userRoles: ["ops"]});
    return result;
}

# Workflow that always fails; tests a same-input "retry" decision followed by "fail".
#
# + ctx - Workflow context
# + input - Workflow input
# + return - Result or error
@workflow:Workflow
function manualRetrySameInputWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string result = check ctx->callActivity(alwaysFailActivity,
            {"message": "manual same-input retry"},
            retryPolicy = <workflow:ManualRetry>{taskName: "manualSameInput", userRoles: ["ops"]});
    return result;
}
