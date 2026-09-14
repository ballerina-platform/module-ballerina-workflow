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

import ballerina/jballerina.java;

import workflow.observe;

# Starts a new workflow instance and returns its unique ID.
#
# + processFunction - The workflow function (must have `@Workflow`)
# + input - Optional input data for the workflow. Must match the workflow
#           function's declared input parameter type (any `anydata` subtype)
# + return - The workflow ID, or an error
public isolated function run(function processFunction, anydata input = ()) returns string|error {
    observe:StartWorkflowSpan span = observe:createStartWorkflowSpan(observe:workflowTypeNameOf(processFunction));
    string|error result = runNative(processFunction, input);
    if result is string {
        span.addInstanceId(result);
        span.close();
    } else {
        span.close(result);
    }
    return result;
}

isolated function runNative(function processFunction, anydata input) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "run"
} external;

# Sends data to a running workflow's events record.
#
# + workflow - The workflow function (must have `@Workflow`)
# + workflowId - Target workflow ID (from `run`)
# + dataName - Field name in the workflow's events record
# + data - The data payload
# + return - An error if sending fails
public isolated function sendData(function workflow, string workflowId, string dataName, anydata data) returns error? {
    observe:SendDataSpan span = observe:createSendDataSpan(workflowId, dataName);
    error? result = sendDataNative(workflow, workflowId, dataName, data);
    span.close(result);
    return result;
}

isolated function sendDataNative(function workflow, string workflowId, string dataName,
        anydata data) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "sendData"
} external;

# Lists the data events a running durable agent has accepted but not yet answered. Use it after a
# restart to rediscover in-flight turns and read their answers with `DurableAgent.getDataResult`
# or `waitForDataResult`.
#
# + agentId - Target agent (workflow) ID
# + return - The in-flight event turns (empty when the agent is idle), or an error
public isolated function getPendingAgentEvents(string agentId)
        returns PendingAgentEvent[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getPendingAgentEvents"
} external;

# Waits for a workflow to complete and returns its result.
#
# + workflowId - The workflow ID
# + timeoutSeconds - Maximum wait time in seconds
# + return - Result of the workflow as anydata, or an error
public isolated function getWorkflowResult(string workflowId, int timeoutSeconds = 30) returns anydata|error {
    observe:GetWorkflowResultSpan span = observe:createGetWorkflowResultSpan(workflowId);
    anydata|error result = getWorkflowResultNative(workflowId, timeoutSeconds);
    span.close(result is error ? result : ());
    return result;
}

isolated function getWorkflowResultNative(string workflowId, int timeoutSeconds) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getWorkflowResult"
} external;

# Completes a pending human task by sending the result back to the waiting workflow. Given
# `callerRoles`, the caller must hold one of the task's `userRoles`; omitting them skips the check
# and leaves enforcement to the caller.
#
# + taskWorkflowId - Temporal workflow ID of the human task child workflow
# + result - The value to return to the workflow (must be compatible with the declared `T`)
# + callerRoles - Roles held by the caller; validated against the task's configured `userRoles`
# + userId - The user ID of the person completing the task (used for auditing)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeHumanTask(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles = (), string? userId = ()) returns error? {
    observe:TaskDecisionSpan span = observe:createHumanTaskDecisionSpan(taskWorkflowId, "complete");
    span.addDecider(userId, callerRoles);
    span.addContent(result);
    map<anydata>|error receipt = completeHumanTaskNative(taskWorkflowId, result, callerRoles, userId);
    if receipt is error {
        span.close(receipt);
        return receipt;
    }
    span.addTaskDetails(receipt);
    span.close();
}

// On success the runtime hands back what it confirmed about the task — its declared name, its
// parent workflow and the roles allowed to decide it — for the decision's audit entry.
isolated function completeHumanTaskNative(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles, string? userId) returns map<anydata>|error = @java:Method {
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
