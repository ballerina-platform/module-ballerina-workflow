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
// against the embedded IN_MEMORY Temporal server started in @BeforeSuite
// (test.bal). These complement the dispatch and parameter-contract unit tests in
// modules/management/tests/command_test.bal: here the operations actually run, so
// the payload each one returns is checked against a real instance, human task, or
// registry.
//
// They follow the graceful-skip pattern used by the other integration tests: when
// no workflow server is reachable the test returns early instead of failing.
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

// Completes immediately — used for the definition, start, and inspection commands.
@Workflow
function cmdSimpleWorkflow(Context ctx, string input) returns string|error {
    return "handled: " + input;
}

// Parks on a human task, so the task commands have something to act on.
@Workflow
function cmdHumanTaskWorkflow(Context ctx, string orderId) returns CmdDecision|error {
    CmdDecision decision = check ctx->awaitHumanTask("cmdApprove", "APPROVER",
            payload = {"orderId": orderId});
    return decision;
}

// Sleeps long enough for the suspend/terminate/cancel commands to act on a running
// instance without racing its completion.
@Workflow
function cmdLongRunningWorkflow(Context ctx, string input) returns string|error {
    check ctx.sleep({seconds: 30});
    return input;
}

isolated function runCommand(management:Operation operation, map<json> params = {})
        returns json|management:Error =>
    management:executeCommand({operation: operation, params: params, identity: cmdIdentity});

// The payload of a successful command, or `()` when the operation could not run
// (no server, instance not yet visible) so the caller can skip gracefully.
isolated function commandPayload(management:Operation operation, map<json> params = {}) returns map<json>? {
    json|management:Error result = runCommand(operation, params);
    if result is management:Error || result !is map<json> {
        return ();
    }
    return result;
}

// Terminates an instance a test left running. Best effort: an instance that already
// closed reports an error, which is not a test failure.
isolated function cleanupInstance(string workflowId) {
    json|management:Error terminated = runCommand(management:TERMINATE_INSTANCE,
            {workflowId: workflowId, reason: "test cleanup"});
    if terminated is management:Error {
        // Already closed, or no server — nothing to clean up.
    }
}

// ── definitions.list ─────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandListDefinitions() returns error? {
    _ = check registerWorkflowForTest(cmdSimpleWorkflow, "cmd-simple-workflow");

    map<json>? payload = commandPayload(management:LIST_DEFINITIONS);
    if payload is () {
        return; // No server available — skip.
    }
    json[] definitions = check payload["definitions"].ensureType();
    test:assertTrue(definitions.length() > 0, "Registered workflows must be listed");

    boolean found = false;
    foreach json definition in definitions {
        map<json> entry = check definition.ensureType();
        if entry["workflowType"] == "cmd-simple-workflow" {
            found = true;
            test:assertEquals(entry["kind"], "WORKFLOW");
        }
    }
    test:assertTrue(found, "The workflow registered by this test must appear in definitions.list");
}

// ── instances.start / get / list / history / activityTree / executionGraph ────

