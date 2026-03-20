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

type ComplianceDecision record {|
    boolean compliant;
    string reviewerId;
|};

// Valid waitForData usage with dependent typing (typed tuple return)
@workflow:Workflow
function typedWaitForDataWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
    |} events
) returns Result|error {
    // Dependent typing: T is inferred as [ApprovalDecision, ComplianceDecision]
    [ApprovalDecision, ComplianceDecision] [appDecision, compDecision] =
        check workflow:waitForData([events.approval, events.compliance]);
    return {status: "DONE", approved: appDecision.approved && compDecision.compliant};
}

// Valid waitForData with wait-any pattern (minCount=1, anydata[] return)
@workflow:Workflow
function waitAnyWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
        future<ApprovalDecision> approverC;
    |} events
) returns Result|error {
    [ApprovalDecision] [first] =
        check workflow:waitForData([events.approverA, events.approverB, events.approverC], 1);
    return {status: "DONE", approved: first.approved};
}
