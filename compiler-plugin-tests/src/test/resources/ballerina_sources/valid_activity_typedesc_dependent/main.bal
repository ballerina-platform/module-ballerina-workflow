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
import ballerina/jballerina.java;

// Dependently-typed activity with inferred typedesc default <>.
// The return type depends on the typedesc parameter.
// This is the only supported pattern for typedesc in @Activity functions.
@workflow:Activity
function convertData(string data, typedesc<anydata> targetType = <>)
        returns targetType|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.test.TestNatives",
    name: "convertData"
} external;

// Workflow calling the dependently-typed activity.
// callActivity is itself dependently typed, so the typedesc flows from
// the LHS (string) through callActivity into the activity function.
@workflow:Workflow
function typedescDependentWorkflow(workflow:Context ctx, string input) returns string|error {
    string result = check ctx->callActivity(convertData, {"data": input});
    return result;
}
