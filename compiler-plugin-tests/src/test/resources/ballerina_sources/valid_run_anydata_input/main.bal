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

// Workflow with a string input - any anydata subtype is a valid input type.
@workflow:Workflow
function stringInputWorkflow(workflow:Context ctx, string input) returns string|error {
    return input;
}

// Workflow with a record input.
@workflow:Workflow
function recordInputWorkflow(workflow:Context ctx, OrderInput input) returns string|error {
    return input.orderId;
}

// Workflow with an int input.
@workflow:Workflow
function intInputWorkflow(workflow:Context ctx, int input) returns int|error {
    return input * 2;
}

public function startWorkflows() returns error? {
    // string literal input for a string-input workflow
    string wf1 = check workflow:run(stringInputWorkflow, "hello");

    // record value input passed as a typed variable
    OrderInput orderInput = {orderId: "ORD-1", quantity: 2};
    string wf2 = check workflow:run(recordInputWorkflow, orderInput);

    // record input passed as a mapping constructor
    string wf3 = check workflow:run(recordInputWorkflow, {orderId: "ORD-2", quantity: 3});

    // named argument style
    string wf4 = check workflow:run(intInputWorkflow, input = 42);

    // nil input is always allowed - means "no input"
    string wf5 = check workflow:run(stringInputWorkflow);

    return checkStarted([wf1, wf2, wf3, wf4, wf5]);
}

function checkStarted(string[] ids) returns error? {
    if ids.length() == 0 {
        return error("no workflows started");
    }
}
