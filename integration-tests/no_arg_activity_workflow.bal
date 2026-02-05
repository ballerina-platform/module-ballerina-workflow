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
// NO-ARG ACTIVITY WORKFLOW
// ================================================================================
// 
// This workflow demonstrates calling activities that have no parameters.
// When an activity has no parameters, we can call it with an empty record {}.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for the no-arg activity workflow.
#
# + id - The workflow identifier
type NoArgWorkflowInput record {|
    string id;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity with no parameters that returns a static value.
# This demonstrates the simplest form of activity.
#
# + return - A greeting string or error
@workflow:Activity
function getStaticGreetingActivity() returns string|error {
    return "Hello from no-arg activity!";
}

# Activity with no parameters that returns a number.
#
# + return - A constant number or error
@workflow:Activity
function getConstantValueActivity() returns int|error {
    return 42;
}

# Activity with no parameters that returns a record.
#
# + return - A config record or error
@workflow:Activity
function getDefaultConfigActivity() returns record {|string version; boolean enabled;|}|error {
    return {version: "1.0.0", enabled: true};
}

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# A workflow that calls a single no-arg activity.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - The greeting result or error
@workflow:Process
function singleNoArgActivityWorkflow(workflow:Context ctx, NoArgWorkflowInput input) returns string|error {
    // Call activity with empty args using {}
    string greeting = check ctx->callActivity(getStaticGreetingActivity, {});
    return greeting;
}

# A workflow that calls multiple no-arg activities.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - A combined result or error
@workflow:Process
function multipleNoArgActivitiesWorkflow(workflow:Context ctx, NoArgWorkflowInput input) returns string|error {
    // Call multiple no-arg activities
    string greeting = check ctx->callActivity(getStaticGreetingActivity, {});
    int number = check ctx->callActivity(getConstantValueActivity, {});
    record {|string version; boolean enabled;|} config = check ctx->callActivity(getDefaultConfigActivity, {});
    
    // Combine results
    return string `${greeting} Number: ${number}, Version: ${config.version}, Enabled: ${config.enabled}`;
}
