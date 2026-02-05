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

type ProcessInput record {|
    string id;
|};

// Activity function WITH required parameters
@workflow:Activity
function processData(string id, int count) returns string|error {
    return "processed-" + id + "-" + count.toString();
}

// Process function that incorrectly calls activity without required args
// This should produce WORKFLOW_109 error for missing required parameters
@workflow:Process
function invalidNoArgProcess(workflow:Context ctx, ProcessInput input) returns string|error {
    // ERROR: Calling activity that requires parameters with empty args record
    string result = check ctx->callActivity(processData, {});
    return result;
}
