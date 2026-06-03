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

# Completes a pending human task by sending the result back to the waiting workflow.
# The `taskWorkflowId` is the child workflow ID of the task, which is
# available via the inbox/task-listing API and is composed as:
# `"humantask-<parentWorkflowId>-<taskName>-<uuid>"`.
#
# ```ballerina
# check workflow:completeHumanTask(taskWorkflowId, {approved: true, comment: "LGTM"});
# ```
#
# If `callerRoles` is provided the function fetches the `userRoles` stored on the task
# and returns an error when none of the caller's roles appear in that list.
# When omitted the role check is skipped; enforcement is then the caller's responsibility.
#
# + taskWorkflowId - Temporal workflow ID of the human task child workflow
# + result - The value to return to the workflow (must be compatible with the declared `T`)
# + callerRoles - Roles held by the caller; validated against the task's configured `userRoles`
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeHumanTask(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "completeHumanTask"
} external;

// Internal functions

# Stops the workflow runtime gracefully, draining in-progress tasks.
#
# + return - An error if stopping fails
isolated function stopWorkflowRuntime() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorker"
} external;
