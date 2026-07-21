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

// ================================================================================
// CHILD WORKFLOW COMPOSITION - TESTS
// ================================================================================
// Tests the child-workflow composition methods on the workflow context:
// runChildWorkflow, getChildWorkflowResult (WorkflowBusyError), waitForChildWorkflow,
// callWorkflow, and sendDataToChildWorkflow.

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testParentStartsChildWorkflow() returns error? {
    // Parent starts a true child workflow with ctx->runChildWorkflow and durably
    // waits for its result with ctx->waitForChildWorkflow.
    string testId = uniqueId("parent-child");
    ParentInput input = {id: testId};
    string workflowId = check workflow:run(parentWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertEquals(<string>result, "Parent received: child-processed:from-parent-" + testId,
        "Parent result should contain the child workflow's processed output");
}

@test:Config {
    groups: ["integration"]
}
function testFanOutFanIn() returns error? {
    // Parent fans out two children and gathers both results.
    string testId = uniqueId("fan-out");
    ParentInput input = {id: testId};
    string workflowId = check workflow:run(fanOutParentWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertEquals(<string>result,
        "child-processed:first-" + testId + "|child-processed:second-" + testId,
        "Fan-out parent should combine both child results in start order");
}

@test:Config {
    groups: ["integration"]
}
function testGetChildWorkflowResultBusy() returns error? {
    // A non-blocking read right after starting a slow child observes
    // workflow:WorkflowBusyError; the durable wait then returns the result.
    string testId = uniqueId("busy-check");
    ParentInput input = {id: testId};
    string workflowId = check workflow:run(busyCheckParentWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertEquals(<string>result, "busy:slow-child-processed:" + testId,
        "The early non-blocking read should observe the busy state");
}

@test:Config {
    groups: ["integration"]
}
function testCallWorkflow() returns error? {
    // ctx->callWorkflow starts the child and waits for its result in one call.
    string testId = uniqueId("call-workflow");
    ParentInput input = {id: testId};
    string workflowId = check workflow:run(callWorkflowParentWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 60);
    test:assertEquals(<string>result, "child-processed:called-" + testId,
        "callWorkflow should return the child's result");
}

@test:Config {
    groups: ["integration"]
}
function testSenderSendsDataToReceiver() returns error? {
    // First start the receiver workflow that waits for data
    string testId = uniqueId("sender-receiver");
    ReceiverInput receiverInput = {id: testId};
    string receiverWorkflowId = check workflow:run(receiverWorkflow, receiverInput);

    // Now start the sender workflow which sends data to the receiver from inside
    // the workflow using ctx->sendDataToChildWorkflow (an external-workflow signal)
    SenderInput senderInput = {targetWorkflowId: receiverWorkflowId};
    string senderWorkflowId = check workflow:run(senderWorkflow, senderInput);

    // Wait for both workflows to complete
    anydata senderResult = check workflow:getWorkflowResult(senderWorkflowId, 60);
    test:assertTrue((<string>senderResult).startsWith("sent-to:"),
        "Sender result should confirm data was sent");

    anydata receiverResult = check workflow:getWorkflowResult(receiverWorkflowId, 60);
    test:assertEquals(<string>receiverResult, "received:hello-from-sender",
        "Receiver should have received the correct message");
}
