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

@workflow:Workflow
function approvalWorkflow(workflow:Context ctx, string input, record {|
    future<boolean> approval;
|} events) returns string|error {
    boolean approved = check wait events.approval;
    return approved ? "approved" : "rejected";
}

public function notifyWorkflow(string workflowId) returns error? {
    // Invalid: event 'approval' expects boolean data but an int is sent - WORKFLOW_135
    int decision = 1;
    check workflow:sendData(approvalWorkflow, workflowId, "approval", decision);

    // Invalid: event 'approval' expects boolean data but a mapping value is sent - WORKFLOW_135
    check workflow:sendData(approvalWorkflow, workflowId, "approval", {approved: true});
}
