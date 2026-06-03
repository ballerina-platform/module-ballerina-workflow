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

import ballerina/jballerina.java;


# Starts a new workflow instance and returns its unique ID.
#
# ```ballerina
# string workflowId = check workflow:run(orderProcess, input = {"orderId": "ORD-123"});
# ```
#
# + processFunction - The workflow function (must have `@Workflow`)
# + input - Optional input data for the workflow
# + return - The workflow ID, or an error
public isolated function run(function processFunction, map<anydata>? input = ()) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "run"
} external;

# Sends data to a running workflow's events record.
#
# ```ballerina
# check workflow:sendData(orderProcess, workflowId, "approval", {approved: true});
# ```
#
# + workflow - The workflow function (must have `@Workflow`)
# + workflowId - Target workflow ID (from `run`)
# + dataName - Field name in the workflow's events record
# + data - The data payload
# + return - An error if sending fails
public isolated function sendData(function workflow, string workflowId, string dataName, anydata data) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Waits for a workflow to complete and returns its result.
#
# ```ballerina
# anydata raw = check workflow:getWorkflowResult(workflowId);
# ```
#
# + workflowId - The workflow ID
# + timeoutSeconds - Maximum wait time in seconds
# + return - Result of the workflow as anydata, or an error
public isolated function getWorkflowResult(string workflowId, int timeoutSeconds = 30) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

// Internal functions

# Stops the workflow runtime gracefully, draining in-progress tasks.
#
# + return - An error if stopping fails
isolated function stopWorkflowRuntime() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorker"
} external;

# Stops the workflow runtime immediately, interrupting in-flight tasks.
#
# + return - An error if stopping fails
isolated function stopWorkflowRuntimeNow() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorkerNow"
} external;

# Returns all registered workflows and their activities.
#
# + return - Registry map, or an error
isolated function getRegisteredWorkflows() returns WorkflowRegistry|error {
    return getRegisteredWorkflowsNative();
}

# + return - Registry map, or an error
isolated function getRegisteredWorkflowsNative() returns WorkflowRegistry|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getRegisteredWorkflows"
} external;
