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
// ctx->await TUPLE-BINDING / UNION-LHS WORKFLOWS
// ================================================================================
//
// Exercises the dependent-typing ergonomics enabled by `typedesc<anydata|error> T = <>`
// (returning `T`):
//   - tuple binding pattern directly off `check ctx->await(...)`
//   - capturing the whole result as `[..]|error` without a forced `check`
//
// Reuses WaitPatternInput / WaitDecision / WaitPatternResult from wait_pattern_workflow.bal.
// ================================================================================

import ballerina/workflow;

# Waits for both approvers and destructures the result with a tuple-binding pattern.
#
# + ctx - Workflow context
# + input - Request input
# + events - Two approval data futures
# + return - Result based on both decisions
@workflow:Workflow
function awaitBindingWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
    |} events
) returns WaitPatternResult|error {
    // Tuple-binding pattern directly off the await (no intermediate tuple variable).
    [WaitDecision, WaitDecision] [decisionA, decisionB] =
        check ctx->await([events.approverA, events.approverB]);
    if !decisionA.approved {
        return {status: "REJECTED", decidedBy: decisionA.approverId};
    }
    if !decisionB.approved {
        return {status: "REJECTED", decidedBy: decisionB.approverId};
    }
    return {status: "APPROVED", decidedBy: "both"};
}

# Waits for both approvers, capturing the result as `[..]|error` without `check`.
# A 5-second timeout makes the error path reachable when no signals arrive.
#
# + ctx - Workflow context
# + input - Request input
# + events - Two approval data futures
# + return - APPROVED/REJECTED on completion, TIMED_OUT on the error path
@workflow:Workflow
function awaitUnionNoCheckWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
    |} events
) returns WaitPatternResult|error {
    // Union LHS, no `check` — the timeout error is surfaced as a value to handle.
    [WaitDecision, WaitDecision]|error result =
        ctx->await([events.approverA, events.approverB], timeout = {seconds: 5});
    if result is error {
        return {status: "TIMED_OUT", decidedBy: ()};
    }
    [WaitDecision, WaitDecision] [decisionA, decisionB] = result;
    boolean bothApproved = decisionA.approved && decisionB.approved;
    return {status: bothApproved ? "APPROVED" : "REJECTED", decidedBy: "both"};
}

# Waits for both approvers using a per-position error tuple. Each slot is a value or an error,
# so each position is narrowed with an `is error` check before use.
#
# + ctx - Workflow context
# + input - Request input
# + events - Two approval data futures
# + return - Result based on both decisions
@workflow:Workflow
function awaitPerPositionErrorWorkflow(
    workflow:Context ctx,
    WaitPatternInput input,
    record {|
        future<WaitDecision> approverA;
        future<WaitDecision> approverB;
    |} events
) returns WaitPatternResult|error {
    [WaitDecision|error, WaitDecision|error] [resultA, resultB] =
        check ctx->await([events.approverA, events.approverB]);
    if resultA is error {
        return {status: "ERROR_A", decidedBy: ()};
    }
    if resultB is error {
        return {status: "ERROR_B", decidedBy: ()};
    }
    boolean bothApproved = resultA.approved && resultB.approved;
    return {status: bothApproved ? "APPROVED" : "REJECTED", decidedBy: "both"};
}
