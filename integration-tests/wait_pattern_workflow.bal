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
// WAIT PATTERN WORKFLOWS — Alternate Wait and Wait-N-Out-Of-M
// ================================================================================
//
// These workflows test the advanced wait patterns:
// 1. Alternate wait (wait f1|f2) — first signal wins
// 2. ctx->await — wait for N of M futures using the context method
//
// ================================================================================

import ballerina/io;
import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Input for wait-pattern workflows.
#
# + id - Unique request identifier
type WaitPatternInput record {|
    string id;
|};

# A decision from an approver.
#
# + approverId - Who sent the decision
# + approved - Whether they approved
type WaitDecision record {|
    string approverId;
    boolean approved;
|};

# Result from wait-pattern workflows.
#
# + status - Final status
# + decidedBy - Who made the deciding response (optional)
type WaitPatternResult record {|
    string status;
    string? decidedBy;
|};

// ================================================================================
// ALTERNATE WAIT WORKFLOW (wait f1|f2)
// ================================================================================

# Workflow that waits for either of two approvers using alternate wait.
# The first response wins; the other is discarded.
#
# + ctx - Workflow context
# + input - Request input
# + events - Two approval data futures
# + return - Result indicating who responded first
@workflow:Workflow
function alternateWaitWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
    |} events
) returns WaitPatternResult|error {
    io:println(string `[alternateWaitWorkflow] Waiting for either approver for: ${input.id}`);
    WaitDecision decision = check wait events.approverA | events.approverB;
    io:println(string `[alternateWaitWorkflow] Decision from ${decision.approverId}: approved=${decision.approved}`);

    return {
        status: decision.approved ? "APPROVED" : "REJECTED",
        decidedBy: decision.approverId
    };
}

// ================================================================================
// WAIT-ALL-DATA WORKFLOW (ctx->await)
// ================================================================================

# Workflow that waits for all approvers using ctx->await.
# Both must respond before the workflow proceeds.
#
# + ctx - Workflow context
# + input - Request input
# + events - Two approval data futures
# + return - Result based on both decisions
@workflow:Workflow
function waitAllWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
    |} events
) returns WaitPatternResult|error {
    io:println(string `[waitAllWorkflow] Waiting for both approvers for: ${input.id}`);
    // Typed tuple — no cloneWithType() needed
    [WaitDecision, WaitDecision] results = check ctx->await(
        [events.approverA, events.approverB]
    );
    WaitDecision decisionA = results[0];
    WaitDecision decisionB = results[1];
    io:println(string `[waitAllWorkflow] A: approved=${decisionA.approved}, B: approved=${decisionB.approved}`);

    if !decisionA.approved {
        return {status: "REJECTED", decidedBy: decisionA.approverId};
    }
    if !decisionB.approved {
        return {status: "REJECTED", decidedBy: decisionB.approverId};
    }
    return {status: "APPROVED", decidedBy: "both"};
}

# Workflow that uses ctx->await with minCount=1 (equivalent to alternate wait).
#
# + ctx - Workflow context
# + input - Request input
# + events - Three approval data futures
# + return - Result from the first responder
@workflow:Workflow
function waitOneOfThreeWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
        future<WaitDecision> approverC;
    |} events
) returns WaitPatternResult|error {
    io:println(string `[waitOneOfThreeWorkflow] Waiting for 1 of 3 approvers for: ${input.id}`);
    // Return 1 result typed as a single-element tuple
    [WaitDecision] results = check ctx->await(
        [events.approverA, events.approverB, events.approverC], 1
    );
    WaitDecision decision = results[0];
    io:println(string `[waitOneOfThreeWorkflow] First decision from ${decision.approverId}: approved=${decision.approved}`);

    return {
        status: decision.approved ? "APPROVED" : "REJECTED",
        decidedBy: decision.approverId
    };
}
