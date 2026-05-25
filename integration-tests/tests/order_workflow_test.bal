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
// ORDER PROCESSING WORKFLOW - TESTS
// ================================================================================

import ballerina/test;
import ballerina/workflow;

@test:Config {
    groups: ["integration"]
}
function testOrderWorkflowWithRecordResult() returns error? {
    string testId = uniqueId("order-test");
    OrderInput input = {id: testId, orderId: "ORD-12345", quantity: 10};
    string workflowId = check workflow:run(orderWorkflow, input);
    
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-12345", "Order ID should match");
        test:assertEquals(result["status"], "PROCESSED", "Status should be PROCESSED");
        test:assertEquals(result["quantity"], 10, "Quantity should match");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}
