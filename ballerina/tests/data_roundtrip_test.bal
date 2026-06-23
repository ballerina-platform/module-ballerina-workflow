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
// sendData round-trip unit tests
// ============================================================================
//
// These tests cover the conversion path used by `workflow:sendData`. The bug
// being guarded against: only record/map payloads round-tripped correctly,
// while primitives (boolean, int, string), json and xml were dropped/garbled.
//
// `roundTripSendData` mirrors the runtime: convert to Java on send, back to
// Ballerina on receive, then coerce to the receiving `future<T>` type.
// ============================================================================

import ballerina/jballerina.java;
import ballerina/test;

// Simulates the full sendData send -> receive -> convert round-trip.
isolated function roundTripSendData(anydata data, typedesc<anydata> t = <>) returns t|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "roundTripSendData"
} external;

@test:Config {groups: ["unit"]}
function testRoundTripBoolean() returns error? {
    boolean result = check roundTripSendData(true);
    test:assertEquals(result, true, "Boolean true should survive the round-trip");

    boolean result2 = check roundTripSendData(false);
    test:assertEquals(result2, false, "Boolean false should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripInt() returns error? {
    int result = check roundTripSendData(42);
    test:assertEquals(result, 42, "Int should survive the round-trip");

    int negative = check roundTripSendData(-7);
    test:assertEquals(negative, -7, "Negative int should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripFloatAndDecimal() returns error? {
    float result = check roundTripSendData(3.14);
    test:assertEquals(result, 3.14, "Float should survive the round-trip");

    decimal dec = check roundTripSendData(10.5d);
    test:assertEquals(dec, 10.5d, "Decimal should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripString() returns error? {
    string result = check roundTripSendData("approved");
    test:assertEquals(result, "approved", "String should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripJson() returns error? {
    json payload = {"approved": true, "score": 95, "note": "ok"};
    json result = check roundTripSendData(payload);
    test:assertEquals(result, payload, "JSON object should survive the round-trip");

    json scalar = check roundTripSendData(<json>123);
    test:assertEquals(scalar, 123, "Scalar JSON should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripXml() returns error? {
    xml payload = xml `<approval><status>approved</status></approval>`;
    xml result = check roundTripSendData(payload);
    test:assertEquals(result, payload, "XML should survive the round-trip");
}

@test:Config {groups: ["unit"]}
function testRoundTripArray() returns error? {
    int[] payload = [1, 2, 3];
    int[] result = check roundTripSendData(payload);
    test:assertEquals(result, payload, "Array should survive the round-trip");
}

type RoundTripRecord record {|
    string name;
    boolean approved;
|};

@test:Config {groups: ["unit"]}
function testRoundTripRecord() returns error? {
    RoundTripRecord payload = {name: "Jane", approved: true};
    RoundTripRecord result = check roundTripSendData(payload);
    test:assertEquals(result, payload, "Record should survive the round-trip");
}

type RoundTripRow record {|
    readonly int id;
    string name;
|};

@test:Config {groups: ["unit"]}
function testRoundTripTable() returns error? {
    table<RoundTripRow> key(id) payload = table [
        {id: 1, name: "Alice"},
        {id: 2, name: "Bob"}
    ];
    table<RoundTripRow> key(id) result = check roundTripSendData(payload);
    test:assertEquals(result, payload, "Table should survive the round-trip");
}
