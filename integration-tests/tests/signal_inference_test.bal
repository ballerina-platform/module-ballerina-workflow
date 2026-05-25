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
// SIGNAL WORKFLOW - TESTS WITH EXPLICIT DATA NAME
// ================================================================================
//
// Tests for sendData with explicit dataName parameter.
// These tests verify that data can be sent to workflows using specific
// data names that match the events record field names.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// TEST 1: Single signal - send with explicit dataName
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testSingleSignalInference() returns error? {
    string testId = uniqueId("single-signal-infer");
    SingleSignalInferInput input = {id: testId, data: "test input data"};
    
    // Start the workflow
    string workflowId = check workflow:run(singleSignalInferWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send signal with explicit dataName
    SingleInferSignal signalData = {id: testId, response: "inferred response"};
    check workflow:sendData(singleSignalInferWorkflow, workflowId, "onlySignal", signalData);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["inputData"], "test input data", "Input data should be preserved");
        test:assertEquals(result["signalResponse"], "inferred response", "Signal response should be captured");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 2: Distinct types - send each with explicit dataName
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testDistinctTypesSignalInference() returns error? {
    string testId = uniqueId("distinct-types-infer");
    DistinctTypesInput input = {id: testId, requestId: "REQ-001"};
    
    // Start the workflow
    string workflowId = check workflow:run(distinctTypesWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send approval signal with explicit dataName
    ApprovalTypeSignal approvalData = {id: testId, approved: true, approverName: "Manager"};
    check workflow:sendData(distinctTypesWorkflow, workflowId, "approval", approvalData);
    
    runtime:sleep(1);
    
    // Send payment signal with explicit dataName
    PaymentTypeSignal paymentData = {id: testId, amount: 250.50, transactionRef: "TXN-123"};
    check workflow:sendData(distinctTypesWorkflow, workflowId, "payment", paymentData);
    
    runtime:sleep(1);
    
    // Send feedback signal with explicit dataName
    FeedbackTypeSignal feedbackData = {id: testId, rating: 5, comment: "Excellent"};
    check workflow:sendData(distinctTypesWorkflow, workflowId, "feedback", feedbackData);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
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
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 3: Ambiguous signal types - explicit dataName disambiguates
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testExplicitSignalNameWithAmbiguousTypes() returns error? {
    string testId = uniqueId("explicit-signal-name");
    ExplicitSignalInput input = {id: testId, message: "test message"};
    
    // Start the workflow
    string workflowId = check workflow:run(explicitSignalNameWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send first signal with explicit dataName
    AmbiguousSignal1 signal1Data = {id: testId, value: "value from signal 1"};
    check workflow:sendData(explicitSignalNameWorkflow, workflowId, "ambig1", signal1Data);
    
    runtime:sleep(1);
    
    // Send second signal with explicit dataName
    AmbiguousSignal2 signal2Data = {id: testId, value: "value from signal 2"};
    check workflow:sendData(explicitSignalNameWorkflow, workflowId, "ambig2", signal2Data);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["fromSignal1"], "value from signal 1", "Signal 1 value should be captured");
        test:assertEquals(result["fromSignal2"], "value from signal 2", "Signal 2 value should be captured");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// TEST 4: Mixed workflow - single signal with explicit dataName
// ================================================================================

@test:Config {
    groups: ["integration", "signal-inference"]
}
function testMixedSignalsWorkflow() returns error? {
    string testId = uniqueId("mixed-signals");
    MixedSignalsInput input = {id: testId, orderId: "ORD-999"};
    
    // Start the workflow
    string workflowId = check workflow:run(mixedSignalsWorkflow, input);
    
    // Give the workflow time to start
    runtime:sleep(1);
    
    // Send status update signal with explicit dataName
    StatusUpdateSignal statusData = {id: testId, statusCode: 200, description: "Success"};
    check workflow:sendData(mixedSignalsWorkflow, workflowId, "statusUpdate", statusData);
    
    // Wait for workflow to complete
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["finalStatus"], 200, "Status code should be captured");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

