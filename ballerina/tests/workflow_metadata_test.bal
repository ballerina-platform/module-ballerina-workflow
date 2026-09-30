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

import ballerina/jballerina.java;
import ballerina/test;
import ballerina/workflow.management;

// Exercises management:getWorkflowMetadata(): the document must be complete before any
// workflow has executed — definitions and activity schemas from the registries, and the
// human-task completion-form schema from the packed workflow descriptor
// (workflow.def.json), which the compiler plugin generates at build time. `bal test`
// never packs a descriptor, so the tests inject one through the test seam.

type MetaInput record {|
    string requestId;
    decimal amount;
|};

function metaFixtureWorkflow(Context ctx, MetaInput input) returns error? {
}

function metaFixtureActivity(string requestId, int retries = 3) returns string {
    return requestId;
}

// The descriptor document the compiler plugin would pack for this fixture — only the
// parts the metadata assembly reads (the human-task result slots).
final json & readonly metaFixtureDescriptor = {
    descriptorVersion: "1.0",
    package: {org: "test", name: "meta_fixture", version: "0.1.0"},
    workflows: [
        {
            name: "metaFixtureWorkflow",
            kind: "WORKFLOW",
            displayName: "Meta fixture",
            icon: "icons/meta.svg",
            activities: [{name: "metaFixtureActivity", displayName: "Fixture activity"}],
            humanTasks: [
                {
                    name: "approve",
                    title: "Approve the request",
                    result: {
                        'type: "MetaApproval",
                        schema: {
                            'type: "object",
                            properties: {approved: {'type: "boolean"}, comment: {'type: "string"}},
                            required: ["approved"]
                        }
                    }
                }
            ]
        },
        {
            name: "metaFixtureWorkflow2",
            kind: "WORKFLOW",
            // The same activity name under another workflow, with a label of its own.
            activities: [{name: "metaFixtureActivity", displayName: "Second owner's activity"}],
            humanTasks: []
        }
    ],
    agents: [
        {
            name: "metaFixtureAgent",
            kind: "AGENT",
            displayName: "Meta agent",
            // An activity only an agent uses is still an activity in history.
            tools: [{name: "makePayment", kind: "ACTIVITY", displayName: "Make payment"}],
            humanTasks: []
        }
    ]
};

function setPackedWorkflowDescriptor(json? descriptor) = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;

function activityDisplayLabel(string activityType) returns string? = @java:Method {
    'class: "io.ballerina.lib.workflow.test.DisplayNameProbe",
    name: "activityLabel"
} external;

@test:Config {groups: ["unit"]}
function testAnAgentsActivityHasADisplayNameInHistoryReads() {
    setPackedWorkflowDescriptor(metaFixtureDescriptor);
    test:assertEquals(activityDisplayLabel("metaFixtureAgent.makePayment"), "Make payment",
        "An activity-backed tool is indexed under its agent");
    test:assertEquals(activityDisplayLabel("makePayment"), "Make payment",
        "History names the activity by its plain type, which the bare key resolves");
    test:assertEquals(activityDisplayLabel("metaFixtureWorkflow2.metaFixtureActivity"), "Second owner's activity",
        "A workflow's own key wins over the first owner's bare key");
    setPackedWorkflowDescriptor(());
}

