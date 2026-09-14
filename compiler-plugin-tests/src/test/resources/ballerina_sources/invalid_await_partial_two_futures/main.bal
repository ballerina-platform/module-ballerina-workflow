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

type PaymentInfo record {|
    decimal amount;
    string currency;
|};

// WORKFLOW_123: 2 futures with minCount=1, non-nilable tuple with binding pattern
@workflow:Workflow
function partialAwaitTwoFuturesWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalDecision> approval;
        future<PaymentInfo> payment;
    |} events
) returns string|error {
    [ApprovalDecision, PaymentInfo] [a, p] =
        check ctx->await([events.approval, events.payment], 1);
    return a.approved ? "approved" : "rejected";
}
