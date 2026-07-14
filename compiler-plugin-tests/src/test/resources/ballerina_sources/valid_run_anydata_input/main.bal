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

// Workflow with a nilable input.
@workflow:Workflow
function nilableInputWorkflow(workflow:Context ctx, string? input) returns string|error {
    return input ?: "no input";
}

// Workflow with no input.
@workflow:Workflow
function noInputWorkflow(workflow:Context ctx) returns string|error {
    return "done";
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

    // omitting the input is always allowed
    string wf5 = check workflow:run(stringInputWorkflow);

    // explicit nil input for a workflow with a nilable input type
    string wf6 = check workflow:run(nilableInputWorkflow, ());

    // explicit nil input for a workflow that declares no input
    string wf7 = check workflow:run(noInputWorkflow, ());

    return checkStarted([wf1, wf2, wf3, wf4, wf5, wf6, wf7]);
}

function checkStarted(string[] ids) returns error? {
    if ids.length() == 0 {
        return error("no workflows started");
    }
}
