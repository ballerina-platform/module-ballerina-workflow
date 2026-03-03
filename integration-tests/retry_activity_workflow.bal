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
// This workflow demonstrates the activity retry behavior and failOnError options.
// - Default: errors cause activity failure (Temporal retries)
// - failOnError: false treats errors as normal completion values
// - Custom retry policies control retry behavior
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for retry activity workflow.
#
# + id - The workflow identifier
# + mode - Test mode: "default_fail", "fail_on_error_false", "retry_then_succeed", "custom_retry"
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

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# Workflow that tests default failOnError behavior (true) with default retry policy.
# With the default retry policy (maximumAttempts=1), the activity runs once and fails.
# The workflow should fail immediately since no retries are configured.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryDefaultFailWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Default behavior: failOnError=true, maximumAttempts=1 (no retries)
    // Activity will fail on first attempt and workflow should fail
    string result = check ctx->callActivity(alwaysFailActivity, {"message": "test failure"});
    return result;
}

# Workflow that tests failOnError=false behavior.
# The activity error should be returned as a normal value without retries.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryFailOnErrorFalseWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // failOnError=false: error is treated as a normal completion value
    string|error result = ctx->callActivity(alwaysFailActivity, {"message": "soft failure"},
        options = {failOnError: false});
    if result is error {
        // Error was returned as a value, not a failure - handle gracefully
        return "Handled error: " + result.message();
    }
    return result;
}

# Workflow that tests custom retry policy with failOnError=true.
# The activity will fail and workflow should fail after custom retry policy is exhausted.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryCustomPolicyWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    // Custom retry policy: max 3 attempts with 1 second initial interval
    string result = check ctx->callActivity(alwaysFailActivity, {"message": "custom retry"},
        options = {
            retryPolicy: {
                maximumAttempts: 3,
                initialIntervalInSeconds: 1,
                backoffCoefficient: 1.5
            }
        });
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

# Workflow that tests failOnError=false with an error that has details.
# The error details should be accessible on the Ballerina side.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryHandleDetailsWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string|error result = ctx->callActivity(failWithDetailsActivity,
        {"orderId": "ORD-99999", "errorCode": 5002},
        options = {failOnError: false});
    if result is error {
        return "Handled: " + result.message();
    }
    return result;
}

# Workflow that tests failOnError=false with an error that has a cause.
# The error message should be accessible on the Ballerina side.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryHandleCauseWorkflow(workflow:Context ctx, RetryActivityInput input) returns string|error {
    string|error result = ctx->callActivity(failWithCauseActivity,
        {"operation": "updateInventory"},
        options = {failOnError: false});
    if result is error {
        return "Handled: " + result.message();
    }
    return result;
}
