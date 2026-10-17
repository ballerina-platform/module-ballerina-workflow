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
    string departmentId;
|};

type Decision record {|
    boolean approved;
    string comment;
|};

// A workflow function as a peer: the model gets one tool, named after the function, whose
// arguments are the workflow's input. It starts the workflow and does not wait for it.
@workflow:Workflow
function departmentApproval(workflow:Context ctx, ApprovalInput input) returns Decision|error {
    return ctx->awaitHumanTask("departmentApproval", {claimId: input.claimId}, userRoles = "MOTOR_ASSESSOR");
}

@workflow:Workflow
function archiveCase(workflow:Context ctx, string caseId) returns error? {
    return;
}

final workflow:DurableAgent claimAgent = check new ({
    systemPrompt: {role: "Claim router", instructions: "Start departmentApproval for each department."},
    model: routerModel,
    peers: [
        {agent: departmentApproval, description: "Asks one department to approve the claim."},
        {agent: archiveCase}
    ]
});

public function main() returns error? {
    string id = check claimAgent.run("Route this claim.");
    string answer = check claimAgent.waitForResult(id);
    _ = answer;
}
