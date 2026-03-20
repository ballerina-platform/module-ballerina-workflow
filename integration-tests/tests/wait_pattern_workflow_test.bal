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
// WAIT PATTERN WORKFLOW TESTS
// ================================================================================
//
// Tests for:
// - Alternate wait (wait f1|f2): first signal wins
// - ctx->await: wait for all, wait for 1 of N
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;

// ================================================================================
// ALTERNATE WAIT — first approver responds (approverA)
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAlternateWaitApproverA() returns error? {
    string testId = uniqueId("alt-wait-a");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(alternateWaitWorkflow, input);
    runtime:sleep(2);

    // Send approverA's decision
    WaitDecision decision = {approverId: "alice", approved: true};
    check workflow:sendData(alternateWaitWorkflow, workflowId, "approverA", decision);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "alice");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// ALTERNATE WAIT — second approver responds (approverB)
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAlternateWaitApproverB() returns error? {
    string testId = uniqueId("alt-wait-b");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(alternateWaitWorkflow, input);
    runtime:sleep(2);

    // Only approverB responds — this is the key test for alternate wait
    WaitDecision decision = {approverId: "bob", approved: false};
    check workflow:sendData(alternateWaitWorkflow, workflowId, "approverB", decision);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "REJECTED");
        test:assertEquals(result["decidedBy"], "bob");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// ALTERNATE WAIT — both respond, first wins
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAlternateWaitBothRespond() returns error? {
    string testId = uniqueId("alt-wait-both");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(alternateWaitWorkflow, input);
    runtime:sleep(2);

    // Send approverA first — small delay ensures ordering
    WaitDecision decisionA = {approverId: "alice", approved: true};
    check workflow:sendData(alternateWaitWorkflow, workflowId, "approverA", decisionA);
    runtime:sleep(1);

    // Then approverB — workflow may have already completed, so ignore send errors
    WaitDecision decisionB = {approverId: "bob", approved: false};
    error? sendErr = workflow:sendData(alternateWaitWorkflow, workflowId, "approverB", decisionB);
    // sendErr is expected if workflow already completed after first signal

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        // First responder wins
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "alice");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// WAIT ALL — both approve
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testWaitAllBothApprove() returns error? {
    string testId = uniqueId("wait-all-approve");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(waitAllWorkflow, input);
    runtime:sleep(2);

    WaitDecision decisionA = {approverId: "alice", approved: true};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverA", decisionA);

    WaitDecision decisionB = {approverId: "bob", approved: true};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverB", decisionB);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "both");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// WAIT ALL — first rejects
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testWaitAllFirstRejects() returns error? {
    string testId = uniqueId("wait-all-reject-a");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(waitAllWorkflow, input);
    runtime:sleep(2);

    WaitDecision decisionA = {approverId: "alice", approved: false};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverA", decisionA);

    WaitDecision decisionB = {approverId: "bob", approved: true};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverB", decisionB);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "REJECTED");
        test:assertEquals(result["decidedBy"], "alice");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// WAIT ALL — B arrives before A (order independence)
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testWaitAllReverseOrder() returns error? {
    string testId = uniqueId("wait-all-reverse");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(waitAllWorkflow, input);
    runtime:sleep(2);

    // Send B first, then A
    WaitDecision decisionB = {approverId: "bob", approved: true};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverB", decisionB);

    WaitDecision decisionA = {approverId: "alice", approved: true};
    check workflow:sendData(waitAllWorkflow, workflowId, "approverA", decisionA);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "both");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// WAIT 1 OF 3 — only one of three signals sent
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testWaitOneOfThree() returns error? {
    string testId = uniqueId("wait-1of3");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(waitOneOfThreeWorkflow, input);
    runtime:sleep(2);

    // Only send approverC
    WaitDecision decision = {approverId: "charlie", approved: true};
    check workflow:sendData(waitOneOfThreeWorkflow, workflowId, "approverC", decision);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "APPROVED");
        test:assertEquals(result["decidedBy"], "charlie");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// AWAIT WITH TIMEOUT — signal arrives before the 5-second timeout
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitSignalWinsBeforeTimeout() returns error? {
    string testId = uniqueId("await-sig-wins");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(awaitOneWithTimeoutWorkflow, input);
    // Wait 2 s, then send a signal — well inside the 5 s window
    runtime:sleep(2);

    WaitDecision decision = {approverId: "alice", approved: true};
    check workflow:sendData(awaitOneWithTimeoutWorkflow, workflowId, "approverA", decision);

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "APPROVED", "Signal should win: status must be APPROVED");
        test:assertEquals(result["decidedBy"], "alice");
    } else {
        test:assertFail("Result should be a map");
    }
}

// ================================================================================
// AWAIT WITH TIMEOUT — timeout fires before any signal arrives
// ================================================================================

@test:Config {
    groups: ["integration", "wait-patterns"]
}
function testAwaitTimeoutFiresBeforeSignal() returns error? {
    string testId = uniqueId("await-timeout");
    WaitPatternInput input = {id: testId};

    string workflowId = check workflow:run(awaitOneWithTimeoutWorkflow, input);
    // Do NOT send any signal — let the 5 s timeout inside the workflow fire.
    // getWorkflowResult blocks until the workflow completes (up to 30 s).

    workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED",
            "Workflow should complete (not fail) when timeout fires — it handles timeout gracefully");

    if execInfo.result is map<anydata> {
        map<anydata> result = <map<anydata>>execInfo.result;
        test:assertEquals(result["status"], "TIMED_OUT", "Timeout should fire: status must be TIMED_OUT");
    } else {
        test:assertFail("Result should be a map");
    }
}
