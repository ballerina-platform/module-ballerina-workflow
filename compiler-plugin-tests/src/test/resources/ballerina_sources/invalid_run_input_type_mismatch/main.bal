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

type OrderInput record {|
    string orderId;
    int quantity;
|};

@workflow:Workflow
function recordInputWorkflow(workflow:Context ctx, OrderInput input) returns string|error {
    return input.orderId;
}

@workflow:Workflow
function stringInputWorkflow(workflow:Context ctx, string input) returns string|error {
    return input;
}

public function startWorkflows() returns error? {
    // Invalid: string input for a workflow expecting a record - WORKFLOW_131
    string wf1 = check workflow:run(recordInputWorkflow, "not-a-record");

    // Invalid: int input for a workflow expecting a string - WORKFLOW_131
    int count = 42;
    string wf2 = check workflow:run(stringInputWorkflow, count);

    // Invalid: explicit nil input for a workflow with a non-nilable input - WORKFLOW_131
    string wf3 = check workflow:run(stringInputWorkflow, ());

    // Invalid: mapping constructor for a workflow expecting a string - WORKFLOW_131
    string wf4 = check workflow:run(stringInputWorkflow, {value: "text"});

    return checkStarted([wf1, wf2, wf3, wf4]);
}

function checkStarted(string[] ids) returns error? {
    if ids.length() == 0 {
        return error("no workflows started");
    }
}
