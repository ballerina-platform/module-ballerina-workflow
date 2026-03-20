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

type Decision record {|
    boolean approved;
    string decidedBy;
|};

// A future created outside the events record — not allowed in waitForData
future<Decision> externalFuture = start fetchDecision();

function fetchDecision() returns Decision => {approved: true, decidedBy: "external"};

// This workflow passes a future NOT from the events parameter — should trigger WORKFLOW_116
@workflow:Workflow
function invalidWaitForDataSource(
    workflow:Context ctx,
    Input input,
    record {|
        future<Decision> approver;
    |} events
) returns Result|error {
    // externalFuture is not from events — should trigger WORKFLOW_116
    anydata[] results = check workflow:waitForData([externalFuture]);
    return {status: "DONE"};
}
