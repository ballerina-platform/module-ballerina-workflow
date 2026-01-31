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
// SINGLE ACTIVITY WORKFLOW
// ================================================================================
// 
// This workflow demonstrates calling a single activity from a process.
// Activities are used for non-deterministic operations like I/O, database calls,
// or external API calls.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

type GreetInput record {|
    string id;
    string name;
|};

// ================================================================================
// ACTIVITY DEFINITION
// ================================================================================

# Simple string processing activity that creates a greeting.
#
# + name - The name to greet
# + return - A greeting string or error
@workflow:Activity
function greetActivity(string name) returns string|error {
    return "Hello, " + name + "!";
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow that calls a single activity to generate a greeting.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input containing id and name
# + return - The greeting result or error
@workflow:Process
function singleActivityWorkflow(workflow:Context ctx, GreetInput input) returns string|error {
    anydata greeting = check ctx->callActivity(greetActivity, input.name);
    return <string>greeting;
}
