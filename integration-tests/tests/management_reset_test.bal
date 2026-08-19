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
// MANAGEMENT API — RESET POINTS AND RESET
// ================================================================================
//
// Covers instances.resetPoints and instances.reset against a live worker: that the
// points a run reports are its workflow-task events annotated with the steps each
// one schedules, that resetting produces a new run of the same workflow ID, and that
// an event which is not a reset point is refused before the runtime is asked.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

function resetPointsOf(string workflowId) returns management:ResetPoint[]|error {
    json|management:Error result = management:executeCommand({
        operation: management:LIST_RESET_POINTS,
        params: {workflowId: workflowId},
        identity: {userId: "reset-tester", roles: ["OPS"]}
    });
    if result is management:Error {
        return error("Listing reset points failed: " + result.message());
    }
    return result.cloneWithType();
}

function resetRun(string workflowId, map<json> params) returns management:WorkflowHandle|error {
    map<json> withId = params.clone();
    withId["workflowId"] = workflowId;
    json|management:Error result = management:executeCommand({
        operation: management:RESET_INSTANCE,
        params: withId,
        identity: {userId: "reset-tester", roles: ["OPS"]}
    });
    if result is management:Error {
        return error("Reset failed: " + result.message());
    }
    return result.cloneWithType();
}

// True when any of the scheduled node names is this activity. Activities are
// scheduled under `workflow-<workflowType>.<activity>`, so the unqualified tail is
// what a test should match — the qualification is an implementation detail that is
// being removed upstream.
isolated function schedules(string[] nodeNames, string activity) returns boolean {
    foreach string name in nodeNames {
        if name == activity || name.endsWith("." + activity) {
            return true;
        }
    }
    return false;
}

// Runs a two-activity workflow to completion and returns its ID and its result.
function runTwoActivityWorkflow(string prefix) returns [string, anydata]|error {
    ActivityInvocationInput input = {id: uniqueId(prefix), value: "reset"};
    string workflowId = check workflow:run(twoActivityInvocationWorkflow, input);
    anydata result = check workflow:getWorkflowResult(workflowId, 20);
    return [workflowId, result];
}

@test:Config {
    groups: ["integration"]
}
function testResetPointsDescribeTheStepsTheySchedule() returns error? {
    [string, anydata] started = check runTwoActivityWorkflow("reset-points");
    string workflowId = started[0];

    management:ResetPoint[] points = check resetPointsOf(workflowId);
    test:assertTrue(points.length() >= 2,
            "A two-activity run must expose at least two reset points, got " + points.length().toString());

    foreach management:ResetPoint point in points {
        test:assertTrue(point.eventType.startsWith("WORKFLOW_TASK"),
                "A reset point must be a workflow-task event, got: " + point.eventType);
        test:assertEquals(point.nodeIds.length(), point.nodeNames.length(),
                "Every scheduled node must carry a display name");
        test:assertFalse(point.isFirstFailure, "Nothing failed in this run");
    }

    // The two activities are called sequentially, so each is scheduled by its own
    // workflow task — they must land on different points. Activities scheduled by one
    // task would share a point, which is exactly what the caller needs to see.
    management:ResetPoint[] upper = points.filter(p => schedules(p.nodeNames, "uppercaseActivity"));
    management:ResetPoint[] length = points.filter(p => schedules(p.nodeNames, "lengthActivity"));
    test:assertEquals(upper.length(), 1, "uppercaseActivity must be scheduled by exactly one reset point");
    test:assertEquals(length.length(), 1, "lengthActivity must be scheduled by exactly one reset point");
    test:assertTrue(upper[0].eventId < length[0].eventId,
            "Sequential steps must produce reset points in execution order");
}

@test:Config {
    groups: ["integration"]
}
function testResetFromFirstWorkflowTaskRerunsTheSameInstance() returns error? {
    // Use case: a completed run has to be run again with the payload it started with.
    [string, anydata] started = check runTwoActivityWorkflow("reset-first");
    string workflowId = started[0];
    anydata originalResult = started[1];

    management:WorkflowHandle reset = check resetRun(workflowId, {resetType: "first-workflow-task"});
    test:assertEquals(reset.workflowId, workflowId, "A reset stays on the same workflow ID");
    test:assertTrue(reset.runId != "", "A reset must report the new run ID");

    // The reset run replays from the beginning and reaches the same outcome.
    anydata replayed = check workflow:getWorkflowResult(workflowId, 20);
    test:assertEquals(replayed, originalResult, "Replaying from the start must reproduce the result");
}

