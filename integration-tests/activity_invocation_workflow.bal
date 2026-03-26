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
// ACTIVITY INVOCATION TRACKING WORKFLOW
// ================================================================================
//
// These workflows verify that WorkflowExecutionInfo.activityInvocations is
// populated from the Temporal event history and that the optional `attempt`
// field correctly reflects retry behaviour.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for activity invocation test workflows.
#
# + id - The workflow identifier
# + value - A test value to pass through activities
type ActivityInvocationInput record {|
    string id;
    string value;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# A simple activity that uppercases the input.
#
# + text - The input text
# + return - Uppercased text or error
@workflow:Activity
function uppercaseActivity(string text) returns string|error {
    return text.toUpperAscii();
}

# A simple activity that returns the string length.
#
# + text - The input text
# + return - The length or error
@workflow:Activity
function lengthActivity(string text) returns int|error {
    return text.length();
}

# An activity that always fails — used to test retry tracking.
#
# + message - The input message
# + return - Always returns an error
@workflow:Activity
function invocationFailActivity(string message) returns string|error {
    return error("Invocation fail: " + message);
}

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# Workflow that calls two activities sequentially and returns combined output.
# Used to verify that activityInvocations contains COMPLETED entries for both.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Combined result or error
@workflow:Workflow
function twoActivityInvocationWorkflow(workflow:Context ctx, ActivityInvocationInput input) returns string|error {
    string upper = check ctx->callActivity(uppercaseActivity, {"text": input.value});
    int len = check ctx->callActivity(lengthActivity, {"text": input.value});
    return upper + ":" + len.toString();
}

# Workflow that calls an always-failing activity with retryOnError=true and maxRetries=2.
# After 3 total attempts (1 initial + 2 retries) the workflow fails.
# Used to verify that the FAILED invocation has attempt >= 3.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function retryInvocationWorkflow(workflow:Context ctx, ActivityInvocationInput input) returns string|error {
    string result = check ctx->callActivity(invocationFailActivity, {"message": input.value},
            retryOnError = true, maxRetries = 2, retryDelay = 0.1);
    return result;
}

# Workflow that calls an always-failing activity with retryOnError=false (default).
# The activity fails once and the error propagates immediately.
# Used to verify the FAILED invocation has attempt == 1.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - Result or error
@workflow:Workflow
function singleFailInvocationWorkflow(workflow:Context ctx, ActivityInvocationInput input) returns string|error {
    string result = check ctx->callActivity(invocationFailActivity, {"message": input.value});
    return result;
}
