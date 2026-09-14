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

// WORKFLOW_123: some tuple members are nilable but others are not
// With minCount < futures, ALL members must be nilable
@workflow:Workflow
function partialAwaitMixedNilableWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
        future<ApprovalDecision> approverB;
    |} events
) returns string|error {
    // minCount=1 < 3 futures; first member is nilable but second and third are not
    [ApprovalDecision?, ComplianceDecision, ApprovalDecision] results =
        check ctx->await([events.approval, events.compliance, events.approverB], 1);
    return "done";
}
