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
// SCALAR / NON-RECORD DATA WORKFLOW TESTS
// ================================================================================
//
// Verifies that workflow:sendData round-trips non-record anydata payloads:
// boolean, int, string, json and xml. These guard against the regression where
// only record/map data was delivered correctly.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration", "signals"]
}
function testSendBooleanData() returns error? {
    string testId = uniqueId("bool-data-test");
    string workflowId = check workflow:run(booleanDataWorkflow, {id: testId});
    runtime:sleep(1);

    check workflow:sendData(booleanDataWorkflow, workflowId, "approved", true);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, true, "Boolean payload should be delivered to the workflow");
}

@test:Config {
    groups: ["integration", "signals"]
}
function testSendIntData() returns error? {
    string testId = uniqueId("int-data-test");
    string workflowId = check workflow:run(intDataWorkflow, {id: testId});
    runtime:sleep(1);

    check workflow:sendData(intDataWorkflow, workflowId, "count", 42);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, 42, "Int payload should be delivered to the workflow");
}

@test:Config {
    groups: ["integration", "signals"]
}
function testSendStringData() returns error? {
    string testId = uniqueId("string-data-test");
    string workflowId = check workflow:run(stringDataWorkflow, {id: testId});
    runtime:sleep(1);

    check workflow:sendData(stringDataWorkflow, workflowId, "note", "approved by manager");

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, "approved by manager", "String payload should be delivered to the workflow");
}

@test:Config {
    groups: ["integration", "signals"]
}
function testSendJsonData() returns error? {
    string testId = uniqueId("json-data-test");
    string workflowId = check workflow:run(jsonDataWorkflow, {id: testId});
    runtime:sleep(1);

    json payload = {"approved": true, "score": 95, "reviewer": "manager-1"};
    check workflow:sendData(jsonDataWorkflow, workflowId, "payload", payload);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, payload, "JSON payload should be delivered to the workflow");
}

@test:Config {
    groups: ["integration", "signals"]
}
function testSendXmlData() returns error? {
    string testId = uniqueId("xml-data-test");
    string workflowId = check workflow:run(xmlDataWorkflow, {id: testId});
    runtime:sleep(1);

    xml payload = xml `<approval><status>approved</status><by>manager-1</by></approval>`;
    check workflow:sendData(xmlDataWorkflow, workflowId, "document", payload);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, payload, "XML payload should be delivered to the workflow");
}

@test:Config {
    groups: ["integration", "signals"]
}
function testSendTableData() returns error? {
    string testId = uniqueId("table-data-test");
    string workflowId = check workflow:run(tableDataWorkflow, {id: testId});
    runtime:sleep(1);

    table<TableRow> key(id) payload = table [
        {id: 1, name: "Alice"},
        {id: 2, name: "Bob"},
        {id: 3, name: "Carol"}
    ];
    check workflow:sendData(tableDataWorkflow, workflowId, "rows", payload);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, payload, "Table payload (rows and key) should be delivered intact to the workflow");
}
