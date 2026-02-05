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
// Tests for readonly field based correlation in workflows.
// These tests verify that:
// 1. Workflows can use readonly fields as correlation keys
// 2. Signals with matching correlation keys are routed correctly
// 3. Composite workflow IDs are generated from correlation keys
// 4. Multiple signals can be sent using correlation keys
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// SIMPLE CORRELATION WORKFLOW TESTS
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
    
    // Start the workflow - workflow ID is timestamp-based, correlation keys are search attributes
    string workflowId = check workflow:createInstance(simpleCorrelatedWorkflow, input);
    
    // Verify the workflow ID starts with the process name
    // Format: simpleCorrelatedWorkflow-<timestamp>
    test:assertTrue(workflowId.startsWith("simpleCorrelatedWorkflow-"), 
        "Workflow ID should start with process name");
    
    // Give the workflow time to start and wait for signal
    runtime:sleep(1);
    
    // Send the response signal using correlation key
    SimpleCorrelatedResponse signalData = {
        requestId: requestId,
        response: "Correlated response!"
    };
    boolean sent = check workflow:sendEvent(simpleCorrelatedWorkflow, signalData, "response");
    test:assertTrue(sent, "Signal should be sent successfully");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["requestId"], requestId, "Request ID should match");
        test:assertEquals(result["originalMessage"], "Hello with correlation", 
                "Original message should be preserved");
        test:assertEquals(result["response"], "Correlated response!", 
                "Signal response should be captured");
    } else {
        test:assertFail("Result should be a map representing SimpleCorrelatedResult");
    }
}

// ================================================================================
// MULTI-KEY CORRELATION WORKFLOW TESTS
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
    
    // Start the workflow - workflow ID is timestamp-based, correlation keys are search attributes
    string workflowId = check workflow:createInstance(correlatedOrderWorkflow, input);
    
    // Verify the workflow ID starts with the process name (correlation keys are in search attributes)
    test:assertTrue(workflowId.startsWith("correlatedOrderWorkflow-"), 
        "Workflow ID should start with process name");
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send payment signal with matching correlation keys
    CorrelatedPaymentSignal payment = {
        customerId: customerId,
        orderId: orderId,
        txnId: "TXN-123",
        amount: 999.99,
        paymentMethod: "CREDIT_CARD"
    };
    boolean paymentSent = check workflow:sendEvent(correlatedOrderWorkflow, payment, "payment");
    test:assertTrue(paymentSent, "Payment signal should be sent successfully");
    
    // Give workflow time to process payment
    runtime:sleep(1);
    
    // Send shipment signal with matching correlation keys
    CorrelatedShipmentSignal shipment = {
        customerId: customerId,
        orderId: orderId,
        trackingNumber: "TRACK-456",
        carrier: "FedEx"
    };
    boolean shipmentSent = check workflow:sendEvent(correlatedOrderWorkflow, shipment, "shipment");
    test:assertTrue(shipmentSent, "Shipment signal should be sent successfully");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["customerId"], customerId, "Customer ID should match");
        test:assertEquals(result["orderId"], orderId, "Order ID should match");
        test:assertEquals(result["status"], "COMPLETED", "Status should be COMPLETED");
        test:assertEquals(result["txnId"], "TXN-123", "Transaction ID should be captured");
        test:assertEquals(result["trackingNumber"], "TRACK-456", "Tracking number should be captured");
    } else {
        test:assertFail("Result should be a map representing CorrelatedOrderResult");
    }
}

// ================================================================================
// CORRELATION WITH INVALID PAYMENT
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
    string workflowId = check workflow:createInstance(correlatedOrderWorkflow, input);
    
    runtime:sleep(1);
    
    // Send payment signal with zero amount (should fail validation)
    CorrelatedPaymentSignal payment = {
        customerId: customerId,
        orderId: orderId,
        txnId: "TXN-INVALID",
        amount: 0.0,  // Invalid amount
        paymentMethod: "CASH"
    };
    boolean paymentSent = check workflow:sendEvent(correlatedOrderWorkflow, payment, "payment");
    test:assertTrue(paymentSent, "Payment signal should be sent");
    
    // Wait for workflow to complete (with payment invalid status)
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "PAYMENT_INVALID", "Status should be PAYMENT_INVALID");
        test:assertEquals(result["txnId"], "TXN-INVALID", "Transaction ID should be captured");
        test:assertEquals(result["trackingNumber"], (), "Tracking number should be nil");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// MULTIPLE CONCURRENT WORKFLOWS WITH DIFFERENT CORRELATION KEYS
// ================================================================================

@test:Config {
    groups: ["integration", "correlation"]
}
function testMultipleConcurrentCorrelatedWorkflows() returns error? {
    // Start two workflows with different correlation keys
    string requestId1 = uniqueId("req1");
    string requestId2 = uniqueId("req2");
    
    SimpleCorrelatedInput input1 = {requestId: requestId1, message: "First workflow"};
    SimpleCorrelatedInput input2 = {requestId: requestId2, message: "Second workflow"};
    
    string workflowId1 = check workflow:createInstance(simpleCorrelatedWorkflow, input1);
    string workflowId2 = check workflow:createInstance(simpleCorrelatedWorkflow, input2);
    
    runtime:sleep(1);
    
    // Send signals - each should go to the correct workflow based on correlation
    SimpleCorrelatedResponse signal1 = {requestId: requestId1, response: "Response 1"};
    SimpleCorrelatedResponse signal2 = {requestId: requestId2, response: "Response 2"};
    
    // Send in reverse order to verify correct correlation routing
    boolean sent2 = check workflow:sendEvent(simpleCorrelatedWorkflow, signal2, "response");
    boolean sent1 = check workflow:sendEvent(simpleCorrelatedWorkflow, signal1, "response");
    
    test:assertTrue(sent1 && sent2, "Both signals should be sent");
    
    // Verify each workflow got the correct signal
    workflow:WorkflowExecutionInfo execInfo1 = check workflow:getWorkflowResult(workflowId1, 30);
    workflow:WorkflowExecutionInfo execInfo2 = check workflow:getWorkflowResult(workflowId2, 30);
    
    test:assertEquals(execInfo1.status, "COMPLETED", "First workflow should complete");
    test:assertEquals(execInfo2.status, "COMPLETED", "Second workflow should complete");
    
    if execInfo1.result is map<anydata> {
        map<anydata> result1 = <map<anydata>>execInfo1.result;
        test:assertEquals(result1["response"], "Response 1", 
            "First workflow should get Response 1");
    }
    
    if execInfo2.result is map<anydata> {
        map<anydata> result2 = <map<anydata>>execInfo2.result;
        test:assertEquals(result2["response"], "Response 2", 
            "Second workflow should get Response 2");
    }
}
