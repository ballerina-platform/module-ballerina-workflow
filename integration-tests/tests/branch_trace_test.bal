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
// BRANCH TRACING - TESTS
// ================================================================================
// The whole chain, end to end: the compiler stamps each call site with its step id, the
// runtime carries it into the activity invocation, and the activity tree reports which step
// ran. Two runs of the same workflow down opposite arms must report different steps.

import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testTheApprovedArmReportsItsOwnStepId() returns error? {
    string testId = uniqueId("branch-approved");
    string workflowId = check workflow:run(branchTraceWorkflow, {id: testId, approved: true});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, testId + ":approved");

    management:ActivityTreeNode node = check activityNodeFor(workflowId, "recordDecision");
    test:assertEquals(node.stepId, "recordDecision#1",
            "The first arm's step is the first occurrence of the activity in source order");
}

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testTheRejectedArmReportsADifferentStepId() returns error? {
    string testId = uniqueId("branch-rejected");
    string workflowId = check workflow:run(branchTraceWorkflow, {id: testId, approved: false});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, testId + ":rejected");

    management:ActivityTreeNode node = check activityNodeFor(workflowId, "recordDecision");
    test:assertEquals(node.stepId, "recordDecision#2",
            "Same activity, same name in history — only the step id tells the arms apart");
}

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testTheExecutionGraphCarriesTheStepIdForHighlighting() returns error? {
    string testId = uniqueId("branch-graph");
    string workflowId = check workflow:run(branchTraceWorkflow, {id: testId, approved: true});
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ExecutionGraph graph = check management:getExecutionGraph(workflowId, "");
    management:GraphNode[] activityNodes = from management:GraphNode gn in graph.nodes
        where gn.'type == management:ACTIVITY && gn.label.includes("recordDecision")
        select gn;
    test:assertEquals(activityNodes.length(), 1, "One activity ran");

    map<json>? metadata = activityNodes[0].metadata;
    test:assertTrue(metadata is map<json>, "The node must carry metadata to be joinable");
    if metadata is map<json> {
        test:assertEquals(metadata["stepId"], "recordDecision#1",
                "The step id travels to the viewer through the graph node's metadata");
    }
}

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testActivitiesAreScheduledUnderTheirPlainName() returns error? {
    // The activity type used to be "<workflowType>.<activity>". Executions started since the
    // naming patch schedule the plain name; the qualified one stays registered so older
    // executions keep replaying.
    string testId = uniqueId("branch-naming");
    string workflowId = check workflow:run(branchTraceWorkflow, {id: testId, approved: true});
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ActivityTreeNode node = check activityNodeFor(workflowId, "recordDecision");
    test:assertEquals(node.name, "recordDecision",
            "The activity type is the plain function name, with no workflow qualifier");
}

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testAChosenStepIdIsWhatTheExecutionReports() returns error? {
    string testId = uniqueId("branch-named");
    string workflowId = check workflow:run(namedStepWorkflow, {id: testId, approved: true});
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ActivityTreeNode node = check activityNodeFor(workflowId, "recordDecision");
    test:assertEquals(node.stepId, "record-outcome",
            "The chosen id reaches history, so the graph and the run agree on the same name");
}

@test:Config {
    groups: ["integration", "branch-trace"]
}
function testAChildWorkflowStepIdSurvivesTheMemo() returns error? {
    // The memo is a different carrier from the call config, and it was silently broken while every
    // activity test passed: the writer used a constant whose value had not been renamed, the reader
    // spelled the key as a literal. Only a memo-carried id catches that.
    string testId = uniqueId("branch-child");
    string workflowId = check workflow:run(childStepWorkflow, {id: testId, approved: true});
    _ = check workflow:getWorkflowResult(workflowId, 30);

    management:ActivityTreeNode[] nodes = check management:getActivityTree(workflowId, "");
    management:ActivityTreeNode[] children = from management:ActivityTreeNode node in nodes
        where node.'type == management:CHILD_WORKFLOW
        select node;
    test:assertEquals(children.length(), 1, "One child workflow ran");
    test:assertEquals(children[0].stepId, "spawn-audit",
            "The child's step id travels in its memo, so the parent's diagram can place the start");
}

// Returns the single activity-tree node whose name contains `activityName`.
isolated function activityNodeFor(string workflowId, string activityName)
        returns management:ActivityTreeNode|error {
    management:ActivityTreeNode[] nodes = check management:getActivityTree(workflowId, "");
    management:ActivityTreeNode[] matches = from management:ActivityTreeNode node in nodes
        where node.'type == management:ACTIVITY && node.name.includes(activityName)
        select node;
    if matches.length() != 1 {
        return error("Expected exactly one '" + activityName + "' node, found "
                + matches.length().toString() + " in " + nodes.toString());
    }
    return matches[0];
}
