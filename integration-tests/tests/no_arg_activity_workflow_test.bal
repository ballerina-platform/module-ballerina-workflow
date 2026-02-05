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
// NO-ARG ACTIVITY WORKFLOW - TESTS
// ================================================================================
// 
// Tests for workflows that call activities with no parameters.
// These tests validate that activities can be called with empty args {}.
//
// ================================================================================

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testSingleNoArgActivityExecution() returns error? {
    string testId = uniqueId("no-arg-single");
    NoArgWorkflowInput input = {id: testId};
    string workflowId = check workflow:createInstance(singleNoArgActivityWorkflow, input);
    
    // Verify workflow ID is generated
    test:assertTrue(workflowId.length() > 0, "Workflow ID should be generated");
    test:assertTrue(workflowId.startsWith("singleNoArgActivityWorkflow-"), 
        "Workflow ID should be prefixed with process name");
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    test:assertEquals(execInfo.result, "Hello from no-arg activity!", 
        "Result should match the static greeting");
}

@test:Config {
    groups: ["integration"]
}
function testMultipleNoArgActivitiesExecution() returns error? {
    string testId = uniqueId("no-arg-multi");
    NoArgWorkflowInput input = {id: testId};
    string workflowId = check workflow:createInstance(multipleNoArgActivitiesWorkflow, input);
    
    // Verify workflow ID is generated
    test:assertTrue(workflowId.length() > 0, "Workflow ID should be generated");
    test:assertTrue(workflowId.startsWith("multipleNoArgActivitiesWorkflow-"), 
        "Workflow ID should be prefixed with process name");
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    // Verify the combined result from multiple no-arg activities
    string expectedResult = "Hello from no-arg activity! Number: 42, Version: 1.0.0, Enabled: true";
    test:assertEquals(execInfo.result, expectedResult, 
        "Result should contain combined output from all activities");
}
