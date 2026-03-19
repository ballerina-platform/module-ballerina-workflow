// Copyright (c) 2025 WSO2 LLC. (http://www.wso2.com).
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
import ballerina/lang.runtime;
import ballerina/test;

type StartResponse record {|
    string status;
    string workflowId;
    string orderId;
    string message;
|};

type PaymentResponse record {|
    string status;
    string message;
|};

type WorkflowResponse record {|
    string workflowId;
    string status;
    OrderResult result;
|};

final http:Client orderPaymentClient = check new ("http://localhost:9094/orders");

@test:Config {}
function testOrderWithPaymentCompleted() returns error? {
    StartResponse startResp = check orderPaymentClient->post("/", {orderId: "TEST-PAY-001", item: "laptop"});
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Give the workflow time to start and begin waiting for the payment data
    runtime:sleep(2);

    // Send payment data via HTTP
    PaymentResponse _ = check orderPaymentClient->post("/TEST-PAY-001/payment", {amount: 1999.99});

    WorkflowResponse result = check orderPaymentClient->get(string `/${startResp.workflowId}/result`);
    test:assertEquals(result.status, "COMPLETED", "Workflow should complete successfully");
    test:assertEquals(result.result.status, "COMPLETED", "Order should be completed after payment");
    test:assertEquals(result.result.orderId, "TEST-PAY-001", "Order ID should match");
}

@test:Config {}
function testOrderOutOfStockNoPaymentNeeded() returns error? {
    StartResponse startResp = check orderPaymentClient->post("/", {orderId: "TEST-PAY-002", item: "keyboard"});

    // Wait and send payment
    runtime:sleep(2);

    PaymentResponse _ = check orderPaymentClient->post("/TEST-PAY-002/payment", {amount: 49.99});

    WorkflowResponse result = check orderPaymentClient->get(string `/${startResp.workflowId}/result`);
    test:assertEquals(result.status, "COMPLETED", "Workflow should complete");
    test:assertEquals(result.result.status, "COMPLETED", "Order should complete");
}
