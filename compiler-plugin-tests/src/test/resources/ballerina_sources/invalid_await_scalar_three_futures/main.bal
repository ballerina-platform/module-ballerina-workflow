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

type ApprovalDecision record {|
    boolean approved;
    string approverId;
|};

type ComplianceDecision record {|
    boolean compliant;
    string reviewerId;
|};

type AuditDecision record {|
    string auditId;
    boolean passed;
|};

// WORKFLOW_122: boolean scalar used with 3 futures — must be a tuple
@workflow:Workflow
function booleanForThreeFutures(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<ComplianceDecision> compliance;
        future<AuditDecision> audit;
    |} events
) returns boolean|error {
    boolean result = check ctx->await([events.approval, events.compliance, events.audit]);
    return result;
}