@test:Config {groups: ["unit"]}
function testWorkflowMetadataCompleteAtRegistration() returns error? {
    _ = check registerWorkflowForTest(metaFixtureWorkflow, "metaFixtureWorkflow",
            {"metaFixtureActivity": metaFixtureActivity});
    _ = check registerWorkflowForTest(metaFixtureWorkflow, "metaFixtureWorkflow2",
            {"metaFixtureActivity": metaFixtureActivity});
    _ = check registerHumanTaskForTest("metaFixtureWorkflow.approve");
    setPackedWorkflowDescriptor(metaFixtureDescriptor);

    management:WorkflowMetadata meta = check management:getWorkflowMetadata();

    test:assertEquals(meta.metadataVersion, "1.0");
    test:assertEquals(meta.reviewActions, ["proceed", "proceed-with-input", "reject"]);
    // The queue is runtime state — chosen at program startup, like capabilities — so it is
    // exposed beside the document, never inside it: a control plane needs it to scope a
    // shared namespace to one integration, but it is not a property of the workflows.
    test:assertEquals(management:getWorkflowTaskQueue(), "BALLERINA_WORKFLOW_TASK_QUEUE",
        "The management module must name the worker's task queue");

    management:WorkflowDefinitionMeta[] defs =
            meta.definitions.filter(d => d.workflowType == "metaFixtureWorkflow");
    test:assertEquals(defs.length(), 1, "The registered workflow must appear in definitions");
    test:assertEquals(defs[0].kind, "WORKFLOW");
    // Display fields come from the descriptor's @display labels, joined by name at read time.
    test:assertEquals(defs[0].displayName, "Meta fixture");
    test:assertEquals(defs[0].icon, "icons/meta.svg");
    management:WorkflowDefinition[] listed = (check management:listWorkflowDefinitions())
        .filter(d => d.workflowType == "metaFixtureWorkflow");
    test:assertEquals(listed.length(), 1);
    test:assertEquals(listed[0].displayName, "Meta fixture",
        "The definition listing must carry the same display name as the metadata document");
    string defSchema = defs[0].inputSchema ?: "";
    test:assertTrue(defSchema.includes("requestId") && defSchema.includes("amount"),
        "The definition input schema must describe the workflow's data parameter, got: " + defSchema);

    // The completion-form schema comes from the packed descriptor — before the task
    // has ever executed (the registry learns the type only at first execution).
    management:HumanTaskMeta[] tasks =
            meta.humanTasks.filter(t => t.name == "metaFixtureWorkflow.approve");
    test:assertEquals(tasks.length(), 1, "The registered human task must appear in humanTasks");
    test:assertEquals(tasks[0].title, "Approve the request", "A task's constant title is its display name");
    string resultSchema = tasks[0].resultSchema ?: "";
    test:assertTrue(resultSchema.includes("approved"),
        "The completion-form schema must come from the packed descriptor, got: " + resultSchema);

    // The descriptor itself is served verbatim under the `descriptor` field.
    json? descriptor = meta.descriptor;
    test:assertTrue(descriptor !is (), "The packed descriptor must be served in the metadata");
    json descriptorVersion = check (<json>descriptor).descriptorVersion;
    test:assertEquals(descriptorVersion, "1.0");

    management:ActivityMeta[] activities = meta.activities
        .filter(a => a.workflowType == "metaFixtureWorkflow" && a.name == "metaFixtureActivity");
    test:assertEquals(activities.length(), 1, "The registered activity must appear in activities");
    test:assertEquals(activities[0].displayName, "Fixture activity");
    test:assertEquals(activities[0].icon, (), "No icon was declared for the activity");
    // The same activity name under another workflow keeps that workflow's own label.
    management:ActivityMeta[] second = meta.activities
        .filter(a => a.workflowType == "metaFixtureWorkflow2" && a.name == "metaFixtureActivity");
    test:assertEquals(second.length(), 1);
    test:assertEquals(second[0].displayName, "Second owner's activity",
        "An activity's display is looked up by its owning workflow first");
    // Parse the schema rather than matching its text: the assertion is about which
    // properties are required, not about how the document happens to be formatted.
    json activitySchema = check (activities[0].inputSchema ?: "{}").fromJsonString();
    map<json> schemaObject = check activitySchema.ensureType();
    map<json> properties = check schemaObject["properties"].ensureType();
    test:assertTrue(properties.hasKey("requestId"),
        "The activity input schema must describe its data parameters, got: " + activitySchema.toString());
    json[] required = check schemaObject["required"].ensureType();
    test:assertEquals(required, <json[]>["requestId"],
        "Only the non-defaultable parameter must be required");

    setPackedWorkflowDescriptor(());
}

// A human task the descriptor does not describe keeps the lazy behavior: it appears in
// the document with a nil resultSchema until it first executes.
@test:Config {groups: ["unit"]}
function testWorkflowMetadataLazyHumanTaskHasNoSchema() returns error? {
    _ = check registerHumanTaskForTest("metaFixtureWorkflow.lazyTask");
    setPackedWorkflowDescriptor(metaFixtureDescriptor);

    management:WorkflowMetadata meta = check management:getWorkflowMetadata();
    management:HumanTaskMeta[] tasks =
            meta.humanTasks.filter(t => t.name == "metaFixtureWorkflow.lazyTask");
    test:assertEquals(tasks.length(), 1);
    test:assertEquals(tasks[0].resultSchema, (),
        "A task the descriptor does not describe must have a nil resultSchema until first execution");

    setPackedWorkflowDescriptor(());
}
