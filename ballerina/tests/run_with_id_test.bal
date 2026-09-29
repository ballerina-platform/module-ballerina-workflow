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
import ballerina/time;
import ballerina/workflow.management;

// Caller-chosen instance ids: `runWithId`, `DurableAgent.runWithId` and
// `management:startInstance` share one start path, so the policy matrix is driven through
// each entry point against the in-memory engine.

@Workflow
function idQuickWorkflow(Context ctx, string input) returns string|error {
    return "done: " + input;
}

@Workflow
function idParkedWorkflow(Context ctx, string input, record {|future<string> go;|} events)
        returns string|error {
    string signal = check wait events.go;
    return input + "/" + signal;
}

@Workflow
function idFailingWorkflow(Context ctx, string input) returns string|error {
    return error("failing on purpose: " + input);
}

// One suffix per test process, so a rerun against a persistent server never meets its own ids.
final string idSuffix = time:utcNow()[0].toString();

isolated function chosenId(string label) returns string {
    return "id-" + label + "-" + idSuffix;
}

@test:BeforeSuite
function setupRunWithIdTests() returns error? {
    _ = check registerWorkflowForTest(idQuickWorkflow, "idQuickWorkflow");
    _ = check registerWorkflowForTest(idParkedWorkflow, "idParkedWorkflow");
    _ = check registerWorkflowForTest(idFailingWorkflow, "idFailingWorkflow");
}

// ── workflow:runWithId ────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testRunWithIdStartsUnderTheGivenId() returns error? {
    string id = chosenId("quick");
    string started = check runWithId(idQuickWorkflow, id, "a");
    test:assertEquals(started, id, "runWithId must return the id it was given");
    test:assertEquals(check getWorkflowResult(id, 15), "done: a");
}

@test:Config {groups: ["unit"]}
function testRunWithIdFailsWhileRunningByDefault() returns error? {
    string id = chosenId("fail-running");
    _ = check runWithId(idParkedWorkflow, id, "first");

    string|error second = runWithId(idParkedWorkflow, id, "second");
    test:assertTrue(second is InstanceAlreadyExistsError,
        "A held id refuses by default: " + (second is error ? second.message() : second));
    if second is InstanceAlreadyExistsError {
        test:assertEquals(second.detail().instanceId, id);
        test:assertEquals(second.detail().status, "RUNNING", "The detail says who holds the id");
    }

    check sendData(idParkedWorkflow, id, "go", "signal");
    test:assertEquals(check getWorkflowResult(id, 15), "first/signal",
        "The first instance is untouched by the refused start");
}

@test:Config {groups: ["unit"]}
function testRunWithIdUseExistingJoinsTheRunningInstance() returns error? {
    string id = chosenId("use-existing");
    _ = check runWithId(idParkedWorkflow, id, "first");

    // The idempotent submit: a retried request gets the instance it already started.
    string joined = check runWithId(idParkedWorkflow, id, "retried", ifRunning = USE_EXISTING);
    test:assertEquals(joined, id);
    management:WorkflowExecutionInfo info = check management:getWorkflowInfo(id);
    test:assertEquals(info.status, "RUNNING", "Joining changes nothing about the instance");

    check sendData(idParkedWorkflow, id, "go", "signal");
    test:assertEquals(check getWorkflowResult(id, 15), "first/signal",
        "The joined instance keeps its original input");
}

@test:Config {groups: ["unit"]}
function testRunWithIdAllowsANewRunAfterCloseByDefault() returns error? {
    string id = chosenId("allow-dup");
    _ = check runWithId(idQuickWorkflow, id, "one");
    test:assertEquals(check getWorkflowResult(id, 15), "done: one");

    string again = check runWithId(idQuickWorkflow, id, "two");
    test:assertEquals(again, id);
    test:assertEquals(check getWorkflowResult(id, 15), "done: two",
        "ALLOW_DUPLICATE starts a fresh run under the same id");
}

@test:Config {groups: ["unit"]}
function testRunWithIdRejectDuplicateRefusesAfterClose() returns error? {
    string id = chosenId("reject-dup");
    _ = check runWithId(idQuickWorkflow, id, "one");
    test:assertEquals(check getWorkflowResult(id, 15), "done: one");

    string|error again = runWithId(idQuickWorkflow, id, "two", ifClosed = REJECT_DUPLICATE);
    test:assertTrue(again is InstanceAlreadyExistsError, "REJECT_DUPLICATE uses an id once, ever");
    if again is InstanceAlreadyExistsError {
        test:assertEquals(again.detail().status, "COMPLETED");
    }
}

