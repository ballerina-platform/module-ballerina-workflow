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

type Input record {| string id; |};

type ApprovalDecision record {| boolean approved; string approverId; |};

type ComplianceDecision record {| boolean compliant; string reviewerId; |};

// Valid: full wait captured WITHOUT `check` as a `T|error` union (no forced check).
// Enabled by the dependent type parameter `typedesc<anydata|error>`.
@workflow:Workflow
function awaitUnionNoCheck(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
    |} events
) returns string|error {
    [ApprovalDecision, ComplianceDecision]|error result = ctx->await([events.approval, events.compliance]);
    if result is error {
        return result;
    }
    [ApprovalDecision, ComplianceDecision] [appr, comp] = result;
    return appr.approved && comp.compliant ? "DONE" : "REJECTED";
}

// Valid: full wait with a tuple-binding pattern + check (direct destructuring).
@workflow:Workflow
function awaitBindingPattern(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
    |} events
) returns string|error {
    [ApprovalDecision, ComplianceDecision] [appr, comp] =
        check ctx->await([events.approval, events.compliance]);
    return appr.approverId + ":" + comp.reviewerId;
}

// Valid: per-position error tuple — each slot is a value or an error. Enabled by the
// widened constraint `typedesc<anydata|error|(anydata|error)[]>` and validated per position
// by the compiler plugin.
@workflow:Workflow
function awaitPerPositionError(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
    |} events
) returns string|error {
    [ApprovalDecision|error, ComplianceDecision|error] [appr, comp] =
        check ctx->await([events.approval, events.compliance]);
    if appr is error {
        return appr;
    }
    if comp is error {
        return comp;
    }
    return appr.approved && comp.compliant ? "DONE" : "REJECTED";
}

// Valid: single wait.
@workflow:Workflow
function awaitSingle(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
    |} events
) returns string|error {
    [ApprovalDecision] [appr] = check ctx->await([events.approval]);
    return appr.approverId;
}

// Valid: partial (alternate) wait, nilable members, binding pattern.
@workflow:Workflow
function awaitPartial(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
    |} events
) returns string|error {
    [ApprovalDecision?, ApprovalDecision?] [a, b] =
        check ctx->await([events.approverA, events.approverB], 1);
    if a is ApprovalDecision {
        return a.approverId;
    }
    if b is ApprovalDecision {
        return b.approverId;
    }
    return "NONE";
}

// Valid: partial wait captured as a union without check.
@workflow:Workflow
function awaitPartialUnionNoCheck(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
    |} events
) returns string|error {
    [ApprovalDecision?, ApprovalDecision?]|error result =
        ctx->await([events.approverA, events.approverB], 1, timeout = {seconds: 5});
    if result is error {
        return "TIMED_OUT";
    }
    return "OK";
}
