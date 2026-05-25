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
// HUMAN-IN-THE-LOOP WORKFLOW - TESTS
// ================================================================================
//
// Tests for the forward-recovery and alternate-wait timeout patterns
// documented in patterns/human-in-the-loop.md.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// FORWARD RECOVERY — PAYMENT SUCCEEDS (no signal needed)
// ================================================================================

@test:Config {
    groups: ["integration", "hitl"]
}
function testHitlPaymentSucceeds() returns error? {
    string testId = uniqueId("hitl-pay-ok");
    HitlInput input = {
        id: testId,
        orderId: "ORD-HITL-01",
        amount: 100.0,
        cardToken: "tok_ok",
        shouldFailPayment: false
    };

    string workflowId = check workflow:run(hitlForwardRecoveryWorkflow, input);
    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["status"], "COMPLETED");
        test:assertTrue((<string>result["message"]).startsWith("TXN-tok_ok"),
                "Message should contain the transaction ID");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// FORWARD RECOVERY — PAYMENT FAILS, REVIEWER APPROVES
// ================================================================================

@test:Config {
    groups: ["integration", "hitl"]
}
function testHitlForwardRecoveryApproved() returns error? {
    string testId = uniqueId("hitl-approved");
    HitlInput input = {
        id: testId,
        orderId: "ORD-HITL-02",
        amount: 250.0,
        cardToken: "tok_fail",
        shouldFailPayment: true
    };

    string workflowId = check workflow:run(hitlForwardRecoveryWorkflow, input);

    // Wait for workflow to reach the signal-wait point
    runtime:sleep(3);

    // Reviewer approves
    check workflow:sendData(hitlForwardRecoveryWorkflow, workflowId, "review", {
        reviewerId: "reviewer-1",
        approved: true,
        note: "Gateway recovered"
    });

    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HITL-02");
        test:assertEquals(result["status"], "COMPLETED");
        test:assertEquals(result["message"], "TXN-MANUAL-tok_fail",
                "Should contain manual-retry transaction ID");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// ================================================================================
// FORWARD RECOVERY — PAYMENT FAILS, REVIEWER REJECTS
// ================================================================================

@test:Config {
    groups: ["integration", "hitl"]
}
function testHitlForwardRecoveryRejected() returns error? {
    string testId = uniqueId("hitl-rejected");
    HitlInput input = {
        id: testId,
        orderId: "ORD-HITL-03",
        amount: 500.0,
        cardToken: "tok_fail",
        shouldFailPayment: true
    };

    string workflowId = check workflow:run(hitlForwardRecoveryWorkflow, input);

    // Wait for workflow to reach the signal-wait point
    runtime:sleep(3);

    // Reviewer rejects
    check workflow:sendData(hitlForwardRecoveryWorkflow, workflowId, "review", {
        reviewerId: "reviewer-2",
        approved: false,
        note: "Suspected fraud"
    });

    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        test:assertEquals(result["orderId"], "ORD-HITL-03");
        test:assertEquals(result["status"], "CANCELLED");
        test:assertTrue((<string>result["message"]).includes("reviewer-2"),
                "Message should mention the reviewer");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}