@test:Config {groups: ["unit"]}
function testRunWithIdAllowDuplicateFailedOnly() returns error? {
    string failedId = chosenId("failed-only-failed");
    _ = check runWithId(idFailingWorkflow, failedId, "x");
    anydata|error failed = getWorkflowResult(failedId, 15);
    test:assertTrue(failed is error, "The fixture must fail first");

    // A failed run may be replaced under the policy; the replacement's result is readable.
    string retried = check runWithId(idQuickWorkflow, failedId, "retry", ifClosed = ALLOW_DUPLICATE_FAILED_ONLY);
    test:assertEquals(retried, failedId);
    test:assertEquals(check getWorkflowResult(failedId, 15), "done: retry");

    string completedId = chosenId("failed-only-completed");
    _ = check runWithId(idQuickWorkflow, completedId, "one");
    test:assertEquals(check getWorkflowResult(completedId, 15), "done: one");
    string|error refused = runWithId(idQuickWorkflow, completedId, "two", ifClosed = ALLOW_DUPLICATE_FAILED_ONLY);
    test:assertTrue(refused is InstanceAlreadyExistsError,
        "A completed run is not replaced under ALLOW_DUPLICATE_FAILED_ONLY");
    if refused is InstanceAlreadyExistsError {
        test:assertEquals(refused.detail().status, "COMPLETED");
    }
}

@test:Config {groups: ["unit"]}
function testRunWithIdRejectsUnacceptableIds() {
    map<string> cases = {
        "": "blank",
        "   ": "blank",
        " padded": "whitespace",
        "humantask-x": "reserved prefix 'humantask-'",
        "reviewactivity-x": "reserved prefix 'reviewactivity-'",
        "childwf-x": "reserved prefix 'childwf-'",
        "childagent-x": "reserved prefix 'childagent-'"
    };
    foreach [string, string] [id, reason] in cases.entries() {
        string|error result = runWithId(idQuickWorkflow, id, "x");
        test:assertTrue(result is error && result !is InstanceAlreadyExistsError,
            string `'${id}' (${reason}) must be refused before any start`);
        if result is error {
            test:assertTrue(result.message().includes("instanceId"),
                "The refusal names the parameter: " + result.message());
        }
    }
    string tooLong = "x".'join(...from int _ in 0 ..< 257 select "");
    test:assertEquals(tooLong.length(), 256);
    string|error result = runWithId(idQuickWorkflow, tooLong, "x");
    test:assertTrue(result is error && result.message().includes("255"),
        "A 256-character id is refused and the limit is named");
}

@test:Config {groups: ["unit"]}
function testRunWithIdCountsCharactersNotUnits() returns error? {
    // 255 characters outside the BMP are 510 UTF-16 units: the limit counts characters, as
    // Ballerina's string length does.
    string wide = "".'join(...from int _ in 0 ..< 255 select "😀");
    test:assertEquals(wide.length(), 255);
    string id = chosenId("wide-") + wide.substring(0, 255 - chosenId("wide-").length());
    string started = check runWithId(idQuickWorkflow, id, "w");
    test:assertEquals(started, id);
    test:assertEquals(check getWorkflowResult(id, 15), "done: w");
}

// ── DurableAgent.runWithId ───────────────────────────────────────────────────

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testAgentRunWithId() returns error? {
    string id = chosenId("agent");
    string started = check runnerCoverageAgent.runWithId(id, "Is the laptop in stock?");
    test:assertEquals(started, id, "The agent instance id is the id the caller chose");
    string answer = check runnerCoverageAgent.waitForResult(id);
    test:assertEquals(answer, "Stock check result: laptop is in stock");

    string|error rejected = runnerCoverageAgent.runWithId(id, "again", ifClosed = REJECT_DUPLICATE);
    test:assertTrue(rejected is InstanceAlreadyExistsError, "The agent honours the closed-id policy");
    if rejected is InstanceAlreadyExistsError {
        test:assertEquals(rejected.detail().status, "COMPLETED");
    }

    string again = check runnerCoverageAgent.runWithId(id, "again");
    test:assertEquals(again, id, "ALLOW_DUPLICATE starts the agent again under the same id");
    string _ = check runnerCoverageAgent.waitForResult(id);

    string|error blank = runnerCoverageAgent.runWithId("", "x");
    test:assertTrue(blank is error && blank !is InstanceAlreadyExistsError, "A blank agent id is refused");
}

// ── management:startInstance ─────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testStartInstanceWithChosenId() returns error? {
    string id = chosenId("mgmt");
    management:WorkflowHandle h = check management:startInstance("idQuickWorkflow", "one", instanceId = id);
    test:assertEquals(h.workflowId, id);
    test:assertTrue(h.started, "A fresh start reports started");
    test:assertEquals(check getWorkflowResult(id, 15), "done: one");

    // Without an id the generated one is a UUID, as before.
    management:WorkflowHandle generated = check management:startInstance("idQuickWorkflow", "two");
    test:assertNotEquals(generated.workflowId, id);
    test:assertTrue(generated.started);
    _ = check getWorkflowResult(generated.workflowId, 15);
}

