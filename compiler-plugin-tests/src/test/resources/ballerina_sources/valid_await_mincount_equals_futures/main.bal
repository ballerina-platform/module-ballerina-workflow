// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

type ApprovalDecision record {|
    boolean approved;
    string approverId;
|};

type ComplianceDecision record {|
    boolean compliant;
    string reviewerId;
|};

// Valid: minCount == futureCount means all futures must complete, so non-nilable is fine
@workflow:Workflow
function awaitMinCountEqualsFuturesWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
    |} events
) returns string|error {
    // minCount=2 == 2 futures — all futures will complete, non-nilable is correct
    [ApprovalDecision, ComplianceDecision] [a, c] =
        check ctx->await([events.approval, events.compliance], 2);
    return a.approved && c.compliant ? "approved" : "rejected";
}

// Valid: explicit minCount == length with binding pattern (3 futures, minCount=3)
@workflow:Workflow
function awaitAllThreeWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
        future<ComplianceDecision> reviewer;
    |} events
) returns string|error {
    [ApprovalDecision, ApprovalDecision, ComplianceDecision] [a, b, c] =
        check ctx->await([events.approverA, events.approverB, events.reviewer], 3);
    return a.approved && b.approved && c.compliant ? "approved" : "rejected";
}
