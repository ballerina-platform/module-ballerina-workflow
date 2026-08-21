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
            humanTasks: [
                {
                    name: "approve",
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
        }
    ],
    agents: []
};

function setPackedWorkflowDescriptor(json? descriptor) = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;

@test:Config {groups: ["unit"]}
function testWorkflowMetadataCompleteAtRegistration() returns error? {
    _ = check registerWorkflowForTest(metaFixtureWorkflow, "metaFixtureWorkflow",
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
    string defSchema = defs[0].inputSchema ?: "";
    test:assertTrue(defSchema.includes("requestId") && defSchema.includes("amount"),
        "The definition input schema must describe the workflow's data parameter, got: " + defSchema);

    // The completion-form schema comes from the packed descriptor — before the task
    // has ever executed (the registry learns the type only at first execution).
    management:HumanTaskMeta[] tasks =
            meta.humanTasks.filter(t => t.name == "metaFixtureWorkflow.approve");
    test:assertEquals(tasks.length(), 1, "The registered human task must appear in humanTasks");
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
