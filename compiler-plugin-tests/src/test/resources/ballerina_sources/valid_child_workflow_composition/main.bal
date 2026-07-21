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

type NotifyEvents record {|
    future<map<anydata>> notification;
|};

@workflow:Workflow
function childProcess(workflow:Context ctx, ChildInput input) returns string|error {
    return input.value;
}

@workflow:Workflow
function noInputProcess(workflow:Context ctx) returns string|error {
    return "done";
}

@workflow:Workflow
function receiverProcess(workflow:Context ctx, ChildInput input, NotifyEvents events) returns string|error {
    map<anydata> data = check wait events.notification;
    return input.value;
}

@workflow:Workflow
function parentProcess(workflow:Context ctx, ChildInput input) returns string|error {
    string childId = check ctx->runChildWorkflow(childProcess, input = {value: "x"});
    string|error early = ctx->getChildWorkflowResult(childId);
    string busyNote = early is workflow:WorkflowBusyError ? "busy" : "done";
    string result = check ctx->waitForChildWorkflow(childId);
    string direct = check ctx->callWorkflow(childProcess, input = input);
    string noInput = check ctx->callWorkflow(noInputProcess);
    check ctx->sendDataToChildWorkflow(childId, "notification", {"message": "hi"});
    return result + direct + noInput + busyNote;
}
