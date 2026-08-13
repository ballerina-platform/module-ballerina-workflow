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

import ballerina/test;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

// Exercises management:getWorkflowMetadata(): the document must be complete from the
// registries alone — no workflow execution — including the human-task completion-form
// schema registered eagerly via registerHumanTask(name, typedesc), which is the path
// the compiler plugin generates when it can determine the result type statically.

type MetaApproval record {|
    boolean approved;
    string comment?;
|};

type MetaInput record {|
    string requestId;
    decimal amount;
|};

function metaFixtureWorkflow(Context ctx, MetaInput input) returns error? {
}

function metaFixtureActivity(string requestId, int retries = 3) returns string {
    return requestId;
}

@test:Config {groups: ["unit"]}
function testWorkflowMetadataCompleteAtRegistration() returns error? {
    _ = check wfInternal:registerWorkflow(metaFixtureWorkflow, "metaFixtureWorkflow",
            {"metaFixtureActivity": metaFixtureActivity});
    _ = check wfInternal:registerHumanTask("metaFixtureWorkflow.approve", MetaApproval);

    management:WorkflowMetadata meta = check management:getWorkflowMetadata();

    test:assertEquals(meta.metadataVersion, "1.0");
    test:assertEquals(meta.reviewActions, ["proceed", "proceed-with-input", "reject"]);

    management:WorkflowDefinitionMeta[] defs =
            meta.definitions.filter(d => d.workflowType == "metaFixtureWorkflow");
    test:assertEquals(defs.length(), 1, "The registered workflow must appear in definitions");
    test:assertEquals(defs[0].kind, "WORKFLOW");
    string defSchema = defs[0].inputSchema ?: "";
    test:assertTrue(defSchema.includes("requestId") && defSchema.includes("amount"),
        "The definition input schema must describe the workflow's data parameter, got: " + defSchema);

    management:HumanTaskMeta[] tasks =
            meta.humanTasks.filter(t => t.name == "metaFixtureWorkflow.approve");
    test:assertEquals(tasks.length(), 1, "The registered human task must appear in humanTasks");
    string resultSchema = tasks[0].resultSchema ?: "";
    test:assertTrue(resultSchema.includes("approved"),
        "The completion-form schema must be available before the task first runs, got: " + resultSchema);

    management:ActivityMeta[] activities = meta.activities
        .filter(a => a.workflowType == "metaFixtureWorkflow" && a.name == "metaFixtureActivity");
    test:assertEquals(activities.length(), 1, "The registered activity must appear in activities");
    string activitySchema = activities[0].inputSchema ?: "";
    test:assertTrue(activitySchema.includes("requestId"),
        "The activity input schema must describe its data parameters, got: " + activitySchema);
    test:assertFalse(activitySchema.includes("\"required\":[\"requestId\",\"retries\"]"),
        "Defaultable activity parameters must not be required in the schema");
}

// A human task registered WITHOUT a typedesc keeps the lazy behavior: it appears in the
// document with a nil resultSchema until it first executes.
@test:Config {groups: ["unit"]}
function testWorkflowMetadataLazyHumanTaskHasNoSchema() returns error? {
    _ = check wfInternal:registerHumanTask("metaFixtureWorkflow.lazyTask");

    management:WorkflowMetadata meta = check management:getWorkflowMetadata();
    management:HumanTaskMeta[] tasks =
            meta.humanTasks.filter(t => t.name == "metaFixtureWorkflow.lazyTask");
    test:assertEquals(tasks.length(), 1);
    test:assertEquals(tasks[0].resultSchema, (),
        "A task registered without a result type must have a nil resultSchema until first execution");
}
