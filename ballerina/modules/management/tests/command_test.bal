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

// Unit tests for executeCommand: parameter validation, identity mapping, and the
// error type each failure produces. These exercise the same operation functions
// the REST resources call, so the error a command reports is exactly the error
// the REST layer maps to a status code.

@test:Config {groups: ["unit"]}
function testCommandMissingRequiredParam() {
    json|Error result = executeCommand({operation: GET_INSTANCE});
    test:assertTrue(result is InvalidRequestError,
        "A missing required parameter must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "workflowId is required");
}

@test:Config {groups: ["unit"]}
function testCommandListDefinitionsReturnsPayloadShape() returns error? {
    json|Error result = executeCommand({operation: LIST_DEFINITIONS});
    test:assertFalse(result is Error, "definitions.list must succeed on a running worker");
    json body = check result.ensureType();
    test:assertTrue(body is map<json> && (<map<json>>body).hasKey("definitions"),
        "definitions.list must return the payload shape {definitions: [...]}");
}

@test:Config {groups: ["unit"]}
function testCommandCompleteHumanTaskRequiresRoles() {
    // An empty identity.roles array means the caller holds no roles — the same
    // denial the REST layer reports when the caller presents none.
    json|Error result = executeCommand({
        operation: COMPLETE_HUMAN_TASK,
        params: {taskId: "humantask-x", result: {}}
    });
    test:assertTrue(result is AccessDeniedError,
        "Completing without roles must be an AccessDeniedError");
    test:assertEquals((<Error>result).message(), "Unauthorized: caller roles are required");
}

@test:Config {groups: ["unit"]}
function testCommandFailHumanTaskRequiresReason() {
    json|Error result = executeCommand({
        operation: FAIL_HUMAN_TASK,
        params: {taskId: "humantask-x"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A missing reason must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "reason is required");
}

@test:Config {groups: ["unit"]}
function testCommandDecideRequiresInputForProceedWithInput() {
    json|Error result = executeCommand({
        operation: DECIDE_REVIEW_ACTIVITY,
        params: {taskId: "reviewactivity-x", action: "proceed-with-input"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A missing decision input must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "input must be a JSON object");
}

@test:Config {groups: ["unit"]}
function testCommandDecideRejectsUnknownAction() {
    json|Error result = executeCommand({
        operation: DECIDE_REVIEW_ACTIVITY,
        params: {taskId: "reviewactivity-x", action: "escalate"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "An unknown action must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "Unknown review decision action: escalate");
}

// ── Operation names on the wire ───────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testOperationConvertsFromWireName() returns error? {
    // A consumer that receives an operation as text converts it to the enum; the
    // string values are the stable wire vocabulary.
    Operation operation = check "humanTasks.complete".ensureType();
    test:assertEquals(operation, COMPLETE_HUMAN_TASK);
    test:assertEquals(COMPLETE_HUMAN_TASK, "humanTasks.complete");
}

@test:Config {groups: ["unit"]}
function testUnknownWireNameIsRejected() {
    Operation|error operation = "nope".ensureType();
    test:assertTrue(operation is error, "An unknown operation name must not convert to an Operation");
}

// ── Error representation ──────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testErrorJsonRepresentation() {
    test:assertEquals(toErrorJson(error NotFoundError("Workflow not found: wf-1")),
        <json>{"error": {"message": "Workflow not found: wf-1"}},
        "Every transport serializes errors through this one representation");
}

// ── Parameter typing ──────────────────────────────────────────────────────────
// A command may arrive from a channel that encodes scalars as text, so numeric
// parameters accept their string form; anything that cannot be coerced is
// reported instead of silently falling back to a default.

@test:Config {groups: ["unit"]}
function testNumericParamAcceptsStringForm() returns error? {
    map<json> normalized = check normalizeParams({"limit": "50", "status": "PENDING"});
    test:assertEquals(normalized["limit"], 50, "A string-encoded number must be coerced");
    test:assertEquals(normalized["status"], "PENDING");
}

@test:Config {groups: ["unit"]}
function testUncoercibleNumericParamIsReported() {
    map<json>|Error normalized = normalizeParams({"limit": "many"});
    test:assertTrue(normalized is InvalidRequestError,
        "A limit that is not a number must be reported, not replaced by the default");
    test:assertEquals((<Error>normalized).message(), "limit must be an integer");
}

@test:Config {groups: ["unit"]}
function testWrongTypedStringParamIsReported() {
    json|Error result = executeCommand({operation: GET_INSTANCE, params: {"workflowId": 42}});
    test:assertTrue(result is InvalidRequestError, "A non-string workflowId must be reported");
    test:assertEquals((<Error>result).message(), "workflowId must be a string",
        "The message must name the real cause, not report the parameter as missing");
}

@test:Config {groups: ["unit"]}
function testFreeFormParamsAcceptAnyJson() returns error? {
    map<json> normalized = check normalizeParams({"result": {"approved": true}, "details": [1, 2]});
    test:assertEquals(normalized["result"], <json>{"approved": true});
    test:assertEquals(normalized["details"], <json>[1, 2]);
}
