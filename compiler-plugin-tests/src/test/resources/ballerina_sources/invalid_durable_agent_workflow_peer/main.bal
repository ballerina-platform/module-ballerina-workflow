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

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider routerModel = check new ("http://localhost:9099", "test-token");

type ApprovalInput record {|
    string claimId;
|};

@workflow:Workflow
function departmentApproval(workflow:Context ctx, ApprovalInput input) returns boolean|error {
    return true;
}

// Not a workflow function: the runtime has nothing to start for it.
function notifyDepartment(ApprovalInput input) returns boolean|error {
    return true;
}

final workflow:DurableAgent claimAgent = check new ({
    systemPrompt: {role: "Claim router", instructions: "Route claims."},
    model: routerModel,
    peers: [
        {agent: notifyDepartment, description: "WORKFLOW_171: a plain function"},
        {agent: departmentApproval, allowedEvents: ["decision"]}
    ]
});
