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
// WORKFLOW INFO - TESTS
// ================================================================================

import ballerina/test;
import ballerina/lang.runtime;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowInfo() returns error? {
    string testId = uniqueId("info-test");
    InfoTestInput input = {id: testId, name: "Charlie"};
    string workflowId = check workflow:startProcess(infoTestWorkflow, input);
    
    // Give it a moment to start
    runtime:sleep(0.5);
    
    // Get workflow info (may still be running or completed)
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowInfo(workflowId);
    
    test:assertEquals(execInfo.workflowId, testId, "Workflow ID should match");
    test:assertTrue(execInfo.status == "RUNNING" || execInfo.status == "COMPLETED", 
        "Status should be RUNNING or COMPLETED");
}

@test:Config {
    groups: ["integration"]
}
function testGetWorkflowInfoAfterCompletion() returns error? {
    string testId = uniqueId("info-complete");
    InfoTestInput input = {id: testId, name: "Diana"};
    string workflowId = check workflow:startProcess(infoTestWorkflow, input);
    
    // Wait for completion
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should be completed");
    test:assertEquals(execInfo.workflowId, testId, "Workflow ID should match");
    test:assertEquals(execInfo.result, "Processed: Diana", "Result should match");
}
