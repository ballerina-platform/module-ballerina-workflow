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
// ctx->await TUPLE-BINDING / UNION-LHS TESTS
// ================================================================================
//
// Validates the dependent-typing ergonomics at runtime:
//   - tuple-binding pattern off check ctx->await(...)  (without error)
//   - union LHS without check, error (timeout) path     (with error)
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// Binding pattern, both approve → APPROVED (without error).
@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitBindingBothApprove() returns error? {
    string testId = uniqueId("await-bind-approve");
    string workflowId = check workflow:run(awaitBindingWorkflow, {id: testId});
    runtime:sleep(2);

    check workflow:sendData(awaitBindingWorkflow, workflowId, "approverA",
            <WaitDecision>{approverId: "alice", approved: true});
    check workflow:sendData(awaitBindingWorkflow, workflowId, "approverB",
            <WaitDecision>{approverId: "bob", approved: true});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    if result is map<anydata> {
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "both");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// Binding pattern, second rejects → REJECTED by bob (without error).
@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitBindingSecondRejects() returns error? {
    string testId = uniqueId("await-bind-reject");
    string workflowId = check workflow:run(awaitBindingWorkflow, {id: testId});
    runtime:sleep(2);

    check workflow:sendData(awaitBindingWorkflow, workflowId, "approverA",
            <WaitDecision>{approverId: "alice", approved: true});
    check workflow:sendData(awaitBindingWorkflow, workflowId, "approverB",
            <WaitDecision>{approverId: "bob", approved: false});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    if result is map<anydata> {
        test:assertEquals(result["status"], "REJECTED");
        test:assertEquals(result["decidedBy"], "bob");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// Union LHS, no check — both signals arrive → APPROVED (without error).
@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitUnionNoCheckCompletes() returns error? {
    string testId = uniqueId("await-union-ok");
    string workflowId = check workflow:run(awaitUnionNoCheckWorkflow, {id: testId});
    runtime:sleep(2);

    check workflow:sendData(awaitUnionNoCheckWorkflow, workflowId, "approverA",
            <WaitDecision>{approverId: "alice", approved: true});
    check workflow:sendData(awaitUnionNoCheckWorkflow, workflowId, "approverB",
            <WaitDecision>{approverId: "bob", approved: true});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    if result is map<anydata> {
        test:assertEquals(result["status"], "APPROVED");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// Union LHS, no check — no signals → timeout error path → TIMED_OUT (with error).
@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitUnionNoCheckTimesOut() returns error? {
    string testId = uniqueId("await-union-timeout");
    string workflowId = check workflow:run(awaitUnionNoCheckWorkflow, {id: testId});
    // Send nothing — the workflow's 5s await timeout fires and is handled as a value.

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    if result is map<anydata> {
        test:assertEquals(result["status"], "TIMED_OUT");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// Per-position error tuple — both positions resolve to values → APPROVED.
// Verifies the native builds an [A|error, B|error] tuple with the converted values in place.
@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitPerPositionErrorBothValues() returns error? {
    string testId = uniqueId("await-perpos");
    string workflowId = check workflow:run(awaitPerPositionErrorWorkflow, {id: testId});
    runtime:sleep(2);

    check workflow:sendData(awaitPerPositionErrorWorkflow, workflowId, "approverA",
            <WaitDecision>{approverId: "alice", approved: true});
    check workflow:sendData(awaitPerPositionErrorWorkflow, workflowId, "approverB",
            <WaitDecision>{approverId: "bob", approved: true});

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    if result is map<anydata> {
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "both");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}
