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
// Management API task-queue scoping
// ================================================================================
// Two integrations can share a namespace (the project) with distinct task queues.
// Listings stay namespace-wide but every row identifies its owning integration
// (namespace + taskQueue), an optional filter scopes them to one queue, and
// mutating a task served by a different integration is rejected.

import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.management as management;

const string LOCAL_QUEUE = "BALLERINA_WORKFLOW_TASK_QUEUE";
const string FOREIGN_QUEUE = "other-integration-queue";
const string FOREIGN_TASK_ID = "humantask-foreign-scope-test";

// Test-only: simulates a second integration by starting a human-task-shaped workflow
// on a foreign task queue (no worker serves it, so it stays pending).
isolated function startForeignQueueHumanTask(string workflowId, string taskQueue,
        string taskName, string[] userRoles) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;

// Test-only: a plain workflow-* typed workflow on a foreign queue, so instance-listing
// scoping is asserted against a real foreign workflow (human tasks are excluded there).
isolated function startForeignQueueWorkflow(string workflowId, string taskQueue,
        string workflowType) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;

const string FOREIGN_INSTANCE_ID = "foreign-scope-instance";

const string IDENTITY_TASK_ID = "humantask-identity-check";

// Temporal's visibility store is eventually consistent: a just-started fixture may take
// a moment to appear in listings, so assertions poll until it is indexed.
isolated function awaitHumanTaskVisible(string taskId, string? taskQueue = ())
        returns management:HumanTaskSummary|error {
    foreach int _ in 0 ..< 40 {
        management:HumanTaskSummary[] tasks = check management:listAllHumanTasks(taskQueue = taskQueue);
        foreach management:HumanTaskSummary t in tasks {
            if t.taskId == taskId {
                return t;
            }
        }
        runtime:sleep(0.5);
    }
    return error("Fixture '" + taskId + "' never became visible in the listing");
}

@test:Config {}
function testHumanTaskRowsCarryQueueIdentity() returns error? {
    // Create a known fixture so the assertions cannot pass vacuously, then assert it
    // is returned with the right attribution and every row identifies its integration.
    check startForeignQueueHumanTask(IDENTITY_TASK_ID, FOREIGN_QUEUE, "identityCheckTask", ["OTHER_ROLE"]);

    management:HumanTaskSummary fixture = check awaitHumanTaskVisible(IDENTITY_TASK_ID);
    test:assertEquals(fixture?.taskQueue, FOREIGN_QUEUE,
        "The fixture must be attributed to the queue it was started on");

    management:HumanTaskSummary[] tasks = check management:listAllHumanTasks();
    test:assertTrue(tasks.length() > 0, "The listing must contain at least the created fixture");
    foreach management:HumanTaskSummary t in tasks {
        test:assertEquals(t?.namespace, "default", "Rows must carry the namespace");
        test:assertTrue(t?.taskQueue is string && t?.taskQueue != "",
            "Rows must carry the owning task queue");
    }
}

@test:Config {}
function testTaskQueueFilterScopesListings() returns error? {
    check startForeignQueueHumanTask(FOREIGN_TASK_ID, FOREIGN_QUEUE, "foreignScopeTask", ["OTHER_ROLE"]);

    // Unfiltered: the foreign task is visible (namespace-wide read tolerance).
    management:HumanTaskSummary foreign = check awaitHumanTaskVisible(FOREIGN_TASK_ID);
    test:assertEquals(foreign?.taskQueue, FOREIGN_QUEUE,
        "The foreign task must be attributed to its own task queue");

    // Filtered to the foreign queue: only its tasks.
    management:HumanTaskSummary[] foreignOnly = check management:listAllHumanTasks(taskQueue = FOREIGN_QUEUE);
    test:assertTrue(foreignOnly.length() > 0, "Foreign-queue filter should match the foreign task");
    foreach management:HumanTaskSummary t in foreignOnly {
        test:assertEquals(t?.taskQueue, FOREIGN_QUEUE, "Filtered rows must all belong to the filter queue");
    }

    // Filtered to the local queue: the foreign task must not appear.
    management:HumanTaskSummary[] localOnly = check management:listAllHumanTasks(taskQueue = LOCAL_QUEUE);
    foreach management:HumanTaskSummary t in localOnly {
        test:assertTrue(t.taskId != FOREIGN_TASK_ID, "Local-queue filter must exclude foreign tasks");
    }

    // Instance listings honor the filter the same way — asserted against a real foreign
    // workflow-* fixture (human tasks are excluded from instance listings).
    check startForeignQueueWorkflow(FOREIGN_INSTANCE_ID, FOREIGN_QUEUE, "workflow-foreignScope");
    boolean foreignInstanceSeen = false;
    foreach int _ in 0 ..< 40 {
        management:WorkflowInstancePage page = check management:listWorkflowInstances(taskQueue = FOREIGN_QUEUE);
        foreach management:WorkflowInstanceSummary inst in page.items {
            test:assertEquals(inst?.taskQueue, FOREIGN_QUEUE, "Instance rows must honor the queue filter");
            if inst.workflowId == FOREIGN_INSTANCE_ID {
                foreignInstanceSeen = true;
                test:assertEquals(inst?.namespace, "default", "Instance rows must carry the namespace");
            }
        }
        if foreignInstanceSeen {
            break;
        }
        runtime:sleep(0.5);
    }
    test:assertTrue(foreignInstanceSeen,
        "The foreign workflow fixture must appear in the queue-filtered instance listing");
}

@test:Config {dependsOn: [testTaskQueueFilterScopesListings]}
function testForeignQueueTaskMutationRejected() {
    // Reads tolerate foreign tasks, but completing one must be rejected: its user-role
    // configuration lives in the other integration.
    error? result = management:completeHumanTask(FOREIGN_TASK_ID, {approved: true}, ["OTHER_ROLE"]);
    test:assertTrue(result is error, "Completing a foreign-queue task must fail");
    if result is error {
        test:assertTrue(result.message().includes("served by a different integration"),
            "The rejection must name the ownership problem: " + result.message());
    }
}
