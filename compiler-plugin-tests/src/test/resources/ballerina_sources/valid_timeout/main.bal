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

@workflow:Activity
function validateItem(string id) returns string|error {
    return id;
}

// Valid createHumanTask with an explicit time:Duration timeout.
// The timeout parameter is optional and defaults to nil, but providing a concrete
// duration value is fully supported and must not trigger any diagnostic.
@workflow:Workflow
function approvalWithTimeoutWorkflow(workflow:Context ctx, string input) returns ApprovalDecision|error {
    string _ = check ctx->callActivity(validateItem, {"id": input});

    ApprovalDecision decision = check ctx->createHumanTask("reviewItem", ["REVIEWER"],
            title = string `Review item ${input}`,
            payload = {"itemId": input},
            timeout = {seconds: 30});
    return decision;
}

// Valid createHumanTask with a nil timeout (same as omitting the parameter entirely).
@workflow:Workflow
function approvalWithNilTimeoutWorkflow(workflow:Context ctx, string input) returns ApprovalDecision|error {
    ApprovalDecision decision = check ctx->createHumanTask("reviewItemNil", ["admin"],
            timeout = ());
    return decision;
}