@test:Config {groups: ["unit"]}
function testStartInstanceRunningPolicies() returns error? {
    string id = chosenId("mgmt-running");
    management:WorkflowHandle first = check management:startInstance("idParkedWorkflow", "first", instanceId = id);

    management:WorkflowHandle|error refused = management:startInstance("idParkedWorkflow", "second",
        instanceId = id);
    test:assertTrue(refused is management:ConflictError,
        "FAIL is a conflict: " + (refused is error ? refused.message() : "no error"));

    management:WorkflowHandle joined = check management:startInstance("idParkedWorkflow", "second",
        instanceId = id, ifRunning = management:USE_EXISTING);
    test:assertEquals(joined.runId, first.runId, "USE_EXISTING hands back the run holding the id");
    test:assertFalse(joined.started, "and says it created nothing");

    management:WorkflowHandle replaced = check management:startInstance("idParkedWorkflow", "third",
        instanceId = id, ifRunning = management:TERMINATE_EXISTING);
    test:assertNotEquals(replaced.runId, first.runId, "TERMINATE_EXISTING starts a new run");
    test:assertTrue(replaced.started);
    management:WorkflowExecutionInfo old = check management:getWorkflowInfoForRun(id, first.runId);
    test:assertEquals(old.status, "TERMINATED", "and the old run is terminated");

    check sendData(idParkedWorkflow, id, "go", "signal");
    test:assertEquals(check getWorkflowResult(id, 15), "third/signal");
}

@test:Config {groups: ["unit"]}
function testStartInstanceClosedPolicies() returns error? {
    string id = chosenId("mgmt-closed");
    _ = check management:startInstance("idQuickWorkflow", "one", instanceId = id);
    test:assertEquals(check getWorkflowResult(id, 15), "done: one");

    management:WorkflowHandle|error rejected = management:startInstance("idQuickWorkflow", "two",
        instanceId = id, ifClosed = management:REJECT_DUPLICATE);
    test:assertTrue(rejected is management:ConflictError, "REJECT_DUPLICATE after a close is a conflict");
    management:WorkflowHandle|error failedOnly = management:startInstance("idQuickWorkflow", "two",
        instanceId = id, ifClosed = management:ALLOW_DUPLICATE_FAILED_ONLY);
    test:assertTrue(failedOnly is management:ConflictError,
        "ALLOW_DUPLICATE_FAILED_ONLY does not replace a completed run");

    management:WorkflowHandle again = check management:startInstance("idQuickWorkflow", "two", instanceId = id);
    test:assertTrue(again.started, "ALLOW_DUPLICATE starts a new run");
    test:assertEquals(check getWorkflowResult(id, 15), "done: two");
}

@test:Config {groups: ["unit"]}
function testStartInstanceRejectsUnacceptableIds() {
    management:WorkflowHandle|error blank = management:startInstance("idQuickWorkflow", "x", instanceId = "");
    test:assertTrue(blank is management:InvalidRequestError, "A blank id is a bad request, not a failure");
    management:WorkflowHandle|error reserved = management:startInstance("idQuickWorkflow", "x",
        instanceId = "childwf-x");
    test:assertTrue(reserved is management:InvalidRequestError);
}

@test:Config {groups: ["unit"]}
function testDeprecatedStartWorkflowByTypeStillTakesAnId() returns error? {
    string id = chosenId("legacy");
    management:WorkflowHandle h = check management:startWorkflowByType("idQuickWorkflow", "one", id);
    test:assertEquals(h.workflowId, id);
    test:assertEquals(check getWorkflowResult(id, 15), "done: one");
}

// ── instances.start command ───────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandStartInstancePolicies() returns error? {
    string id = chosenId("cmd");
    map<json> first = check commandPayload(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "first", workflowId: id});
    test:assertEquals(first["workflowId"], id);
    test:assertEquals(first["started"], true);

    json|management:Error held = runCommand(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "second", workflowId: id});
    test:assertTrue(held is management:Error && management:errorCodeOf(held) == management:CONFLICT,
        "A held id the default policy refuses is CONFLICT (409 on the wire)");

    map<json> joined = check commandPayload(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "second", workflowId: id, ifRunning: "USE_EXISTING"});
    test:assertEquals(joined["runId"], first["runId"]);
    test:assertEquals(joined["started"], false, "A joined start says so, so the route answers 200");

    json|management:Error badPolicy = runCommand(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "x", workflowId: id, ifRunning: "MAYBE"});
    test:assertTrue(badPolicy is management:Error
        && management:errorCodeOf(badPolicy) == management:INVALID_REQUEST, "An unknown policy is a bad request");
    json|management:Error badClosed = runCommand(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "x", workflowId: id, ifClosed: "NEVER"});
    test:assertTrue(badClosed is management:Error
        && management:errorCodeOf(badClosed) == management:INVALID_REQUEST);
    json|management:Error badId = runCommand(management:START_INSTANCE,
        {workflowType: "idParkedWorkflow", input: "x", workflowId: 42});
    test:assertTrue(badId is management:Error && management:errorCodeOf(badId) == management:INVALID_REQUEST,
        "A non-string workflowId is refused rather than silently ignored");

    check sendData(idParkedWorkflow, id, "go", "signal");
    test:assertEquals(check getWorkflowResult(id, 15), "first/signal");
}
