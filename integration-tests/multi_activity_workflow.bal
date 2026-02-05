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
// MULTI ACTIVITY WORKFLOW
// ================================================================================
// 
// This workflow demonstrates calling multiple activities in sequence.
// Each activity is executed independently and their results are combined.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for multi-activity workflow.
#
# + id - The workflow identifier
# + name - The name to process
type MultiActivityInput record {|
    string id;
    string name;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity that creates a greeting message.
#
# + name - The name to greet
# + return - A greeting string or error
@workflow:Activity
function multiGreetActivity(string name) returns string|error {
    return "Hello, " + name + "!";
}

# Activity that multiplies two numbers.
#
# + a - First number
# + b - Second number
# + return - The product or error
@workflow:Activity
function multiplyActivity(int a, int b) returns int|error {
    return a * b;
}

# Activity that returns a fixed timestamp.
#
# + return - A timestamp string or error
@workflow:Activity
function getTimestampActivity() returns string|error {
    return "2026-01-31T12:00:00Z";
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow that calls multiple activities in sequence and combines results.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input containing id and name
# + return - A map with all activity results or error
@workflow:Process
function multiActivityWorkflow(workflow:Context ctx, MultiActivityInput input) returns map<anydata>|error {
    string greeting = check ctx->callActivity(multiGreetActivity, {"name": input.name});
    
    int product = check ctx->callActivity(multiplyActivity, {"a": 5, "b": 7});
    
    string timestamp = check ctx->callActivity(getTimestampActivity, {});
    
    return {
        "greeting": greeting,
        "product": product,
        "timestamp": timestamp
    };
}
