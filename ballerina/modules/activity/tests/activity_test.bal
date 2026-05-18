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

import ballerina/http;
import ballerina/test;

// ============================================================================
// Mock HTTP service — used to verify callRestAPIDispatch method routing
// ============================================================================

listener http:Listener activityTestListener = new (9797);

service /mock on activityTestListener {
    resource function get items() returns json {
        return {"method": "GET"};
    }
    resource function post items(@http:Payload json payload) returns json {
        return {"method": "POST", "received": payload};
    }
    resource function put items(@http:Payload json payload) returns json {
        return {"method": "PUT", "received": payload};
    }
    resource function delete items() returns json {
        return {"method": "DELETE"};
    }
    resource function patch items(@http:Payload json payload) returns json {
        return {"method": "PATCH", "received": payload};
    }
}

final http:Client testHttpClient = check new ("http://localhost:9797/mock");

// ============================================================================
// RestMethod Enum Tests
// ============================================================================

@test:Config {groups: ["unit"]}
function testRestMethodGetValue() {
    test:assertEquals(GET, "GET", "GET enum member must have string value \"GET\"");
}

@test:Config {groups: ["unit"]}
function testRestMethodPostValue() {
    test:assertEquals(POST, "POST", "POST enum member must have string value \"POST\"");
}

@test:Config {groups: ["unit"]}
function testRestMethodPutValue() {
    test:assertEquals(PUT, "PUT", "PUT enum member must have string value \"PUT\"");
}

@test:Config {groups: ["unit"]}
function testRestMethodDeleteValue() {
    test:assertEquals(DELETE, "DELETE", "DELETE enum member must have string value \"DELETE\"");
}

@test:Config {groups: ["unit"]}
function testRestMethodPatchValue() {
    test:assertEquals(PATCH, "PATCH", "PATCH enum member must have string value \"PATCH\"");
}

@test:Config {groups: ["unit"]}
function testRestMethodAssignableToString() {
    RestMethod method = GET;
    string s = method;
    test:assertEquals(s, "GET", "RestMethod enum value must be assignable to string");
}

// ============================================================================
// callRestAPIDispatch Tests — verifies HTTP method routing for each verb
// ============================================================================

@test:Config {groups: ["unit"]}
function testDispatchGet() returns error? {
    anydata result = check callRestAPIDispatch(testHttpClient, GET, "/items", (), (), json);
    test:assertEquals(result, {"method": "GET"});
}

@test:Config {groups: ["unit"]}
function testDispatchPost() returns error? {
    json body = {"key": "value"};
    anydata result = check callRestAPIDispatch(testHttpClient, POST, "/items", body, (), json);
    map<json> m = check (<json>result).ensureType();
    test:assertEquals(m["method"], "POST");
    test:assertEquals(m["received"], body);
}

@test:Config {groups: ["unit"]}
function testDispatchPut() returns error? {
    json body = {"key": "updated"};
    anydata result = check callRestAPIDispatch(testHttpClient, PUT, "/items", body, (), json);
    map<json> m = check (<json>result).ensureType();
    test:assertEquals(m["method"], "PUT");
    test:assertEquals(m["received"], body);
}

@test:Config {groups: ["unit"]}
function testDispatchDelete() returns error? {
    anydata result = check callRestAPIDispatch(testHttpClient, DELETE, "/items", (), (), json);
    test:assertEquals(result, {"method": "DELETE"});
}

@test:Config {groups: ["unit"]}
function testDispatchPatch() returns error? {
    json body = {"key": "patched"};
    anydata result = check callRestAPIDispatch(testHttpClient, PATCH, "/items", body, (), json);
    map<json> m = check (<json>result).ensureType();
    test:assertEquals(m["method"], "PATCH");
    test:assertEquals(m["received"], body);
}
