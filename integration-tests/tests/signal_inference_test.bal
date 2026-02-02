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
// SIGNAL INFERENCE WORKFLOW - TESTS
// ================================================================================
//
// Tests for the optional signalName feature in sendEvent.
// These tests verify that signal names can be inferred when:
// 1. There's only one signal in the events record
// 2. Signal types have distinct structures (different fields)
// 3. Explicit signalName works correctly with ambiguous types
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// TEST 1: Single signal - signal name should be inferred automatically
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testSingleSignalInference() returns error? {
    string testId = uniqueId("single-signal-infer");
    SingleSignalInferInput input = {id: testId, data: "test input data"};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(singleSignalInferWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send signal WITHOUT explicit signalName - should be inferred (only one signal)
    SingleInferSignal signalData = {id: testId, response: "inferred response"};
    boolean sent = check workflow:sendEvent(singleSignalInferWorkflow, signalData);
    test:assertTrue(sent, "Signal should be sent successfully without explicit signalName");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["inputData"], "test input data", "Input data should be preserved");
        test:assertEquals(result["signalResponse"], "inferred response", "Signal response should be captured");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// TEST 2: Distinct types - signal name inferred by type structure matching
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testDistinctTypesSignalInference() returns error? {
    string testId = uniqueId("distinct-types-infer");
    DistinctTypesInput input = {id: testId, requestId: "REQ-001"};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(distinctTypesWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send approval signal WITHOUT explicit signalName - distinct type (has 'approved' boolean)
    ApprovalTypeSignal approvalData = {id: testId, approved: true, approverName: "Manager"};
    boolean approvalSent = check workflow:sendEvent(distinctTypesWorkflow, approvalData);
    test:assertTrue(approvalSent, "Approval signal should be inferred by type structure");
    
    runtime:sleep(1);
    
    // Send payment signal WITHOUT explicit signalName - distinct type (has 'amount' decimal)
    PaymentTypeSignal paymentData = {id: testId, amount: 250.50, transactionRef: "TXN-123"};
    boolean paymentSent = check workflow:sendEvent(distinctTypesWorkflow, paymentData);
    test:assertTrue(paymentSent, "Payment signal should be inferred by type structure");
    
    runtime:sleep(1);
    
    // Send feedback signal WITHOUT explicit signalName - distinct type (has 'rating' int)
    FeedbackTypeSignal feedbackData = {id: testId, rating: 5, comment: "Excellent"};
    boolean feedbackSent = check workflow:sendEvent(distinctTypesWorkflow, feedbackData);
    test:assertTrue(feedbackSent, "Feedback signal should be inferred by type structure");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["wasApproved"], true, "Approval should be captured");
        // Note: decimal values are serialized as floats in workflow results
        anydata paymentAmount = result["paymentAmount"];
        if paymentAmount is float {
            test:assertTrue(paymentAmount > 250.0 && paymentAmount < 251.0, "Payment amount should be ~250.50");
        } else if paymentAmount is decimal {
            test:assertEquals(paymentAmount, 250.50d, "Payment amount should be captured");
        } else {
            test:assertFail("Payment amount should be a number");
        }
        test:assertEquals(result["feedbackRating"], 5, "Feedback rating should be captured");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// TEST 3: Explicit signalName with ambiguous types - must provide name
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testExplicitSignalNameWithAmbiguousTypes() returns error? {
    string testId = uniqueId("explicit-signal-name");
    ExplicitSignalInput input = {id: testId, message: "test message"};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(explicitSignalNameWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send first ambiguous signal WITH explicit signalName
    AmbiguousSignal1 signal1Data = {id: testId, value: "value from signal 1"};
    boolean sent1 = check workflow:sendEvent(explicitSignalNameWorkflow, signal1Data, "ambig1");
    test:assertTrue(sent1, "First ambiguous signal should be sent with explicit name");
    
    runtime:sleep(1);
    
    // Send second ambiguous signal WITH explicit signalName
    AmbiguousSignal2 signal2Data = {id: testId, value: "value from signal 2"};
    boolean sent2 = check workflow:sendEvent(explicitSignalNameWorkflow, signal2Data, "ambig2");
    test:assertTrue(sent2, "Second ambiguous signal should be sent with explicit name");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["fromSignal1"], "value from signal 1", "Signal 1 value should be captured");
        test:assertEquals(result["fromSignal2"], "value from signal 2", "Signal 2 value should be captured");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// TEST 4: Mixed workflow - single distinct signal type
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testMixedSignalsWorkflow() returns error? {
    string testId = uniqueId("mixed-signals");
    MixedSignalsInput input = {id: testId, orderId: "ORD-999"};
    
    // Start the workflow
    string workflowId = check workflow:createInstance(mixedSignalsWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send status update signal without explicit name - should be inferred (only one signal)
    StatusUpdateSignal statusData = {id: testId, statusCode: 200, description: "Success"};
    boolean sent = check workflow:sendEvent(mixedSignalsWorkflow, statusData);
    test:assertTrue(sent, "Status signal should be inferred (single signal in record)");
    
    // Wait for workflow to complete
    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    
    test:assertEquals(execInfo.status, "COMPLETED", "Workflow should complete successfully");
    
    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["finalStatus"], 200, "Status code should be captured");
    } else {
        test:assertFail("Result should be a map");
    }
}

// NOTE: Test for runtime error on ambiguous types was removed because this scenario
// is now validated at compile time by the SendEventValidatorTask. The compiler plugin
// test testInvalidSendEventAmbiguousNoSignalName covers this validation.

