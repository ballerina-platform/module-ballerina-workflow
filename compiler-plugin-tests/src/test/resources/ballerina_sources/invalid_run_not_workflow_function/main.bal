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

// A plain function without the @Workflow annotation.
function plainFunction(workflow:Context ctx, string input) returns string|error {
    return input;
}

public function startWorkflows() returns error? {
    // Invalid: the first argument must be a function with @Workflow - WORKFLOW_130
    string wf1 = check workflow:run(plainFunction, "hello");
    return checkStarted([wf1]);
}

function checkStarted(string[] ids) returns error? {
    if ids.length() == 0 {
        return error("no workflows started");
    }
}
