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

type ChildInput record {|
    string value;
|};

function plainFunction(ChildInput input) returns string {
    return input.value;
}

@workflow:Activity
function someActivity(string value) returns string|error {
    return value;
}

@workflow:Workflow
function parentProcess(workflow:Context ctx, ChildInput input) returns string|error {
    // ERROR: the first argument of runChildWorkflow must be a @Workflow function
    string childId = check ctx->runChildWorkflow(plainFunction, input = {value: "x"});
    // ERROR: the first argument of callWorkflow must be a @Workflow function
    string result = check ctx->callWorkflow(someActivity, input = {value: "x"});
    return childId + result;
}
