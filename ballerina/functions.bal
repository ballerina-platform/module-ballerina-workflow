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
# + input - Optional input data for the workflow. Must match the workflow
#           function's declared input parameter type (any `anydata` subtype)
# + return - The workflow ID, or an error
public isolated function run(function processFunction, anydata input = ()) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "run"
} external;

# Starts a new durable agent instance and returns its unique ID. The dedicated
# starter mirrors `run` for `@workflow:DurableAgent` functions.
#
# ```ballerina
# string agentId = check workflow:runDurableAgent(orderAgent, input = {"orderId": "ORD-123"});
# ```
#
# + agentFunction - The agent function (must have `@DurableAgent`)
# + input - Optional input data for the agent
# + return - The agent (workflow) ID, or an error
public isolated function runDurableAgent(function agentFunction, anydata input = ())
        returns string|error = @java:Method {
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

# Sends a request to a running durable agent and waits for its response — the
# request-response counterpart of `sendData`, modeled as a Temporal Update.
# The payload is delivered to the agent's event wait (the message and the
# agent's answer for that turn travel together); the call blocks until the
# agent answers and returns that answer coerced to the expected type `T`.
# For structured `T`, the agent's textual answer is parsed as JSON.
#
# Only supported for `@workflow:DurableAgent` workflows: their data intake and
# turn answers are framework-managed, so the response can be correlated
# implicitly. For plain workflows use one-way `sendData` instead.
#
# ```ballerina
# string reply = check workflow:updateAgent(orderAgent, agentId, "chat", "Is the laptop available?");
# ```
#
# + agentFunction - The agent function (must have `@workflow:DurableAgent`)
# + agentId - Target agent (workflow) ID (from `run`)
# + eventName - The event field name declared in the agent's signature
# + data - The request payload
# + T - Expected response type (inferred from context)
# + return - The agent's answer for the turn that consumed this request, or an error
public isolated function updateAgent(function agentFunction, string agentId, string eventName, anydata data,
        typedesc<anydata> T = <>) returns T|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "updateAgent"
} external;

# Sends a request to a running durable agent without waiting for the answer.
# Returns as soon as the request is durably accepted by the workflow server,
# with an update ID for fetching the answer later via `getAgentUpdateResult` —
# from this or any other process.
#
# Prefer this over the blocking `updateAgent` whenever the turn may take long,
# e.g. when the agent escalates to a human task: no thread or connection is
# held while the agent is suspended, and neither the request nor the answer is
# lost if the caller crashes.
#
# ```ballerina
# string updateId = check workflow:updateAgentAsync(orderAgent, agentId, "chat", "Expedite my order");
# ```
#
# + agentFunction - The agent function (must have `@workflow:DurableAgent`)
# + agentId - Target agent (workflow) ID (from `runDurableAgent`)
# + eventName - The update channel registered by the agent
# + data - The request payload
# + return - The update ID to check back with, or an error
public isolated function updateAgentAsync(function agentFunction, string agentId, string eventName,
        anydata data) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "updateAgentAsync"
} external;

# Fetches the agent's answer for a request sent with `updateAgentAsync`. Waits
# up to `timeoutSeconds`; when the agent has not finished the turn yet (e.g. it
# is suspended on a human task) a `workflow:UpdatePendingError` is returned and
# the caller should check back later with the same update ID. The answer is
# read from the workflow history, so it remains retrievable after crashes and
# from other processes.
#
# ```ballerina
# string|error answer = workflow:getAgentUpdateResult(agentId, updateId);
# if answer is workflow:UpdatePendingError {
#     // still working - check back later
# }
# ```
#
# + agentId - Target agent (workflow) ID
# + updateId - The update ID returned by `updateAgentAsync`
# + timeoutSeconds - How long to wait before reporting the update as pending
# + T - Expected response type (inferred from context)
# + return - The agent's answer, a `workflow:UpdatePendingError` when the turn
#            is still in progress, or an error
public isolated function getAgentUpdateResult(string agentId, string updateId,
        decimal timeoutSeconds = 30, typedesc<anydata> T = <>) returns T|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getAgentUpdateResult"
} external;

# Lists the requests a running durable agent has accepted but not yet answered.
# Use after a crash or restart to rediscover in-flight turns for a session and
# fetch their answers via `getAgentUpdateResult` — nothing is lost while the
# agent works, however long the turn takes.
#
# ```ballerina
# workflow:PendingAgentUpdate[] pending = check workflow:getPendingAgentUpdates(agentId);
# foreach var update in pending {
#     string answer = check workflow:getAgentUpdateResult(agentId, update.updateId);
# }
# ```
#
# + agentId - Target agent (workflow) ID
# + return - The in-flight updates (empty when the agent is idle), or an error
public isolated function getPendingAgentUpdates(string agentId)
        returns PendingAgentUpdate[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getPendingAgentUpdates"
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
# + userId - The user ID of the person completing the task (used for auditing)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeHumanTask(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles = (), string? userId = ()) returns error? = @java:Method {
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
