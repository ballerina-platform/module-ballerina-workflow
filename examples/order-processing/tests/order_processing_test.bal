// Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/test;

type StartResponse record {|
    string status;
    string workflowId;
    string orderId;
    string message;
|};

type WorkflowResponse record {|
    string workflowId;
    string status;
    OrderResult result;
|};

final http:Client orderProcessingClient = check new ("http://localhost:9090/orders");

@test:Config {}
function testProcessOrderCompleted() returns error? {
    StartResponse startResp = check orderProcessingClient->post("/", {orderId: "TEST-ORD-001", item: "laptop", quantity: 2});
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    WorkflowResponse result = check orderProcessingClient->get(string `/${startResp.workflowId}/result`);
    test:assertEquals(result.status, "COMPLETED", "Workflow should complete successfully");
    test:assertEquals(result.result.status, "COMPLETED", "Order should be completed");
    test:assertNotEquals(result.result.reservationId, (), "Should have a reservation ID");
}

@test:Config {}
function testProcessOrderOutOfStock() returns error? {
    StartResponse startResp = check orderProcessingClient->post("/", {orderId: "TEST-ORD-002", item: "laptop", quantity: 1000});

    WorkflowResponse result = check orderProcessingClient->get(string `/${startResp.workflowId}/result`);
    test:assertEquals(result.status, "COMPLETED", "Workflow should complete even for out-of-stock");
    test:assertEquals(result.result.status, "OUT_OF_STOCK", "Order should be out of stock");
}
