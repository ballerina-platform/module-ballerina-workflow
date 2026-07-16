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
// SUSPEND TEST - Workflow Definition
// ================================================================================
//
// This workflow is used to test the suspend/resume management API. It parks at a
// signal wait, then performs an activity call — the durable operation the suspend
// gate must hold at while the workflow is suspended (ballerina-library#8903).
//
// ================================================================================

import ballerina/workflow;

# Input for the suspend test workflow.
#
# + id - The workflow identifier
type SuspendTestInput record {|
    string id;
|};

# Signal that releases the suspend test workflow from its wait point.
#
# + id - The workflow identifier
type SuspendGoSignal record {|
    string id;
|};

# Activity executed after the signal; must not run while the workflow is suspended.
#
# + id - The workflow identifier
# + return - A processed string or error
@workflow:Activity
function suspendTestActivity(string id) returns string|error {
    return "done-" + id;
}

# A workflow used to test that suspension blocks progress at the next durable operation.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input
# + signals - The signal futures record
# + return - The activity result or error
@workflow:Workflow
function suspendTestWorkflow(workflow:Context ctx, SuspendTestInput input,
        record {|future<SuspendGoSignal> go;|} signals) returns string|error {
    SuspendGoSignal _ = check wait signals.go;
    string result = check ctx->callActivity(suspendTestActivity, {"id": input.id});
    return result;
}
