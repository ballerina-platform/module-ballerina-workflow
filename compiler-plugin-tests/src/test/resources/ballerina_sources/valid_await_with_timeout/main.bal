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

import ballerina/workflow;

type Input record {|
    string id;
|};

type Result record {|
    string status;
    boolean approved;
|};

type ApprovalDecision record {|
    boolean approved;
    string approverId;
|};

// Valid ctx->await with a timeout — futures from events, optional timeout field set
@workflow:Workflow
function awaitWithTimeoutWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
    |} events
) returns Result|error {
    // timeout: wait up to 48 hours for both approvals; returns error on timeout
    [ApprovalDecision, ApprovalDecision] [a, b] =
        check ctx->await([events.approverA, events.approverB], timeout = {hours: 48});
    return {status: "APPROVED", approved: a.approved && b.approved};
}

// Valid ctx->await with timeout and explicit minCount
@workflow:Workflow
function awaitAnyWithTimeoutWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
        future<ApprovalDecision> approverC;
    |} events
) returns Result|error {
    // Wait for first of three approvers, but no longer than 24 hours
    [ApprovalDecision] [first] =
        check ctx->await([events.approverA, events.approverB, events.approverC], 1,
            timeout = {hours: 24});
    return {status: "APPROVED", approved: first.approved};
}
