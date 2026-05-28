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

// Invalid: timeout field is provided as a plain integer 30.
// The HumanTaskConfig.timeout field expects time:Duration? (a record type),
// not a plain int. This should trigger a Ballerina type error.
@workflow:Workflow
function invalidTimeoutNoValueWorkflow(workflow:Context ctx, string input) returns ApprovalDecision|error {
    ApprovalDecision decision = check ctx->callHumanTask({
        taskName: "reviewItem",
        timeout: 30  // ERROR: int is not compatible with time:Duration?
    });
    return decision;
}
