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

// Valid: nilable tuple with minCount passed as named argument
@workflow:Workflow
function partialAwaitNamedMinCountValidWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approverA;
        future<ApprovalDecision> approverB;
        future<ApprovalDecision> approverC;
    |} events
) returns string|error {
    [ApprovalDecision?, ApprovalDecision?, ApprovalDecision?] [a, b, c] =
        check ctx->await([events.approverA, events.approverB, events.approverC], minCount = 2);
    return "done";
}
