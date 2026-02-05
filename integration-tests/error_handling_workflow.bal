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
// ERROR HANDLING WORKFLOW
// ================================================================================
// 
// This workflow demonstrates how to handle activity errors gracefully.
// Temporal activities can fail, and workflows need to handle these failures.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for error handling workflow.
#
# + id - The workflow identifier
# + shouldFail - Whether the workflow should trigger a failure
type ErrorHandlingInput record {|
    string id;
    boolean shouldFail;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity that always fails with an error.
#
# + reason - The reason for failure
# + return - Always returns an error
@workflow:Activity
function failingActivity(string reason) returns string|error {
    return error("Activity failed: " + reason);
}

# Activity that succeeds with a greeting.
#
# + name - The name to greet
# + return - A greeting string or error
@workflow:Activity
function successActivity(string name) returns string|error {
    return "Hello, " + name + "!";
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow that demonstrates error handling patterns.
# Can take different paths based on input, showing both success and failure cases.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input with error control flag
# + return - Result message or error
@workflow:Process
function errorHandlingWorkflow(workflow:Context ctx, ErrorHandlingInput input) returns string|error {
    if input.shouldFail {
        string|error result = ctx->callActivity(failingActivity, {"reason": "Intentional failure"});
        if result is error {
            return "Activity error caught: " + result.message();
        }
        return result;
    } else {
        string result = check ctx->callActivity(successActivity, {"name": "World"});
        return result;
    }
}
