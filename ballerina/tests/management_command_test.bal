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

// ============================================================================
// Management commands — end-to-end integration tests
// ============================================================================
//
// Every `management:Operation` exercised through `management:executeCommand`
// against the in-process IN_MEMORY runtime (tests/Config.toml), which the module
// starts on demand — no external server is involved. These complement the dispatch
// and parameter-contract unit tests in modules/management/tests/command_test.bal:
// here the operations actually run, so the payload each one returns is checked
// against a real instance, human task, or registry.
//
// Nothing here skips on error. With the runtime in-process there is no "server
// unavailable" condition to absorb, so a management error fails the test and an
// operation whose target has not appeared yet is polled to a bounded deadline.
//
// One environmental limit is real and is handled explicitly rather than silently:
// the embedded test server implements no visibility API, so the four listing
// operations (`instances.list`, `humanTasks.list`, `humanTasks.pendingCount`,
// `reviewActivities.list`) cannot answer here — Temporal rejects
// ListWorkflowExecutions as UNIMPLEMENTED. `assertPageOrUnsupported` pins what is
// still verifiable for them: the command dispatches, reaches the runtime, and any
// failure is classified as an `ExecutionError` rather than mistaken for a bad
// request or a missing target. Their page shapes are covered by the pagination and
// listing tests in modules/management/tests and by the REST suite.
//
// Fixtures are registered once in this file's @BeforeSuite, under the same name
// `run` derives from the function pointer (`workflow-<functionName>`), since a
// registration under any other name is invisible to `run`.
// ============================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.management;

// A caller with roles, since instance-detail and task operations are role-gated.
final management:Identity & readonly cmdIdentity = {userId: "alice", roles: ["APPROVER", "OPS"]};

type CmdDecision record {|
    boolean approved;
    string comment;
|};

// The fixtures take a single record input: a workflow's input value is bound
// positionally, so the whole value a command or `run` carries becomes the argument.
type CmdOrder record {|
    string reference;
|};

// Completes immediately — used for the definition, start, and inspection commands.
@Workflow
function cmdSimpleWorkflow(Context ctx, CmdOrder request) returns string|error {
    return "handled: " + request.reference;
}

// Takes a bare string, so `instances.start` is exercised with an input that is not an
// object: a workflow's input is bound to its declared parameter, whatever its type.
@Workflow
function cmdScalarInputWorkflow(Context ctx, string reference) returns string|error {
    return "scalar: " + reference;
}

// Parks on a human task, so the task commands have something to act on.
@Workflow
function cmdHumanTaskWorkflow(Context ctx, CmdOrder request) returns CmdDecision|error {
    CmdDecision decision = check ctx->awaitHumanTask("cmdApprove", {"reference": request.reference}, userRoles = "APPROVER");
    return decision;
}

// Parks on a human task and reports what a rejection delivered, so `humanTasks.fail` can
// be verified on the values the workflow actually received rather than on the command's
// acknowledgement. Returning the detail as data keeps the assertions in the test.
@Workflow
function cmdRejectableWorkflow(Context ctx, CmdOrder request) returns map<json>|error {
    CmdDecision|HumanTaskError decision = ctx->awaitHumanTask("cmdReject", {"reference": request.reference}, userRoles = "APPROVER");
    if decision is HumanTaskRejectedError {
        HumanTaskRejectedDetail rejection = decision.detail();
        return {
            outcome: "rejected",
            reason: rejection.reason,
            details: rejection.details,
            rejectedBy: rejection.rejectedBy
        };
    }
    if decision is HumanTaskError {
        return {outcome: "failed", reason: decision.message()};
    }
    return {outcome: "completed"};
}

// Parks on an event, so the lifecycle commands act on a genuinely running instance.
// A timer would not do: the embedded server skips time whenever every worker is idle,
// so `ctx.sleep` returns at once and the instance is already closed by the time a
// suspend or terminate command reaches it.
@Workflow
function cmdParkedWorkflow(Context ctx, CmdOrder request, record {|future<string> go;|} events)
        returns string|error {
    string signal = check wait events.go;
    return signal;
}

