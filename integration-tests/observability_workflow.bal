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

// ================================================================================
// OBSERVABILITY WORKFLOW
// ================================================================================
//
// Exercises the observability integration: a workflow with one activity and one
// data event, so a single run emits the workflow start/completion metrics, the
// activity execution metrics, the data-event counter, and the client-side
// start_workflow / send_data / get_workflow_result tracing spans asserted by
// tests/observability_test.bal. A second workflow that always fails covers the
// failed-status metrics.
//
// ================================================================================

import ballerina/workflow;

# Input for the observability workflow.
#
# + name - The name to echo through the activity
type ObservabilityInput record {|
    string name;
|};

@workflow:Activity
function observabilityEcho(string name) returns string {
    return "obs:" + name;
}

@workflow:Workflow
function observabilityFlow(workflow:Context ctx, ObservabilityInput input, ObservabilityEvents events)
        returns string|error {
    string echoed = check ctx->callActivity(observabilityEcho, {name: input.name});
    boolean approved = check wait events.obsApproval;
    return approved ? echoed : "rejected";
}

type ObservabilityEvents record {|
    future<boolean> obsApproval;
|};

@workflow:Workflow
function observabilityFailingFlow(workflow:Context ctx) returns error? {
    return error("observability failure scenario");
}
