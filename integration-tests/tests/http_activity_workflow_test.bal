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
// HTTP ACTIVITY WORKFLOW - TESTS
// ================================================================================
// 
// Integration tests for callRemoteActivity and callResourceActivity using
// a local HTTP service. These tests verify that HTTP client methods can be
// called as workflow activities through the Temporal engine.
//
// ================================================================================

import ballerina/test;
import ballerina/workflow;

// ================================================================================
// callRemoteActivity Tests
// ================================================================================

@test:Config {
    groups: ["integration", "http-activity"]
}
function testHttpRemotePostActivity() returns error? {
    string testId = uniqueId("http-remote-post");
    HttpPostInput input = {id: testId, name: "Alice", email: "alice@test.com"};
    string workflowId = check workflow:run(httpRemotePostWorkflow, input);

    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
}

// ================================================================================
// callResourceActivity Tests
// ================================================================================

@test:Config {
    groups: ["integration", "http-activity"]
}
function testHttpResourceGetActivity() returns error? {
    string testId = uniqueId("http-resource-get");
    HttpActivityInput input = {id: testId};
    string workflowId = check workflow:run(httpResourceGetWorkflow, input);

    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    if execInfo.status == "FAILED" {
        test:assertFail("Workflow failed: " + (execInfo.errorMessage ?: "unknown error"));
    }
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
}

@test:Config {
    groups: ["integration", "http-activity"]
}
function testHttpResourcePostActivity() returns error? {
    string testId = uniqueId("http-resource-post");
    HttpPostInput input = {id: testId, name: "Bob", email: "bob@test.com"};
    string workflowId = check workflow:run(httpResourcePostWorkflow, input);

    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    if execInfo.status == "FAILED" {
        test:assertFail("Workflow failed: " + (execInfo.errorMessage ?: "unknown error"));
    }
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
}

@test:Config {
    groups: ["integration", "http-activity"]
}
function testHttpResourceGreetActivity() returns error? {
    string testId = uniqueId("http-resource-greet");
    HttpPostInput input = {id: testId, name: "Charlie", email: "charlie@test.com"};
    string workflowId = check workflow:run(httpResourceGreetWorkflow, input);

    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);

    if execInfo.status == "FAILED" {
        test:assertFail("Workflow failed: " + (execInfo.errorMessage ?: "unknown error"));
    }
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
}