@test:BeforeSuite
function setupCommandTests() returns error? {
    // Registered under the function's own name: `run` resolves a pointer to
    // `workflow-<functionName>`, and `instances.start` is given the same name as its
    // `workflowType`. An alias would leave both unable to find the workflow.
    _ = check registerWorkflowForTest(cmdSimpleWorkflow, "cmdSimpleWorkflow");
    _ = check registerWorkflowForTest(cmdScalarInputWorkflow, "cmdScalarInputWorkflow");
    _ = check registerWorkflowForTest(cmdHumanTaskWorkflow, "cmdHumanTaskWorkflow");
    _ = check registerWorkflowForTest(cmdRejectableWorkflow, "cmdRejectableWorkflow");
    _ = check registerWorkflowForTest(cmdParkedWorkflow, "cmdParkedWorkflow");
}

isolated function runCommand(management:Operation operation, map<json> params = {})
        returns json|management:Error =>
    management:executeCommand({operation: operation, params: params, identity: cmdIdentity});

// The object payload of a command. A management error fails the calling test, as does
// an operation that answers with something other than a JSON object.
isolated function commandPayload(management:Operation operation, map<json> params = {})
        returns map<json>|error {
    json result = check runCommand(operation, params);
    return result.ensureType();
}

// Asserts what a listing operation must satisfy in an environment without a
// visibility API: either a well-formed page, or a failure the operation classified as
// an execution failure. A `NotFoundError`, `InvalidRequestError`, or `AccessDeniedError`
// here would mean the command layer misread the request rather than the server
// refusing to serve it, so those fail the test.
isolated function assertPageOrUnsupported(management:Operation operation, map<json> params,
        string... expectedKeys) returns error? {
    json|management:Error result = runCommand(operation, params);
    if result is management:Error {
        test:assertTrue(result is management:ExecutionError,
            operation.toString() + " must reach the runtime and report an execution failure, got: " +
            result.message());
        return;
    }
    map<json> page = check result.ensureType();
    foreach string key in expectedKeys {
        test:assertTrue(page.hasKey(key), operation.toString() + " must return a '" + key + "' field");
    }
}

// The first pending human task of a workflow, polled until it becomes visible.
// Discovery goes through `listPendingHumanTasks`, which queries the parent workflow
// directly; `humanTasks.list` would need the visibility API. A task that never
// appears is a registration or routing regression, so the deadline fails the test.
isolated function awaitFirstPendingTaskId(string workflowId) returns string|error {
    foreach int i in 0 ..< 20 {
        management:HumanTaskGroup[] groups = check management:listPendingHumanTasks(workflowId);
        foreach management:HumanTaskGroup taskGroup in groups {
            if taskGroup.taskIds.length() > 0 {
                return taskGroup.taskIds[0];
            }
        }
        runtime:sleep(1);
    }
    return error("No pending human task became visible for workflow " + workflowId);
}

// Polls an instance until it reports the expected status. A lifecycle command's
// acknowledgement only says the request was accepted, so a test that stops there passes
// even when the instance never reaches the state it asked for. The deadline fails.
isolated function awaitInstanceStatus(string workflowId, string expected) returns error? {
    string last = "";
    foreach int i in 0 ..< 20 {
        map<json> instance = check commandPayload(management:GET_INSTANCE, {workflowId: workflowId});
        last = instance["status"].toString();
        if last == expected {
            return;
        }
        runtime:sleep(1);
    }
    return error(string `Instance ${workflowId} never reached ${expected}; last status was ${last}`);
}

// Terminates an instance a test left running. Best effort: an instance that already
// closed reports an error, which is not a test failure.
isolated function cleanupInstance(string workflowId) {
    json|management:Error terminated = runCommand(management:TERMINATE_INSTANCE,
            {workflowId: workflowId, reason: "test cleanup"});
    if terminated is management:Error {
        // Already closed — nothing left to clean up.
    }
}

