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
// Human task completion payload validation unit tests
// ============================================================================
//
// These tests cover the payload path used by `completeHumanTask` and the
// worker-side coercion in `awaitHumanTask`. Both now run through
// `TypesUtil.validateAndConvert`, which:
//   * accepts a nil completion when the task's expected type is nilable,
//   * rejects a nil completion when the expected type is non-nilable
//     (previously this produced a TypeCastError crash — ballerina-library#8866),
//   * coerces compatible basic/complex payloads to the expected type, and
//   * returns an error for payloads that do not match the expected type.
//
// `simulateHumanTaskCompletion` mirrors the runtime send -> receive -> validate
// round-trip so these assertions hold without a live workflow server.
// ============================================================================

import ballerina/jballerina.java;
import ballerina/test;

// Mirrors the completeHumanTask/awaitHumanTask payload path: the completion value
// is serialised, deserialised, and validated/coerced against the task's expected type `t`.
isolated function simulateHumanTaskCompletion(anydata result, typedesc<anydata> t = <>) returns t|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "simulateHumanTaskCompletion"
} external;

// Named aliases so complex/basic types can be passed as typedesc values.
type ApprovalDecision record {|
    boolean approved;
    string comment;
|};

type NestedApproval record {|
    ApprovalDecision decision;
    string[] reviewers;
    int level;
|};

type IntList int[];
type StringToInt map<int>;
type NilableApproval ApprovalDecision?;
type NilableString string?;

// ── Empty / nil completion ───────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCompleteWithNilForNilableType() returns error? {
    // Happy path: a task whose result type is nilable may be completed with ().
    ApprovalDecision? result = check simulateHumanTaskCompletion((), NilableApproval);
    test:assertTrue(result is (), "Nil completion should stay nil for a nilable result type");

    string? strResult = check simulateHumanTaskCompletion((), NilableString);
    test:assertTrue(strResult is (), "Nil completion should stay nil for string?");
}

@test:Config {groups: ["unit"]}
function testCompleteWithNilForNonNilableTypeFails() {
    // Regression for #8866: completing a non-nilable task with () must return an
    // error (not a nil that later panics with a TypeCastError).
    ApprovalDecision|error result = simulateHumanTaskCompletion((), ApprovalDecision);
    test:assertTrue(result is error, "Nil completion for a non-nilable record must fail");
    if result is error {
        test:assertTrue(result.message().includes("non-nil"),
                "Error should explain the non-nil requirement: " + result.message());
    }

    int|error intResult = simulateHumanTaskCompletion((), int);
    test:assertTrue(intResult is error, "Nil completion for a non-nilable int must fail");
}

// ── Basic payloads (happy path) ──────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCompleteWithBasicPayloads() returns error? {
    string s = check simulateHumanTaskCompletion("approved");
    test:assertEquals(s, "approved", "String completion should round-trip");

    int i = check simulateHumanTaskCompletion(42);
    test:assertEquals(i, 42, "Int completion should round-trip");

    boolean b = check simulateHumanTaskCompletion(true);
    test:assertEquals(b, true, "Boolean completion should round-trip");

    float f = check simulateHumanTaskCompletion(3.14);
    test:assertEquals(f, 3.14, "Float completion should round-trip");

    decimal d = check simulateHumanTaskCompletion(10.5d);
    test:assertEquals(d, 10.5d, "Decimal completion should round-trip");
}

// ── Complex payloads (happy path) ────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCompleteWithRecordPayload() returns error? {
    ApprovalDecision payload = {approved: true, comment: "LGTM"};
    ApprovalDecision result = check simulateHumanTaskCompletion(payload);
    test:assertEquals(result, payload, "Record completion should coerce to the expected record type");
}

@test:Config {groups: ["unit"]}
function testCompleteWithMapPayload() returns error? {
    map<int> payload = {"a": 1, "b": 2};
    map<int> result = check simulateHumanTaskCompletion(payload, StringToInt);
    test:assertEquals(result, payload, "Map completion should coerce to the expected map type");
}

@test:Config {groups: ["unit"]}
function testCompleteWithArrayPayload() returns error? {
    int[] payload = [1, 2, 3];
    int[] result = check simulateHumanTaskCompletion(payload, IntList);
    test:assertEquals(result, payload, "Array completion should coerce to the expected array type");
}

@test:Config {groups: ["unit"]}
function testCompleteWithNestedRecordPayload() returns error? {
    NestedApproval payload = {
        decision: {approved: true, comment: "ok"},
        reviewers: ["alice", "bob"],
        level: 2
    };
    NestedApproval result = check simulateHumanTaskCompletion(payload);
    test:assertEquals(result, payload, "Nested record completion should coerce to the expected type");
}

// ── Invalid payloads (negative cases) ────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testCompleteRecordExpectedButStringProvided() {
    // Expected a record, provided a string → must fail without completing.
    ApprovalDecision|error result = simulateHumanTaskCompletion("not-a-record", ApprovalDecision);
    test:assertTrue(result is error, "String payload for a record type must fail");
}

@test:Config {groups: ["unit"]}
function testCompleteMapExpectedButArrayProvided() {
    // Expected a map, provided an array → must fail.
    map<int>|error result = simulateHumanTaskCompletion([1, 2, 3], StringToInt);
    test:assertTrue(result is error, "Array payload for a map type must fail");
}

@test:Config {groups: ["unit"]}
function testCompleteIntExpectedButStringProvided() {
    int|error result = simulateHumanTaskCompletion("oops", int);
    test:assertTrue(result is error, "String payload for an int type must fail");
}

@test:Config {groups: ["unit"]}
function testCompleteRecordMissingRequiredFieldFails() {
    // A record payload missing a required field cannot be coerced to the sealed record.
    map<json> partial = {"approved": true};
    ApprovalDecision|error result = simulateHumanTaskCompletion(partial, ApprovalDecision);
    test:assertTrue(result is error, "Record payload missing a required field must fail");
}

@test:Config {groups: ["unit"]}
function testCompleteRecordWithWrongFieldTypeFails() {
    // 'approved' should be a boolean; a string there must be rejected.
    map<json> wrong = {"approved": "yes", "comment": "LGTM"};
    ApprovalDecision|error result = simulateHumanTaskCompletion(wrong, ApprovalDecision);
    test:assertTrue(result is error, "Record payload with a wrong field type must fail");
}
