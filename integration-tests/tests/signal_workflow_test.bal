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
// SIGNAL HANDLING WORKFLOW - TESTS
// ================================================================================
//
// Tests for future-based signal handling in workflows.
// These tests verify that signals can be sent to running workflows
// and that the workflow correctly waits for and processes signals.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// SIMPLE SIGNAL WORKFLOW TESTS
// ================================================================================

@test:Config {
    groups: ["integration", "signals"]
}
function testSimpleSignalWorkflow() returns error? {
    string testId = uniqueId("simple-signal-test");
    SimpleSignalInput input = {id: testId, message: "Hello from workflow"};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(simpleSignalWorkflow, input);
    
    // Give the workflow time to start and begin waiting for signal
    runtime:sleep(1);
    
    // Send the response signal
    SimpleSignalData signalData = {id: testId, response: "Response received!"};
    boolean sent = check workflow:sendEvent(simpleSignalWorkflow, signalData, "response");
    test:assertTrue(sent, "Signal should be sent successfully");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["originalMessage"], "Hello from workflow", 
                "Original message should be preserved");
        test:assertEquals(result["response"], "Response received!", 
                "Signal response should be captured");
    } else {
        test:assertFail("Result should be a map representing SimpleSignalResult");
    }
}

// ================================================================================
// APPROVAL WORKFLOW TESTS - APPROVED PATH
// ================================================================================

@test:Config {
    groups: ["integration", "signals"]
}
function testApprovalWorkflowApprovedPath() returns error? {
    string testId = uniqueId("approval-approved-test");
    ApprovalInput input = {id: testId, orderId: "ORD-001", amount: 100.0};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(approvalWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send approval signal
    ApprovalSignal approval = {
        id: testId, 
        approverId: "manager-123", 
        approved: true, 
        reason: ()
    };
    boolean approvalSent = check workflow:sendEvent(approvalWorkflow, approval, "approval");
    test:assertTrue(approvalSent, "Approval signal should be sent successfully");
    
    // Give workflow time to process approval and start waiting for payment
    runtime:sleep(1);
    
    // Send payment signal
    PaymentSignal payment = {
        id: testId,
        txnId: "TXN-456",
        amount: 100.0
    };
    boolean paymentSent = check workflow:sendEvent(approvalWorkflow, payment, "payment");
    test:assertTrue(paymentSent, "Payment signal should be sent successfully");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["orderId"], "ORD-001", "Order ID should match");
        test:assertEquals(result["status"], "COMPLETED", "Status should be COMPLETED");
        test:assertEquals(result["approvedBy"], "manager-123", "Approver should be captured");
        test:assertEquals(result["txnId"], "TXN-456", "Transaction ID should be captured");
    } else {
        test:assertFail("Result should be a map representing ApprovalResult");
    }
}

// ================================================================================
// APPROVAL WORKFLOW TESTS - REJECTED PATH
// ================================================================================

@test:Config {
    groups: ["integration", "signals"]
}
function testApprovalWorkflowRejectedPath() returns error? {
    string testId = uniqueId("approval-rejected-test");
    ApprovalInput input = {id: testId, orderId: "ORD-002", amount: 5000.0};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(approvalWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send rejection signal
    ApprovalSignal rejection = {
        id: testId, 
        approverId: "manager-456", 
        approved: false, 
        reason: "Amount too high"
    };
    boolean sent = check workflow:sendEvent(approvalWorkflow, rejection, "approval");
    test:assertTrue(sent, "Rejection signal should be sent successfully");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete (with rejection result)");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["orderId"], "ORD-002", "Order ID should match");
        test:assertEquals(result["status"], "REJECTED", "Status should be REJECTED");
        test:assertEquals(result["approvedBy"], "manager-456", "Approver should be captured");
        test:assertEquals(result["txnId"], (), "Transaction ID should be null for rejected orders");
    } else {
        test:assertFail("Result should be a map representing ApprovalResult");
    }
}

// ================================================================================
// APPROVAL WORKFLOW TESTS - INSUFFICIENT PAYMENT
// ================================================================================

@test:Config {
    groups: ["integration", "signals"]
}
function testApprovalWorkflowInsufficientPayment() returns error? {
    string testId = uniqueId("approval-insufficient-test");
    ApprovalInput input = {id: testId, orderId: "ORD-003", amount: 200.0};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(approvalWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send approval signal
    ApprovalSignal approval = {
        id: testId, 
        approverId: "manager-789", 
        approved: true, 
        reason: ()
    };
    _ = check workflow:sendEvent(approvalWorkflow, approval, "approval");
    
    // Give workflow time to process approval
    runtime:sleep(1);
    
    // Send insufficient payment signal
    PaymentSignal payment = {
        id: testId,
        txnId: "TXN-789",
        amount: 150.0  // Less than the required 200.0
    };
    _ = check workflow:sendEvent(approvalWorkflow, payment, "payment");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "PAYMENT_INSUFFICIENT", 
                "Status should be PAYMENT_INSUFFICIENT");
    } else {
        test:assertFail("Result should be a map representing ApprovalResult");
    }
}