// ── definitions.list ─────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandListDefinitions() returns error? {
    map<json> payload = check commandPayload(management:LIST_DEFINITIONS);
    json[] definitions = check payload["definitions"].ensureType();
    test:assertTrue(definitions.length() > 0, "Registered workflows must be listed");

    boolean found = false;
    foreach json definition in definitions {
        map<json> entry = check definition.ensureType();
        if entry["workflowType"] == "cmdSimpleWorkflow" {
            found = true;
            test:assertEquals(entry["kind"], "WORKFLOW");
        }
    }
    test:assertTrue(found, "The workflow registered by this suite must appear in definitions.list");
}

// ── instances.start / get / list / history / activityTree / executionGraph ────

@test:Config {groups: ["unit"]}
function testCommandStartAndInspectInstance() returns error? {
    // `input` is the workflow's input value, bound to its declared parameter.
    map<json> startHandle = check commandPayload(management:START_INSTANCE, {
        workflowType: "cmdSimpleWorkflow",
        input: {reference: "payload-1"}
    });
    string workflowId = check startHandle["workflowId"].ensureType();
    test:assertTrue(workflowId.length() > 0, "instances.start must return the new workflow ID");

    anydata result = check getWorkflowResult(workflowId, 15);
    test:assertEquals(result, "handled: payload-1",
        "instances.start must run the workflow with the input the command carried");

    // instances.get
    map<json> instance = check commandPayload(management:GET_INSTANCE, {workflowId: workflowId});
    test:assertEquals(instance["workflowId"], workflowId);
    test:assertEquals(instance["status"], "COMPLETED");

    // instances.history
    map<json> history = check commandPayload(management:GET_INSTANCE_HISTORY, {workflowId: workflowId});
    json[] events = check history["events"].ensureType();
    test:assertTrue(events.length() > 0, "A completed instance must have history events");

    // instances.activityTree
    map<json> tree = check commandPayload(management:GET_INSTANCE_ACTIVITY_TREE,
            {workflowId: workflowId});
    test:assertTrue(tree.hasKey("nodes"), "instances.activityTree must return a nodes array");

    // instances.executionGraph
    map<json> graph = check commandPayload(management:GET_INSTANCE_EXECUTION_GRAPH,
            {workflowId: workflowId});
    test:assertTrue(graph.hasKey("nodes") || graph.hasKey("edges"),
        "instances.executionGraph must return a graph");

    // instances.list — visibility-backed, see assertPageOrUnsupported.
    check assertPageOrUnsupported(management:LIST_INSTANCES,
            {workflowId: workflowId, 'limit: 20}, "items", "hasMore");
}

@test:Config {groups: ["unit"]}
function testCommandStartInstanceWithScalarInput() returns error? {
    // A workflow input is not always a record. `POST /workflows` accepted any JSON here
    // before the REST resources were routed through commands, so rejecting a scalar
    // would have narrowed the HTTP contract as well as the command surface.
    map<json> startHandle = check commandPayload(management:START_INSTANCE, {
        workflowType: "cmdScalarInputWorkflow",
        input: "payload-2"
    });
    string workflowId = check startHandle["workflowId"].ensureType();

    anydata result = check getWorkflowResult(workflowId, 15);
    test:assertEquals(result, "scalar: payload-2",
        "A scalar input must reach the workflow's parameter unchanged");
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownInstanceIsNotFound() {
    json|management:Error result = runCommand(management:GET_INSTANCE,
            {workflowId: "wf-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown instance must be a NotFoundError, got: " + result.message());
    } else {
        test:assertFail("An unknown instance must fail, got payload: " + result.toJsonString());
    }
}

// ── humanTasks.list / pendingCount / get / complete ───────────────────────────

@test:Config {groups: ["unit"]}
function testCommandHumanTaskListGetAndComplete() returns error? {
    CmdOrder input = {reference: "ORD-CMD-001"};
    string workflowId = check run(cmdHumanTaskWorkflow, input);
    string taskId = check awaitFirstPendingTaskId(workflowId);

    // humanTasks.list / humanTasks.pendingCount — visibility-backed.
    check assertPageOrUnsupported(management:LIST_HUMAN_TASKS,
            {status: "PENDING", parentWorkflowId: workflowId, 'limit: 20}, "items", "hasMore");
    check assertPageOrUnsupported(management:COUNT_PENDING_HUMAN_TASKS, {}, "count");

    // humanTasks.get
    map<json> task = check commandPayload(management:GET_HUMAN_TASK, {taskId: taskId});
    test:assertEquals(task["taskId"], taskId);
    test:assertEquals(task["parentWorkflowId"], workflowId);

    // humanTasks.complete — and the workflow receives the value.
    CmdDecision expected = {approved: true, comment: "via command"};
    map<json> completion = check commandPayload(management:COMPLETE_HUMAN_TASK,
            {taskId: taskId, result: expected.toJson()});
    test:assertEquals(completion["success"], true);
    test:assertEquals(completion["completedBy"], "alice",
        "The command identity must be recorded as the completing user");

    // The result crosses Temporal as JSON, so it arrives as a `map<anydata>` and needs
    // converting rather than casting.
    anydata wfResult = check getWorkflowResult(workflowId, 15);
    CmdDecision decision = check wfResult.cloneWithType();
    test:assertEquals(decision, expected, "The workflow must receive what the command submitted");
}

// ── humanTasks.fail ──────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandFailHumanTask() returns error? {
    CmdOrder input = {reference: "ORD-CMD-002"};
    string workflowId = check run(cmdRejectableWorkflow, input);
    string taskId = check awaitFirstPendingTaskId(workflowId);

    string reason = "rejected by the operator";
    map<json> details = {"code": "MANUAL_REJECT", "reviewer": "alice"};
    map<json> outcome = check commandPayload(management:FAIL_HUMAN_TASK, {
        taskId: taskId,
        reason: reason,
        details: details
    });
    test:assertEquals(outcome["success"], true);
    test:assertEquals(outcome["completedBy"], "alice");

    // The command's own acknowledgement is not evidence that the rejection was delivered:
    // humanTasks.fail once reported success having dropped the reason and the details on the
    // way to the runtime, and asserting only that the workflow errored would pass for any
    // failure at all. The fixture returns what it received, so both submitted values are
    // checked where the workflow saw them.
    anydata wfResult = check getWorkflowResult(workflowId, 15);
    map<json> received = check wfResult.toJson().ensureType();
    test:assertEquals(received["outcome"], "rejected",
            "The awaiting workflow must observe a rejection, got: " + received.toJsonString());
    test:assertEquals(received["reason"], reason, "The submitted reason must reach the workflow");
    test:assertEquals(received["details"], details, "The submitted details must reach the workflow");
    test:assertEquals(received["rejectedBy"], "alice",
            "The rejecting user must reach the workflow");

    // The task itself must record the rejection as terminal, not merely accept it.
    map<json> task = check commandPayload(management:GET_HUMAN_TASK, {taskId: taskId});
    test:assertEquals(task["status"], "FAILED",
            "A rejected task must close as FAILED, got: " + task["status"].toString());
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownHumanTaskIsNotFound() {
    json|management:Error result = runCommand(management:GET_HUMAN_TASK,
            {taskId: "humantask-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown task must be a NotFoundError, got: " + result.message());
    } else {
        test:assertFail("An unknown task must fail, got payload: " + result.toJsonString());
    }
}

// ── instances.suspend / resume / wake ────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandSuspendAndResumeInstance() returns error? {
    CmdOrder input = {reference: "ORD-CMD-SUSPEND"};
    string workflowId = check run(cmdParkedWorkflow, input);
    runtime:sleep(2);

    map<json> suspendResult = check commandPayload(management:SUSPEND_INSTANCE,
            {workflowId: workflowId});
    test:assertEquals(suspendResult["success"], true, "instances.suspend must acknowledge");

    map<json> resumeResult = check commandPayload(management:RESUME_INSTANCE,
            {workflowId: workflowId});
    test:assertEquals(resumeResult["success"], true, "instances.resume must acknowledge");

    // Leave nothing running behind.
    cleanupInstance(workflowId);
}

@test:Config {groups: ["unit"]}
function testCommandWakeInstanceDispatches() returns error? {
    CmdOrder input = {reference: "ORD-CMD-WAKE"};
    string workflowId = check run(cmdParkedWorkflow, input);
    runtime:sleep(1);

    // Waking targets a durable agent's built-in sleep; on a plain workflow the signal
    // is harmless. Either outcome is fine — what matters is that the command reaches
    // the runtime rather than being rejected as malformed.
    json|management:Error result = runCommand(management:WAKE_INSTANCE, {workflowId: workflowId});
    if result is management:Error {
        test:assertFalse(result is management:InvalidRequestError,
            "instances.wake must reach the runtime, got: " + result.message());
    }
    cleanupInstance(workflowId);
}

// ── instances.terminate / cancel ─────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandTerminateInstance() returns error? {
    CmdOrder input = {reference: "ORD-CMD-TERMINATE"};
    string workflowId = check run(cmdParkedWorkflow, input);
    runtime:sleep(2);

    map<json> result = check commandPayload(management:TERMINATE_INSTANCE,
            {workflowId: workflowId, reason: "no longer needed"});
    test:assertEquals(result["success"], true, "instances.terminate must acknowledge");

    runtime:sleep(1);
    map<json> instance = check commandPayload(management:GET_INSTANCE, {workflowId: workflowId});
    test:assertEquals(instance["status"], "TERMINATED",
        "A terminated instance must report status TERMINATED");
}

@test:Config {groups: ["unit"]}
function testCommandCancelInstance() returns error? {
    CmdOrder input = {reference: "ORD-CMD-CANCEL"};
    string workflowId = check run(cmdParkedWorkflow, input);
    runtime:sleep(2);

    map<json> result = check commandPayload(management:CANCEL_INSTANCE, {workflowId: workflowId});
    test:assertEquals(result["success"], true, "instances.cancel must acknowledge");

    // Cancellation is cooperative, so the acknowledgement only says the request was
    // delivered — an instance that ignores it would still pass. Require the instance to
    // actually close as canceled, and terminate it if it never does: a parked instance
    // left running would otherwise outlive the suite.
    error? closed = awaitInstanceStatus(workflowId, "CANCELED");
    if closed is error {
        cleanupInstance(workflowId);
        test:assertFail(closed.message());
    }
}

// ── reviewActivities.list / get / decide ─────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandListReviewActivities() returns error? {
    // Visibility-backed: a page here must be well-formed even when nothing is pending.
    check assertPageOrUnsupported(management:LIST_REVIEW_ACTIVITIES,
            {status: "PENDING", 'limit: 20}, "items", "hasMore");
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownReviewActivityIsNotFound() {
    json|management:Error result = runCommand(management:GET_REVIEW_ACTIVITY,
            {taskId: "reviewactivity-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown review activity must be a NotFoundError, got: " + result.message());
    } else {
        test:assertFail("An unknown review activity must fail, got payload: " +
                result.toJsonString());
    }
}

@test:Config {groups: ["unit"]}
function testCommandDecideUnknownReviewActivityIsNotFound() {
    json|management:Error result = runCommand(management:DECIDE_REVIEW_ACTIVITY,
            {taskId: "reviewactivity-does-not-exist-0002", action: "proceed"});
    if result is management:Error {
        // The decision is well-formed, so it must pass validation and be rejected by the
        // runtime as a missing target rather than as a malformed request.
        test:assertTrue(result is management:NotFoundError,
            "An unknown review activity must be a NotFoundError, got: " + result.message());
    } else {
        test:assertFail("Deciding an unknown review activity must fail, got payload: " +
                result.toJsonString());
    }
}
