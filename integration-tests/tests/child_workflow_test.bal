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
// CHILD WORKFLOW / IMPLICIT ACTIVITY - TESTS
// ================================================================================
// Tests that workflow:run and workflow:sendData work as implicit activities
// when called from inside a @Workflow function.

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testParentStartsChildWorkflow() returns error? {
    // Parent workflow starts a child workflow using workflow:run() inside workflow.
    // The call is routed through an implicit activity for determinism.
    string testId = uniqueId("parent-child");
    ParentInput input = {id: testId};
    string workflowId = check workflow:run(parentWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 60);

    test:assertEquals(execInfo.status, "COMPLETED",
        "Parent workflow should complete after child workflow finishes");
    test:assertTrue((<string>execInfo.result).startsWith("Parent received: child-processed:"),
        "Parent result should contain the child workflow's processed output");
}

@test:Config {
    groups: ["integration"]
}
function testSenderSendsDataToReceiver() returns error? {
    // First start the receiver workflow that waits for data
    string testId = uniqueId("sender-receiver");
    ReceiverInput receiverInput = {id: testId};
    string receiverWorkflowId = check workflow:run(receiverWorkflow, receiverInput);

    // Now start the sender workflow which sends data to the receiver
    // from inside the workflow using workflow:sendData() as an implicit activity
    SenderInput senderInput = {targetWorkflowId: receiverWorkflowId};
    string senderWorkflowId = check workflow:run(senderWorkflow, senderInput);

    // Wait for both workflows to complete
    workflow:WorkflowExecutionInfo senderResult = check workflow:getWorkflowResult(senderWorkflowId, 60);
    test:assertEquals(senderResult.status, "COMPLETED",
        "Sender workflow should complete after sending data");
    test:assertTrue((<string>senderResult.result).startsWith("sent-to:"),
        "Sender result should confirm data was sent");

    workflow:WorkflowExecutionInfo receiverResult = check workflow:getWorkflowResult(receiverWorkflowId, 60);
    test:assertEquals(receiverResult.status, "COMPLETED",
        "Receiver workflow should complete after receiving data");
    test:assertEquals(<string>receiverResult.result, "received:hello-from-sender",
        "Receiver should have received the correct message");
}