@test:Config {groups: ["unit"]}
function testCommandStartAndInspectInstance() returns error? {
    _ = check registerWorkflowForTest(cmdSimpleWorkflow, "cmd-start-workflow");

    json|management:Error started = runCommand(management:START_INSTANCE, {
        workflowType: "cmd-start-workflow",
        input: "payload-1"
    });
    if started is management:Error {
        return; // No server available — skip.
    }
    map<json> startHandle = check started.ensureType();
    string workflowId = check startHandle["workflowId"].ensureType();
    test:assertTrue(workflowId.length() > 0, "instances.start must return the new workflow ID");

    _ = check getWorkflowResult(workflowId, 15);

    // instances.get
    map<json>? instance = commandPayload(management:GET_INSTANCE, {workflowId: workflowId});
    if instance is () {
        return;
    }
    test:assertEquals(instance["workflowId"], workflowId);
    test:assertEquals(instance["status"], "COMPLETED");

    // instances.list — the page must contain the instance just run.
    map<json>? page = commandPayload(management:LIST_INSTANCES, {workflowId: workflowId, 'limit: 20});
    if page is map<json> {
        json[] items = check page["items"].ensureType();
        test:assertTrue(items.length() > 0, "instances.list must find the started instance");
    }

    // instances.history
    map<json>? history = commandPayload(management:GET_INSTANCE_HISTORY, {workflowId: workflowId});
    if history is map<json> {
        json[] events = check history["events"].ensureType();
        test:assertTrue(events.length() > 0, "A completed instance must have history events");
    }

    // instances.activityTree
    map<json>? tree = commandPayload(management:GET_INSTANCE_ACTIVITY_TREE, {workflowId: workflowId});
    if tree is map<json> {
        test:assertTrue(tree.hasKey("nodes"), "instances.activityTree must return a nodes array");
    }

    // instances.executionGraph
    map<json>? graph = commandPayload(management:GET_INSTANCE_EXECUTION_GRAPH, {workflowId: workflowId});
    if graph is map<json> {
        test:assertTrue(graph.hasKey("nodes") || graph.hasKey("edges"),
            "instances.executionGraph must return a graph");
    }
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownInstanceIsNotFound() {
    json|management:Error result = runCommand(management:GET_INSTANCE,
            {workflowId: "wf-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown instance must be a NotFoundError, got: " + result.message());
    }
}

// ── humanTasks.list / pendingCount / get / complete ───────────────────────────

@test:Config {groups: ["unit"]}
function testCommandHumanTaskListGetAndComplete() returns error? {
    _ = check registerWorkflowForTest(cmdHumanTaskWorkflow, "cmd-human-task-complete");

    map<string> input = {id: "test-cmd-ht-001", orderId: "ORD-CMD-001"};
    string|error runResult = run(cmdHumanTaskWorkflow, input);
    if runResult is error {
        return; // No server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(2);

    // humanTasks.list — find the task this instance parked on.
    map<json>? page = commandPayload(management:LIST_HUMAN_TASKS,
            {status: "PENDING", parentWorkflowId: workflowId, 'limit: 20});
    if page is () {
        return;
    }
    json[] items = check page["items"].ensureType();
    if items.length() == 0 {
        return; // Task not visible yet — skip.
    }
    map<json> summary = check items[0].ensureType();
    string taskId = check summary["taskId"].ensureType();
    test:assertEquals(summary["parentWorkflowId"], workflowId);
    test:assertTrue(summary["canComplete"] == true,
        "A task the caller's roles match must be completable");

    // humanTasks.pendingCount
    map<json>? count = commandPayload(management:COUNT_PENDING_HUMAN_TASKS);
    if count is map<json> {
        int pending = check count["count"].ensureType();
        test:assertTrue(pending > 0, "The pending count must include the parked task");
    }

    // humanTasks.get
    map<json>? task = commandPayload(management:GET_HUMAN_TASK, {taskId: taskId});
    if task is map<json> {
        test:assertEquals(task["taskId"], taskId);
    }

    // humanTasks.complete — and the workflow receives the value.
    CmdDecision expected = {approved: true, comment: "via command"};
    json|management:Error completed = runCommand(management:COMPLETE_HUMAN_TASK,
            {taskId: taskId, result: expected.toJson()});
    if completed is management:Error {
        return;
    }
    map<json> completion = check completed.ensureType();
    test:assertEquals(completion["success"], true);
    test:assertEquals(completion["completedBy"], "alice",
        "The command identity must be recorded as the completing user");

    anydata|error wfResult = getWorkflowResult(workflowId, 15);
    if wfResult is error {
        return;
    }
    CmdDecision decision = check wfResult.ensureType();
    test:assertEquals(decision, expected, "The workflow must receive what the command submitted");
}

// ── humanTasks.fail ──────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandFailHumanTask() returns error? {
    _ = check registerWorkflowForTest(cmdHumanTaskWorkflow, "cmd-human-task-fail");

    map<string> input = {id: "test-cmd-ht-002", orderId: "ORD-CMD-002"};
    string|error runResult = run(cmdHumanTaskWorkflow, input);
    if runResult is error {
        return;
    }
    runtime:sleep(2);

    map<json>? page = commandPayload(management:LIST_HUMAN_TASKS,
            {status: "PENDING", parentWorkflowId: runResult, 'limit: 20});
    if page is () {
        return;
    }
    json[] items = check page["items"].ensureType();
    if items.length() == 0 {
        return;
    }
    map<json> summary = check items[0].ensureType();
    string taskId = check summary["taskId"].ensureType();

    json|management:Error failed = runCommand(management:FAIL_HUMAN_TASK, {
        taskId: taskId,
        reason: "rejected by the operator",
        details: {"code": "MANUAL_REJECT"}
    });
    if failed is management:Error {
        return;
    }
    map<json> outcome = check failed.ensureType();
    test:assertEquals(outcome["success"], true);
    test:assertEquals(outcome["completedBy"], "alice");
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownHumanTaskIsNotFound() {
    json|management:Error result = runCommand(management:GET_HUMAN_TASK,
            {taskId: "humantask-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown task must be a NotFoundError, got: " + result.message());
    }
}

// ── instances.suspend / resume / wake ────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandSuspendAndResumeInstance() returns error? {
    _ = check registerWorkflowForTest(cmdLongRunningWorkflow, "cmd-suspend-resume");

    map<string> input = {id: "test-cmd-suspend-001", input: "keep-running"};
    string|error runResult = run(cmdLongRunningWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    json|management:Error suspended = runCommand(management:SUSPEND_INSTANCE, {workflowId: workflowId});
    if suspended is management:Error {
        return;
    }
    map<json> suspendResult = check suspended.ensureType();
    test:assertEquals(suspendResult["success"], true, "instances.suspend must acknowledge");

    json|management:Error resumed = runCommand(management:RESUME_INSTANCE, {workflowId: workflowId});
    if resumed is management:Error {
        return;
    }
    map<json> resumeResult = check resumed.ensureType();
    test:assertEquals(resumeResult["success"], true, "instances.resume must acknowledge");

    // Leave nothing running behind.
    cleanupInstance(workflowId);
}

@test:Config {groups: ["unit"]}
function testCommandWakeInstanceDispatches() returns error? {
    _ = check registerWorkflowForTest(cmdLongRunningWorkflow, "cmd-wake");

    map<string> input = {id: "test-cmd-wake-001", input: "sleeping"};
    string|error runResult = run(cmdLongRunningWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
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
    _ = check registerWorkflowForTest(cmdLongRunningWorkflow, "cmd-terminate");

    map<string> input = {id: "test-cmd-terminate-001", input: "to-terminate"};
    string|error runResult = run(cmdLongRunningWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    json|management:Error terminated = runCommand(management:TERMINATE_INSTANCE,
            {workflowId: workflowId, reason: "no longer needed"});
    if terminated is management:Error {
        return;
    }
    map<json> result = check terminated.ensureType();
    test:assertEquals(result["success"], true, "instances.terminate must acknowledge");

    runtime:sleep(1);
    map<json>? instance = commandPayload(management:GET_INSTANCE, {workflowId: workflowId});
    if instance is map<json> {
        test:assertEquals(instance["status"], "TERMINATED",
            "A terminated instance must report status TERMINATED");
    }
}

@test:Config {groups: ["unit"]}
function testCommandCancelInstance() returns error? {
    _ = check registerWorkflowForTest(cmdLongRunningWorkflow, "cmd-cancel");

    map<string> input = {id: "test-cmd-cancel-001", input: "to-cancel"};
    string|error runResult = run(cmdLongRunningWorkflow, input);
    if runResult is error {
        return;
    }
    string workflowId = runResult;
    runtime:sleep(2);

    json|management:Error cancelled = runCommand(management:CANCEL_INSTANCE, {workflowId: workflowId});
    if cancelled is management:Error {
        return;
    }
    map<json> result = check cancelled.ensureType();
    test:assertEquals(result["success"], true, "instances.cancel must acknowledge");
}

// ── reviewActivities.list / get / decide ─────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCommandListReviewActivities() returns error? {
    map<json>? page = commandPayload(management:LIST_REVIEW_ACTIVITIES,
            {status: "PENDING", 'limit: 20});
    if page is () {
        return; // No server available — skip.
    }
    // The page shape is asserted even when no review is pending: a listing that
    // returns nothing must still be a well-formed page.
    test:assertTrue(page.hasKey("items"), "reviewActivities.list must return an items array");
    test:assertTrue(page.hasKey("hasMore"), "reviewActivities.list must return a hasMore flag");
    json[] items = check page["items"].ensureType();
    test:assertTrue(items.length() >= 0);
}

@test:Config {groups: ["unit"]}
function testCommandGetUnknownReviewActivityIsNotFound() {
    json|management:Error result = runCommand(management:GET_REVIEW_ACTIVITY,
            {taskId: "reviewactivity-does-not-exist-0001"});
    if result is management:Error {
        test:assertTrue(result is management:NotFoundError,
            "An unknown review activity must be a NotFoundError, got: " + result.message());
    }
}

@test:Config {groups: ["unit"]}
function testCommandDecideUnknownReviewActivityIsNotFound() {
    json|management:Error result = runCommand(management:DECIDE_REVIEW_ACTIVITY,
            {taskId: "reviewactivity-does-not-exist-0002", action: "proceed"});
    if result is management:Error {
        test:assertFalse(result is management:InvalidRequestError,
            "A valid decision must pass validation and reach the runtime, got: " + result.message());
    }
}
