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

type Result record {|
    string status;
|};

function backgroundTask(string id) {
    // some background work
}

// This workflow uses start action — should trigger WORKFLOW_120
@workflow:Workflow
function invalidWorkflowWithStart(
    workflow:Context ctx,
    Input input
) returns Result|error {
    // start action creates a new strand — not allowed inside @Workflow
    future<()> _ = start backgroundTask(input.id);
    return {status: "DONE"};
}
