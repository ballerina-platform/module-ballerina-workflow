// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// The caller-chosen id policy matrix against a real Temporal server: what the in-memory
// suite asserts about the shared start path must hold where the id policies are the
// server's own.

@test:Config {groups: ["integration"]}
function testRunWithIdRunningPoliciesOnServer() returns error? {
    string id = uniqueId("run-with-id");
    SimpleSignalInput input = {id: id, message: "first"};
    string started = check workflow:runWithId(simpleSignalWorkflow, id, input);
    test:assertEquals(started, id);

    string|error refused = workflow:runWithId(simpleSignalWorkflow, id, input);
    test:assertTrue(refused is workflow:InstanceAlreadyExistsError, "FAIL refuses a running holder");
    if refused is workflow:InstanceAlreadyExistsError {
        test:assertEquals(refused.detail().status, "RUNNING");
    }

    string joined = check workflow:runWithId(simpleSignalWorkflow, id, input,
        ifRunning = workflow:USE_EXISTING);
    test:assertEquals(joined, id, "USE_EXISTING joins the running instance");

    // As the signal tests do: let the run reach its wait before the signal arrives.
    runtime:sleep(1);
    SimpleSignalData signal = {id: id, response: "ok"};
    check workflow:sendData(simpleSignalWorkflow, id, "response", signal);
    anydata result = check workflow:getWorkflowResult(id, 30);
    SimpleSignalResult typed = check result.cloneWithType();
    test:assertEquals(typed.originalMessage, "first", "The joined instance kept its first input");
}

@test:Config {groups: ["integration"]}
function testRunWithIdClosedPoliciesOnServer() returns error? {
    string id = uniqueId("run-with-id-closed");
    InfoTestInput input = {id: id, name: "Closed"};
    _ = check workflow:runWithId(infoTestWorkflow, id, input);
    _ = check workflow:getWorkflowResult(id, 30);

    string|error rejected = workflow:runWithId(infoTestWorkflow, id, input, ifClosed = workflow:REJECT_DUPLICATE);
    test:assertTrue(rejected is workflow:InstanceAlreadyExistsError, "REJECT_DUPLICATE refuses after a close");
    if rejected is workflow:InstanceAlreadyExistsError {
        test:assertEquals(rejected.detail().status, "COMPLETED");
    }
    string|error failedOnly = workflow:runWithId(infoTestWorkflow, id, input,
        ifClosed = workflow:ALLOW_DUPLICATE_FAILED_ONLY);
    test:assertTrue(failedOnly is workflow:InstanceAlreadyExistsError,
        "ALLOW_DUPLICATE_FAILED_ONLY refuses after a completion");

    string again = check workflow:runWithId(infoTestWorkflow, id, input);
    test:assertEquals(again, id, "ALLOW_DUPLICATE starts a new run under the id");
    _ = check workflow:getWorkflowResult(id, 30);

    // A failed holder is replaced under ALLOW_DUPLICATE_FAILED_ONLY.
    string failedId = uniqueId("run-with-id-failed");
    RetryActivityInput retryInput = {id: failedId, mode: "fail"};
    _ = check workflow:runWithId(retryDefaultFailWorkflow, failedId, retryInput);
    anydata|error failed = workflow:getWorkflowResult(failedId, 30);
    test:assertTrue(failed is error, "The fixture must fail first");
    string replaced = check workflow:runWithId(retryDefaultFailWorkflow, failedId, retryInput,
        ifClosed = workflow:ALLOW_DUPLICATE_FAILED_ONLY);
    test:assertEquals(replaced, failedId);
    anydata|error replacedRun = workflow:getWorkflowResult(failedId, 30);
    test:assertTrue(replacedRun is error, "The replacement run of the failing fixture fails again");
}

@test:Config {groups: ["integration"]}
function testAgentRunWithIdOnServer() returns error? {
    string id = uniqueId("agent-with-id");
    string started = check openInputAgent.runWithId(id, "Say hello");
    test:assertEquals(started, id);
    string _ = check openInputAgent.waitForResult(id);

    string|error rejected = openInputAgent.runWithId(id, "Say hello", ifClosed = workflow:REJECT_DUPLICATE);
    test:assertTrue(rejected is workflow:InstanceAlreadyExistsError, "The agent honours the closed-id policy");
    string again = check openInputAgent.runWithId(id, "Say hello again");
    test:assertEquals(again, id);
    string _ = check openInputAgent.waitForResult(id);
}

@test:Config {groups: ["integration"]}
function testStartInstancePoliciesOnServer() returns error? {
    string id = uniqueId("start-instance");
    json input = {id: id, message: "first"};
    management:WorkflowHandle first = check management:startInstance("simpleSignalWorkflow", input,
        instanceId = id);
    test:assertTrue(first.started);

    management:WorkflowHandle|error refused = management:startInstance("simpleSignalWorkflow", input,
        instanceId = id);
    test:assertTrue(refused is management:ConflictError, "FAIL is a conflict");

    management:WorkflowHandle joined = check management:startInstance("simpleSignalWorkflow", input,
        instanceId = id, ifRunning = management:USE_EXISTING);
    test:assertEquals(joined.runId, first.runId);
    test:assertFalse(joined.started);

    management:WorkflowHandle replaced = check management:startInstance("simpleSignalWorkflow",
        {id: id, message: "third"}, instanceId = id, ifRunning = management:TERMINATE_EXISTING);
    test:assertNotEquals(replaced.runId, first.runId);
    management:WorkflowExecutionInfo old = check management:getWorkflowInfoForRun(id, first.runId);
    test:assertEquals(old.status, "TERMINATED", "TERMINATE_EXISTING closes the old run");

    runtime:sleep(1);
    SimpleSignalData signal = {id: id, response: "ok"};
    check workflow:sendData(simpleSignalWorkflow, id, "response", signal);
    anydata result = check workflow:getWorkflowResult(id, 30);
    SimpleSignalResult typed = check result.cloneWithType();
    test:assertEquals(typed.originalMessage, "third", "The replacement run carries the new input");

    management:WorkflowHandle|error rejectedAfterClose = management:startInstance("simpleSignalWorkflow", input,
        instanceId = id, ifClosed = management:REJECT_DUPLICATE);
    test:assertTrue(rejectedAfterClose is management:ConflictError);
}
