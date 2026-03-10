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

// Activity with a required typedesc parameter (no default value).
// typedesc is a readonly type descriptor and should be allowed as an
// activity parameter. The compiler plugin and runtime adapter filter
// it out from args validation and positional arg reconstruction.
@workflow:Activity
function transformData(string data, typedesc<anydata> targetType) returns anydata|error {
    return data;
}

// Workflow calling the activity without providing the typedesc arg.
// The compiler plugin should skip the required typedesc param and not
// report a WORKFLOW_109 (missing required param) error.
@workflow:Workflow
function typedescRequiredWorkflow(workflow:Context ctx, string input) returns string|error {
    string result = check ctx->callActivity(transformData, {"data": input});
    return result;
}