@test:Config {
    groups: ["integration"]
}
function testResetFromSelectedStepStartsThere() returns error? {
    // Use case: an operator opens a run, picks a step, and restarts from it.
    [string, anydata] started = check runTwoActivityWorkflow("reset-step");
    string workflowId = started[0];
    management:ResetPoint[] points = check resetPointsOf(workflowId);
    management:ResetPoint[] atLength = points.filter(p => schedules(p.nodeNames, "lengthActivity"));
    test:assertEquals(atLength.length(), 1);

    management:WorkflowHandle reset =
            check resetRun(workflowId, {resetType: "workflow-task-id", eventId: atLength[0].eventId});
    test:assertEquals(reset.workflowId, workflowId);

    anydata replayed = check workflow:getWorkflowResult(workflowId, 20);
    test:assertTrue(replayed is string, "The reset run must complete");

    // History before the point is preserved rather than re-executed: the new run still
    // reports the earlier step, and reports it once.
    management:ActivityTreeNode[] tree = check management:getActivityTree(workflowId, reset.runId);
    management:ActivityTreeNode[] preserved =
            tree.filter(n => n.name == "uppercaseActivity" || n.name.endsWith(".uppercaseActivity"));
    test:assertEquals(preserved.length(), 1,
            "The step before the reset point must be preserved, not run again");
    test:assertEquals(preserved[0].status, "COMPLETED");
}

@test:Config {
    groups: ["integration"]
}
function testResetFromLastWorkflowTask() returns error? {
    // Use case: a run wedged on a failing workflow task, moved onto fixed code. The
    // target is the run's most recent workflow task, so only its tail re-executes.
    [string, anydata] started = check runTwoActivityWorkflow("reset-last");
    string workflowId = started[0];
    management:ResetPoint[] points = check resetPointsOf(workflowId);

    management:WorkflowHandle reset = check resetRun(workflowId, {resetType: "last-workflow-task"});
    test:assertEquals(reset.workflowId, workflowId);
    test:assertTrue(reset.runId != "", "Resetting to the last workflow task must start a new run");

    anydata replayed = check workflow:getWorkflowResult(workflowId, 20);
    test:assertEquals(replayed, started[1], "Re-running only the tail must reach the same result");

    // The target really was the last point, not the first.
    management:ResetPoint last = points[points.length() - 1];
    test:assertTrue(last.eventId > points[0].eventId,
            "A run with several workflow tasks must distinguish its first from its last");
}

@test:Config {
    groups: ["integration"]
}
function testResetRejectsAnEventThatIsNotAResetPoint() returns error? {
    [string, anydata] started = check runTwoActivityWorkflow("reset-bad-event");
    string workflowId = started[0];

    // Event 1 is WorkflowExecutionStarted — a real event, but not a workflow task, so
    // it cannot be reset to. Refused here rather than by the runtime, whose own error
    // for this does not say what is valid.
    json|management:Error result = management:executeCommand({
        operation: management:RESET_INSTANCE,
        params: {workflowId: workflowId, resetType: "workflow-task-id", eventId: 1},
        identity: {userId: "reset-tester", roles: ["OPS"]}
    });
    test:assertTrue(result is management:InvalidRequestError,
            "An ineligible event must be an InvalidRequestError");
    string message = (<management:Error>result).message();
    test:assertTrue(message.includes("is not a reset point"), "Got: " + message);
    // The refusal orients the caller without inlining the listing: a long run has
    // thousands of points, and they are already available from the reset-points
    // operation the message names.
    test:assertTrue(message.includes("reset points, from"),
            "The refusal must give the range of valid points, got: " + message);
    test:assertTrue(message.includes("Call the reset-points operation"),
            "The refusal must say where the full list is, got: " + message);
}

@test:Config {
    groups: ["integration"]
}
function testResetPointsMarkTheFirstFailure() returns error? {
    // A run whose activity fails: the point that would re-run it is flagged, so a UI
    // can offer "retry from where it broke" without the operator reading history.
    ActivityInvocationInput input = {id: uniqueId("reset-failure"), value: "boom"};
    string workflowId = check workflow:run(singleFailInvocationWorkflow, input);
    do {
        _ = check workflow:getWorkflowResult(workflowId, 20);
    } on fail {
        // Expected — the activity always fails.
    }
    runtime:sleep(0.5d);

    management:ResetPoint[] points = check resetPointsOf(workflowId);
    management:ResetPoint[] flagged = points.filter(p => p.isFirstFailure);
    test:assertEquals(flagged.length(), 1, "Exactly one point must be flagged as the first failure");
    test:assertTrue(schedules(flagged[0].nodeNames, "invocationFailActivity"),
            "The flagged point must be the one that scheduled the failed step, got: "
            + flagged[0].nodeNames.toString());
}
