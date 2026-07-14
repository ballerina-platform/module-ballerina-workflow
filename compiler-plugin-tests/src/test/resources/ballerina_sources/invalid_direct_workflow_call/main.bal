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

import ballerina/workflow;

@workflow:Workflow
function simpleWorkflow(workflow:Context ctx, string input) returns string|error {
    return input;
}

// Invalid: @Workflow functions must not be invoked directly - WORKFLOW_136
function caller(workflow:Context ctx) returns error? {
    string result = check simpleWorkflow(ctx, "direct");
    if result == "" {
        return error("empty result");
    }
}
