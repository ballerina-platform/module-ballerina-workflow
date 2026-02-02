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
// ARRAY PROCESSING WORKFLOW - TESTS
// ================================================================================

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testArrayProcessingWithLargerArray() returns error? {
    string testId = uniqueId("array-large");
    ArrayProcessInput input = {id: testId, numbers: [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]};
    string workflowId = check workflow:createInstance(arrayProcessingWorkflow, input);
    
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    test:assertEquals(execInfo.result, 550, "Sum should be 550");
}
