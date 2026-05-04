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
// BUILT-IN CONNECTOR ACTIVITIES — TESTS
// ================================================================================
// End-to-end tests that exercise `workflow.activity:callRestAPI` against an
// in-process HTTP mock listener. These tests prove that:
//   1. A module-level `final http:Client` is registered as a workflow connection
//      (via compiler-plugin emitted `wfInternal:registerConnection`).
//   2. The connection BObject is replaced with the `"connection:<name>"` marker
//      when crossing the Temporal serialization boundary, and resolved back on
//      the activity worker side.
//   3. The builtin activity invokes the underlying HTTP client and returns the
//      response payload to the workflow.

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testCallRestAPIGet() returns error? {
    string testId = uniqueId("rest-get");
    ConnectorInput input = {id: testId, userId: 1};
    string workflowId = check workflow:run(fetchUserWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED",
            "fetchUserWorkflow should complete. Error: "
                    + (execInfo.errorMessage ?: "none"));

    json expected = {id: 1, name: "Alice"};
    test:assertEquals(execInfo.result, expected,
            "Builtin callRestAPI activity should return the mocked JSON body");
}

@test:Config {
    groups: ["integration"]
}
function testCallRestAPIPost() returns error? {
    string testId = uniqueId("rest-post");
    EchoInput input = {id: testId, payload: {message: "hello", count: 3}};
    string workflowId = check workflow:run(echoWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED",
            "echoWorkflow should complete. Error: "
                    + (execInfo.errorMessage ?: "none"));

    json expected = {echo: {message: "hello", count: 3}};
    test:assertEquals(execInfo.result, expected,
            "Builtin callRestAPI activity should round-trip the POST payload");
}
