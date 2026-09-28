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

import ballerina/test;
import ballerina/workflow.management;

// The client-side reads — getResult, waitForResult, getStatus — answer one error vocabulary
// for workflows and durable agents alike: WorkflowInProgressError while running or when a
// bounded wait runs out, InstanceFailedError when closed without a result,
// InstanceNotFoundError for an id nothing holds.

@Workflow
function readQuickWorkflow(Context ctx, string input) returns string|error {
    return "done: " + input;
}

@Workflow
function readParkedWorkflow(Context ctx, string input, record {|future<string> go;|} events)
        returns string|error {
    string signal = check wait events.go;
    return input + "/" + signal;
}

@Workflow
function readFailingWorkflow(Context ctx, string input) returns string|error {
    return error("read fixture failed: " + input);
}

@test:BeforeSuite
function setupResultReadTests() returns error? {
    _ = check registerWorkflowForTest(readQuickWorkflow, "readQuickWorkflow");
    _ = check registerWorkflowForTest(readParkedWorkflow, "readParkedWorkflow");
    _ = check registerWorkflowForTest(readFailingWorkflow, "readFailingWorkflow");
}

@test:Config {groups: ["unit"]}
function testGetResultWhileRunningIsInProgress() returns error? {
    string id = check run(readParkedWorkflow, "a");

    string|error read = getResult(id);
    test:assertTrue(read is WorkflowInProgressError, "A running instance answers in-progress, not a failure");
    test:assertTrue(read is WorkflowBusyError, "The deprecated alias still matches the same error");
    test:assertEquals(check getStatus(id), RUNNING);

    check sendData(readParkedWorkflow, id, "go", "b");
    string result = check waitForResult(id);
    test:assertEquals(result, "a/b", "waitForResult returns the typed result");
    test:assertEquals(check getStatus(id), COMPLETED);
    string again = check getResult(id);
    test:assertEquals(again, "a/b", "Once closed, the non-blocking read returns the same result");
}

@test:Config {groups: ["unit"]}
function testWaitForResultBoundedAnswersInProgress() returns error? {
    string id = check run(readParkedWorkflow, "a");

    string|error bounded = waitForResult(id, {seconds: 1});
    test:assertTrue(bounded is WorkflowInProgressError,
        "A bounded wait the instance outlives answers in-progress, the same as getResult");

    check sendData(readParkedWorkflow, id, "go", "b");
    string result = check waitForResult(id, {seconds: 30});
    test:assertEquals(result, "a/b");
}

@test:Config {groups: ["unit"]}
function testGetResultOfFailedInstance() returns error? {
    string id = check run(readFailingWorkflow, "x");
    string|error waited = waitForResult(id);
    test:assertTrue(waited is InstanceFailedError, "A failed instance answers InstanceFailedError");
    if waited is InstanceFailedError {
        test:assertEquals(waited.detail().status, FAILED);
        test:assertEquals(waited.detail().instanceId, id);
        test:assertTrue(waited.message().includes("read fixture failed: x"),
            "The instance's own error message is carried: " + waited.message());
    }
    test:assertEquals(check getStatus(id), FAILED);
    string|error read = getResult(id);
    test:assertTrue(read is InstanceFailedError, "The non-blocking read agrees");
}

@test:Config {groups: ["unit"]}
function testGetResultOfTerminatedInstance() returns error? {
    string id = check run(readParkedWorkflow, "a");
    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(id);
    test:assertEquals(info.status, "RUNNING");
    check management:terminateWorkflow(id, "", "test");

    string|error read = waitForResult(id, {seconds: 10});
    test:assertTrue(read is InstanceFailedError, "A terminated instance closed without a result");
    if read is InstanceFailedError {
        test:assertEquals(read.detail().status, TERMINATED);
    }
    test:assertEquals(check getStatus(id), TERMINATED);
}

@test:Config {groups: ["unit"]}
function testReadsOfUnknownInstance() {
    string|error read = getResult("no-such-instance");
    test:assertTrue(read is InstanceNotFoundError, "An unknown id is not found, not a failure");
    if read is InstanceNotFoundError {
        test:assertEquals(read.detail().instanceId, "no-such-instance");
    }
    string|error waited = waitForResult("no-such-instance", {seconds: 1});
    test:assertTrue(waited is InstanceNotFoundError);
    test:assertTrue(getStatus("no-such-instance") is InstanceNotFoundError);
}

@test:Config {groups: ["unit"]}
function testDeprecatedGetWorkflowResultAnswersInProgressOnTimeout() returns error? {
    string id = check run(readParkedWorkflow, "a");
    anydata|error result = getWorkflowResult(id, 1);
    test:assertTrue(result is WorkflowInProgressError,
        "The deprecated read reports a wait that ran out as in-progress, not as a timeout failure");
    check sendData(readParkedWorkflow, id, "go", "b");
    test:assertEquals(check getWorkflowResult(id, 15), "a/b");
}

// ── Durable agents: the same vocabulary ───────────────────────────────────────

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testAgentReadsShareTheVocabulary() returns error? {
    string id = check runnerCoverageAgent.run("Is the laptop in stock?");
    string answer = check runnerCoverageAgent.waitForResult(id, {seconds: 30});
    test:assertEquals(answer, "Stock check result: laptop is in stock");
    test:assertEquals(check runnerCoverageAgent.getStatus(id), COMPLETED);
    string again = check runnerCoverageAgent.getResult(id);
    test:assertEquals(again, answer);

    // A workflow's id is not one of this agent's instances.
    string workflowId = check run(readQuickWorkflow, "w");
    string _ = check waitForResult(workflowId);
    string|error foreign = runnerCoverageAgent.getResult(workflowId);
    test:assertTrue(foreign is InstanceNotFoundError, "An agent's read is scoped to its own instances");
    test:assertTrue(runnerCoverageAgent.getStatus(workflowId) is InstanceNotFoundError);
    test:assertTrue(runnerCoverageAgent.getStatus("no-such-agent") is InstanceNotFoundError);
    string|error unknown = runnerCoverageAgent.getResult("no-such-agent");
    test:assertTrue(unknown is InstanceNotFoundError);
}
