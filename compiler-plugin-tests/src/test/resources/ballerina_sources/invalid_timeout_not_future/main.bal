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

type ApprovalDecision record {|
    boolean approved;
    string? reason;
|};

// Invalid: timeout is given a future<int> value (from the events record),
// not a time:Duration. The timeout parameter must be time:Duration?
// and cannot accept a future.
@workflow:Workflow
function invalidTimeoutNotFutureWorkflow(
    workflow:Context ctx,
    string input,
    record {| future<int> ticketCount; |} events
) returns ApprovalDecision|error {
    ApprovalDecision decision = check ctx->awaitHumanTask("reviewItem", ["admin"],
            timeout = events.ticketCount);  // ERROR: future<int> is not time:Duration?
    return decision;
}
