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
// CORRELATION WORKFLOW - TESTS
// ================================================================================
//
// Tests for multi-signal workflows with multiple signal types.
// These tests verify that:
// 1. Workflows can receive multiple signal types
// 2. Signals are routed correctly using workflowId
// 3. Workflow IDs are generated as UUID v7
// 4. Multiple signals can be sent to the same workflow
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// SIMPLE CORRELATED WORKFLOW TESTS
// ================================================================================

@test:Config {
    groups: ["integration", "correlation"]
}
function testSimpleCorrelatedWorkflow() returns error? {
    string requestId = uniqueId("corr-simple-test");
    
    SimpleCorrelatedInput input = {
        requestId: requestId,
        message: "Hello with correlation"
    };
    
    // Start the workflow - workflow ID is timestamp-based, fields are search attributes
    string workflowId = check workflow:run(simpleCorrelatedWorkflow, input);
    
    // Verify the workflow ID is a valid UUID v7
    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");
    
    // Give the workflow time to start and wait for signal
    runtime:sleep(1);
    
    // Send the response signal using workflowId directly
    SimpleCorrelatedResponse signalData = {
        requestId: requestId,
        response: "Correlated response!"
    };
    check workflow:sendData(simpleCorrelatedWorkflow, workflowId, "response", signalData);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["requestId"], requestId, "Request ID should match");
        test:assertEquals(result["originalMessage"], "Hello with correlation",
                "Original message should be preserved");
        test:assertEquals(result["response"], "Correlated response!",
                "Signal response should be captured");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// MULTI-SIGNAL WORKFLOW TESTS
// ================================================================================

@test:Config {
    groups: ["integration", "correlation"]
}
function testCorrelatedOrderWorkflow() returns error? {
    string customerId = uniqueId("customer");
    string orderId = uniqueId("order");
    
    CorrelatedOrderInput input = {
        customerId: customerId,
        orderId: orderId,
        product: "Laptop",
        quantity: 1,
        price: 999.99
    };
    
    // Start the workflow - workflow ID is timestamp-based, fields are search attributes
    string workflowId = check workflow:run(correlatedOrderWorkflow, input);
    
    // Verify the workflow ID is a valid UUID v7
    test:assertTrue(isValidUuidV7(workflowId), "Workflow ID should be a valid UUID v7");
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send payment signal with workflowId
    CorrelatedPaymentSignal payment = {
        customerId: customerId,
        orderId: orderId,
        txnId: "TXN-123",
        amount: 999.99,
        paymentMethod: "CREDIT_CARD"
    };
    check workflow:sendData(correlatedOrderWorkflow, workflowId, "payment", payment);
    
    // Give workflow time to process payment
    runtime:sleep(1);
    
    // Send shipment signal with workflowId
    CorrelatedShipmentSignal shipment = {
        customerId: customerId,
        orderId: orderId,
        trackingNumber: "TRACK-456",
        carrier: "FedEx"
    };
    check workflow:sendData(correlatedOrderWorkflow, workflowId, "shipment", shipment);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["customerId"], customerId, "Customer ID should match");
        test:assertEquals(result["orderId"], orderId, "Order ID should match");
        test:assertEquals(result["status"], "COMPLETED", "Status should be COMPLETED");
        test:assertEquals(result["txnId"], "TXN-123", "Transaction ID should be captured");
        test:assertEquals(result["trackingNumber"], "TRACK-456", "Tracking number should be captured");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// INVALID PAYMENT TEST
// ================================================================================

@test:Config {
    groups: ["integration", "correlation"]
}
function testCorrelatedOrderWorkflowInvalidPayment() returns error? {
    string customerId = uniqueId("customer");
    string orderId = uniqueId("order");
    
    CorrelatedOrderInput input = {
        customerId: customerId,
        orderId: orderId,
        product: "Phone",
        quantity: 1,
        price: 500.00
    };
    
    // Start the workflow
    string workflowId = check workflow:run(correlatedOrderWorkflow, input);
    
    runtime:sleep(1);
    
    // Send payment signal with zero amount (should fail validation)
    CorrelatedPaymentSignal payment = {
        customerId: customerId,
        orderId: orderId,
        txnId: "TXN-INVALID",
        amount: 0.0,  // Invalid amount
        paymentMethod: "CASH"
    };
    check workflow:sendData(correlatedOrderWorkflow, workflowId, "payment", payment);
    
    // Wait for workflow to complete (with payment invalid status)
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["status"], "PAYMENT_INVALID", "Status should be PAYMENT_INVALID");
        test:assertEquals(result["txnId"], "TXN-INVALID", "Transaction ID should be captured");
        test:assertEquals(result["trackingNumber"], (), "Tracking number should be nil");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// MULTIPLE CONCURRENT WORKFLOWS
// ================================================================================

@test:Config {
    groups: ["integration", "correlation"]
}
function testMultipleConcurrentCorrelatedWorkflows() returns error? {
    // Start two workflows
    string requestId1 = uniqueId("req1");
    string requestId2 = uniqueId("req2");
    
    SimpleCorrelatedInput input1 = {requestId: requestId1, message: "First workflow"};
    SimpleCorrelatedInput input2 = {requestId: requestId2, message: "Second workflow"};
    
    string workflowId1 = check workflow:run(simpleCorrelatedWorkflow, input1);
    string workflowId2 = check workflow:run(simpleCorrelatedWorkflow, input2);
    
    runtime:sleep(1);
    
    // Send signals using workflowId directly
    SimpleCorrelatedResponse signal1 = {requestId: requestId1, response: "Response 1"};
    SimpleCorrelatedResponse signal2 = {requestId: requestId2, response: "Response 2"};
    
    // Send signal to second workflow first, then first (out of order)
    check workflow:sendData(simpleCorrelatedWorkflow, workflowId2, "response", signal2);
    check workflow:sendData(simpleCorrelatedWorkflow, workflowId1, "response", signal1);
    
    // Verify each workflow got the correct signal
    anydata result1 = check workflow:getWorkflowResult(workflowId1, 30);
    anydata result2 = check workflow:getWorkflowResult(workflowId2, 30);

    if result1 is map<anydata> {
        test:assertEquals(result1["response"], "Response 1",
            "First workflow should get Response 1");
    } else {
        test:assertFail("Expected map<anydata> result for first workflow");
    }

    if result2 is map<anydata> {
        test:assertEquals(result2["response"], "Response 2",
            "Second workflow should get Response 2");
    } else {
        test:assertFail("Expected map<anydata> result for second workflow");
    }
}
