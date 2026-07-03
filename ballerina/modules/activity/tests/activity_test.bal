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
import ballerina/soap.soap11;
import ballerina/soap.soap12;
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
// Mock SOAP service — used to verify callSoapAPI dispatch (soap11 / soap12)
// ============================================================================

service /soap on activityTestListener {
    // SOAP 1.1 endpoint — responds with a SOAP 1.1 envelope.
    resource function post calc11() returns xml {
        return xml `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                        <soap:Body>
                            <quer:AddResponse xmlns:quer="http://tempuri.org/">
                                <quer:AddResult>5</quer:AddResult>
                            </quer:AddResponse>
                        </soap:Body>
                    </soap:Envelope>`;
    }

    // SOAP 1.2 endpoint — responds with a SOAP 1.2 envelope.
    resource function post calc12() returns xml {
        return xml `<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
                        <soap:Body>
                            <quer:AddResponse xmlns:quer="http://tempuri.org/">
                                <quer:AddResult>5</quer:AddResult>
                            </quer:AddResponse>
                        </soap:Body>
                    </soap:Envelope>`;
    }
}

final soap11:Client testSoap11Client = check new ("http://localhost:9797/soap/calc11");
final soap12:Client testSoap12Client = check new ("http://localhost:9797/soap/calc12");

final xml soapRequestBody = xml `<quer:Add xmlns:quer="http://tempuri.org/">
                                    <quer:intA>2</quer:intA>
                                    <quer:intB>3</quer:intB>
                                </quer:Add>`;

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

// ============================================================================
// callSoapAPI Tests — verifies SOAP 1.1 / 1.2 dispatch and validation
// ============================================================================

@test:Config {groups: ["unit"]}
function testCallSoapApiSoap11() returns error? {
    // soap11:Client branch + invokeSoap11 success path
    xml response = check callSoapAPI(testSoap11Client, soapRequestBody, "http://tempuri.org/Add");
    test:assertTrue(response.toString().includes("5"),
            "SOAP 1.1 response should carry the mocked AddResult");
}

@test:Config {groups: ["unit"]}
function testCallSoapApiSoap11RequiresAction() {
    // invokeSoap11 action-required guard — no network call is made
    xml|error response = callSoapAPI(testSoap11Client, soapRequestBody);
    test:assertTrue(response is error, "SOAP 1.1 without an action must return an error");
    if response is error {
        test:assertEquals(response.message(), "SOAP 1.1 requires the 'action' parameter.");
    }
}

@test:Config {groups: ["unit"]}
function testCallSoapApiSoap12() returns error? {
    // soap12:Client branch + invokeSoap12 success path (action is optional for SOAP 1.2)
    xml response = check callSoapAPI(testSoap12Client, soapRequestBody);
    test:assertTrue(response.toString().includes("5"),
            "SOAP 1.2 response should carry the mocked AddResult");
}
