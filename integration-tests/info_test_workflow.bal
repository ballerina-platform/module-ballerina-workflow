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
// WORKFLOW INFO TEST - Workflow Definition
// ================================================================================
// 
// This workflow is used to test workflow info retrieval functionality.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for info test workflow.
#
# + id - The workflow identifier
# + name - The name to process
type InfoTestInput record {|
    string id;
    string name;
|};

// ================================================================================
// ACTIVITY DEFINITION
// ================================================================================

# Activity for the info test workflow.
#
# + name - The name to process
# + return - A processed string or error
@workflow:Activity
function infoTestActivity(string name) returns string|error {
    return "Processed: " + name;
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow used to test workflow info retrieval.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + return - The processed result or error
@workflow:Process
function infoTestWorkflow(workflow:Context ctx, InfoTestInput input) returns string|error {
    string result = check ctx->callActivity(infoTestActivity, {"name": input.name});
    return result;
}
