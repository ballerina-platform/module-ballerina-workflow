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

// WORKFLOW_123: non-nilable tuple with minCount passed as named argument
@workflow:Workflow
function partialAwaitNamedMinCountWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
    |} events
) returns string|error {
    // minCount as named arg: minCount=1 < 2 futures, tuple members must be nilable
    [ApprovalDecision, ApprovalDecision] [a, b] =
        check ctx->await([events.approverA, events.approverB], minCount = 1);
    return a.approved ? "approved" : "rejected";
}
