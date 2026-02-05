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
// ERROR HANDLING WORKFLOW - TESTS
// ================================================================================

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testActivityErrorHandling() returns error? {
    // Test with failing activity - error should be caught and handled
    string testId1 = uniqueId("error-test-fail");
    ErrorHandlingInput input1 = {id: testId1, shouldFail: true};
    string workflowId1 = check workflow:createInstance(errorHandlingWorkflow, input1);
    
    workflow:WorkflowExecutionInfo execInfo1 = check workflow:getWorkflowResult(workflowId1, 30);
    
    test:assertEquals(execInfo1.status, "COMPLETED", "Workflow should complete (error was handled)");
    test:assertTrue((<string>execInfo1.result).startsWith("Activity error caught:"), 
        "Result should indicate error was caught");
    
    // Test with successful path
    string testId2 = uniqueId("error-test-success");
    ErrorHandlingInput input2 = {id: testId2, shouldFail: false};
    string workflowId2 = check workflow:createInstance(errorHandlingWorkflow, input2);
    
    workflow:WorkflowExecutionInfo execInfo2 = check workflow:getWorkflowResult(workflowId2, 30);
    
    test:assertEquals(execInfo2.status, "COMPLETED", "Workflow should complete successfully");
    test:assertEquals(execInfo2.result, "Hello, World!", "Result should be the greeting");
}
