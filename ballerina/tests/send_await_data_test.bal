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
// sendData / awaitData payload validation unit tests
// ============================================================================
//
// `sendData` serialises a payload to a running workflow; the workflow receives
// it through an event `future<T>` and `await` coerces it to `T`. These tests
// complement data_roundtrip_test.bal (happy-path round-trips) by covering the
// empty (nil) and invalid-payload scenarios for the send -> await coercion path,
// which now runs through `TypesUtil.validateAndConvert` (ballerina-library#8866).
//
// `roundTripSendData` mirrors that full send -> receive -> coerce path.
// ============================================================================

import ballerina/test;

type SendApproval record {|
    boolean approved;
    string reviewer;
|};

type NilableInt int?;
type NilableSendApproval SendApproval?;
type SendIntArray int[];
type SendStringMap map<string>;

// ── Empty / nil payloads ─────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testSendNilToNilableFuture() returns error? {
    // A nilable event future may receive a nil value.
    int? result = check roundTripSendData((), NilableInt);
    test:assertTrue(result is (), "Nil should be accepted by an int? future");

    SendApproval? recResult = check roundTripSendData((), NilableSendApproval);
    test:assertTrue(recResult is (), "Nil should be accepted by a record? future");
}

@test:Config {groups: ["unit"]}
function testSendNilToNonNilableFutureFails() {
    // Sending nil to a non-nilable future must fail rather than deliver a nil
    // that later panics with a TypeCastError.
    int|error result = roundTripSendData((), int);
    test:assertTrue(result is error, "Nil to a non-nilable int future must fail");

    SendApproval|error recResult = roundTripSendData((), SendApproval);
    test:assertTrue(recResult is error, "Nil to a non-nilable record future must fail");
}

// ── Basic payloads (happy path) ──────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testSendBasicPayloads() returns error? {
    string s = check roundTripSendData("go");
    test:assertEquals(s, "go", "String payload should survive send/await");

    int i = check roundTripSendData(7);
    test:assertEquals(i, 7, "Int payload should survive send/await");

    boolean b = check roundTripSendData(false);
    test:assertEquals(b, false, "Boolean payload should survive send/await");

    decimal d = check roundTripSendData(2.5d);
    test:assertEquals(d, 2.5d, "Decimal payload should survive send/await");
}

// ── Complex payloads (happy path) ────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testSendComplexPayloads() returns error? {
    SendApproval rec = {approved: true, reviewer: "alice"};
    SendApproval recResult = check roundTripSendData(rec);
    test:assertEquals(recResult, rec, "Record payload should coerce to the future's record type");

    int[] arr = [10, 20, 30];
    int[] arrResult = check roundTripSendData(arr, SendIntArray);
    test:assertEquals(arrResult, arr, "Array payload should coerce to the future's array type");

    map<string> m = {"k1": "v1", "k2": "v2"};
    map<string> mResult = check roundTripSendData(m, SendStringMap);
    test:assertEquals(mResult, m, "Map payload should coerce to the future's map type");
}

// ── Invalid payloads (negative cases) ────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testSendRecordExpectedButStringProvidedFails() {
    SendApproval|error result = roundTripSendData("not-a-record", SendApproval);
    test:assertTrue(result is error, "String payload for a record future must fail");
}

@test:Config {groups: ["unit"]}
function testSendMapExpectedButArrayProvidedFails() {
    map<string>|error result = roundTripSendData([1, 2, 3], SendStringMap);
    test:assertTrue(result is error, "Array payload for a map future must fail");
}

@test:Config {groups: ["unit"]}
function testSendIntExpectedButStringProvidedFails() {
    int|error result = roundTripSendData("abc", int);
    test:assertTrue(result is error, "Non-numeric string for an int future must fail");
}

@test:Config {groups: ["unit"]}
function testSendRecordWithWrongFieldTypeFails() {
    map<json> wrong = {"approved": "yes", "reviewer": "alice"};
    SendApproval|error result = roundTripSendData(wrong, SendApproval);
    test:assertTrue(result is error, "Record payload with a wrong field type must fail");
}
