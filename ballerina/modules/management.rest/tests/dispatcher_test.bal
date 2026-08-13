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

// Unit tests for executeManagementCommand's envelope handling: parameter validation,
// identity mapping, and status/body conversion. These exercise the same op functions
// the REST resources call, so a passing dispatcher result IS the REST behavior.

@test:Config {groups: ["unit"]}
function testDispatcherUnknownOperation() {
    ManagementCommandResult result = executeManagementCommand({operation: "nope"});
    test:assertEquals(result.httpStatus, 400);
    test:assertEquals(result.body, <json>{"error": {"message": "Unknown operation: nope"}});
}

@test:Config {groups: ["unit"]}
function testDispatcherMissingRequiredParam() {
    ManagementCommandResult result = executeManagementCommand({operation: "instances.get"});
    test:assertEquals(result.httpStatus, 400);
    test:assertEquals(result.body, <json>{"error": {"message": "workflowId is required"}});
}

@test:Config {groups: ["unit"]}
function testDispatcherListDefinitionsReturnsRestShape() {
    ManagementCommandResult result = executeManagementCommand({operation: "definitions.list"});
    test:assertEquals(result.httpStatus, 200);
    json body = result.body;
    test:assertTrue(body is map<json> && (<map<json>>body).hasKey("definitions"),
        "definitions.list must return the REST body shape {definitions: [...]}");
}

@test:Config {groups: ["unit"]}
function testDispatcherCompleteHumanTaskRequiresRoles() {
    // An empty identity.roles array is the command-channel equivalent of an absent
    // x-user-roles header — same 403 with the same error body as the REST route.
    ManagementCommandResult result = executeManagementCommand({
        operation: "humanTasks.complete",
        params: {taskId: "humantask-x", result: {}}
    });
    test:assertEquals(result.httpStatus, 403);
    test:assertEquals(result.body,
        <json>{"error": {"message": "Unauthorized: x-user-roles header is required"}});
}

@test:Config {groups: ["unit"]}
function testDispatcherFailHumanTaskRequiresReason() {
    ManagementCommandResult result = executeManagementCommand({
        operation: "humanTasks.fail",
        params: {taskId: "humantask-x"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertEquals(result.httpStatus, 400);
    test:assertEquals(result.body, <json>{"error": {"message": "reason is required"}});
}

@test:Config {groups: ["unit"]}
function testDispatcherDecideRequiresInputForProceedWithInput() {
    ManagementCommandResult result = executeManagementCommand({
        operation: "reviewActivities.decide",
        params: {taskId: "reviewactivity-x", action: "proceed-with-input"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertEquals(result.httpStatus, 400);
    test:assertEquals(result.body, <json>{"error": {"message": "input must be a JSON object"}});
}

@test:Config {groups: ["unit"]}
function testDispatcherDecideRejectsUnknownAction() {
    ManagementCommandResult result = executeManagementCommand({
        operation: "reviewActivities.decide",
        params: {taskId: "reviewactivity-x", action: "escalate"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertEquals(result.httpStatus, 400);
    test:assertEquals(result.body,
        <json>{"error": {"message": "Unknown review decision action: escalate"}});
}
