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

// Captures this submodule's reference so native code can create records in this module.
function init() {
    initManagementModule();
}

isolated function initManagementModule() = @java:Method {
    'class: "io.ballerina.lib.workflow.ModuleUtils",
    name: "setManagementModule"
} external;

// ================================================================================
// INSPECTION
// ================================================================================

# Gets current execution info for a workflow without waiting for it to finish.
# Returns the status, workflow type, and ID.
#
# ```ballerina
# import ballerina/workflow.management;
#
# WorkflowExecutionInfo info = check management:getWorkflowInfo(workflowId);
# ```
#
# + workflowId - The workflow ID
# + return - Execution info, or an error
public isolated function getWorkflowInfo(string workflowId) returns WorkflowExecutionInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Lists all workflow types registered with this worker, for use in the workflow launcher UI.
# Returns one entry per registered workflow function. The `inputSchema` field is `()` until
# the compiler plugin generates JSON Schema at build time.
#
# ```ballerina
# management:WorkflowDefinition[] defs = check management:listWorkflowDefinitions();
# ```
#
# + return - Array of workflow definitions, or an error
public isolated function listWorkflowDefinitions() returns WorkflowDefinition[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// LIFECYCLE CONTROL
// ================================================================================

# Requests a running workflow to suspend (pause) execution.
# Sends a `__wf_suspend` signal; the workflow runtime handles blocking until
# `resumeWorkflow` is called.
#
# ```ballerina
# check management:suspendWorkflow(workflowId);
# ```
#
# + workflowId - The workflow ID to suspend
# + return - An error if the signal cannot be delivered
public isolated function suspendWorkflow(string workflowId) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Resumes a previously suspended workflow by sending a `__wf_resume` signal.
#
# ```ballerina
# check management:resumeWorkflow(workflowId);
# ```
#
# + workflowId - The workflow ID to resume
# + return - An error if the signal cannot be delivered
public isolated function resumeWorkflow(string workflowId) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// HUMAN TASKS
// ================================================================================

# Returns the pending human task child workflows started by the given parent workflow,
# grouped by task type and sorted alphabetically by task name. Scans the parent's
# event history for child workflow start events whose ID matches the
# `humantask-<parentWorkflowId>-` prefix.
#
# ```ballerina
# management:HumanTaskGroup[] groups = check management:listPendingHumanTasks(parentWorkflowId);
# // groups are sorted alphabetically by taskName
# foreach management:HumanTaskGroup group in groups {
#     foreach string taskId in group.taskIds {
#         check workflow:completeHumanTask(taskId, decision);
#     }
# }
# ```
#
# + parentWorkflowId - The Temporal workflow ID of the parent workflow
# + return - Array of task groups sorted by task name, or an error
public isolated function listPendingHumanTasks(string parentWorkflowId) returns HumanTaskGroup[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Lists all human task instances across all parent workflows, optionally filtered by status.
# Queries Temporal's visibility API and filters executions whose workflow ID starts with
# `humantask-`. The `taskName` and `parentWorkflowId` fields are extracted from the task's
# Temporal memo (set when the task was created by `callHumanTask`).
#
# ```ballerina
# management:HumanTaskSummary[] pending =
#     check management:listAllHumanTasks(status = "PENDING");
# ```
#
# + status - Optional status filter: PENDING | COMPLETED | TIMED_OUT | CANCELED | TERMINATED
# + return - Array of human task summaries, or an error
public isolated function listAllHumanTasks(string? status = ()) returns HumanTaskSummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns detailed info for a single human task, including memo fields.
# Calls Temporal DescribeWorkflowExecution to read the memo set at task creation.
#
# ```ballerina
# management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
# ```
#
# + taskId - The child workflow ID of the human task (`humantask-{parentId}-{taskName}-{uuid}`)
# + return - Full task info including title, userRoles, payload, and formSchema, or an error
public isolated function getHumanTaskInfo(string taskId) returns HumanTaskInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Completes a pending human task by sending the result back to the waiting workflow.
# This is the preferred API location; `workflow:completeHumanTask` delegates here.
#
# ```ballerina
# check management:completeHumanTask(taskWorkflowId, {approved: true, comment: "LGTM"});
# ```
#
# + taskWorkflowId - Temporal workflow ID of the human task child workflow
# + result - The value to return to the workflow
# + callerRoles - Roles held by the caller; validated against the task's configured `userRoles`
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeHumanTask(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// MANUAL RETRY TASKS
// ================================================================================

# Completes a pending manual retry task by sending the human's decision back to
# the waiting workflow. The `taskWorkflowId` is the child workflow ID of the
# retry task, available via `listPendingRetryTasks` or `listAllRetryTasks`.
#
# ```ballerina
# // Retry with original arguments
# check management:completeRetryTask(taskId, {action: "retry"});
#
# // Retry with different input
# check management:completeRetryTask(taskId, {action: "retry-with-input", input: {"orderId": "NEW-123"}});
#
# // Permanently fail the activity
# check management:completeRetryTask(taskId, {action: "fail"});
# ```
#
# + taskWorkflowId - Temporal workflow ID of the retry task child workflow (`retrytask-...`)
# + decision - The retry decision: retry, retry with new input, or fail
# + callerRoles - Roles held by the caller; validated against the task's configured `userRoles`
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeRetryTask(string taskWorkflowId, RetryDecision decision,
        [string, string...]? callerRoles = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns pending manual retry task child workflows started by the given parent workflow,
# grouped by task name and sorted alphabetically. Scans the parent's event history for
# child workflow start events whose ID starts with the `retrytask-{parentWorkflowId}-` prefix.
#
# ```ballerina
# management:RetryTaskSummary[] tasks = check management:listPendingRetryTasks(parentWorkflowId);
# foreach management:RetryTaskSummary task in tasks {
#     check management:completeRetryTask(task.taskId, {action: "retry"});
# }
# ```
#
# + parentWorkflowId - The Temporal workflow ID of the parent workflow
# + return - Array of pending retry task summaries, or an error
public isolated function listPendingRetryTasks(string parentWorkflowId) returns RetryTaskSummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Lists all manual retry task instances across all parent workflows, optionally filtered
# by status. Queries Temporal's visibility API for executions whose workflow ID starts
# with `retrytask-`.
#
# ```ballerina
# management:RetryTaskSummary[] pending = check management:listAllRetryTasks(status = "PENDING");
# ```
#
# + status - Optional status filter: `PENDING` | `COMPLETED` | `CANCELED` | `TERMINATED`
# + return - Array of retry task summaries, or an error
public isolated function listAllRetryTasks(string? status = ()) returns RetryTaskSummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns detailed info for a single manual retry task, including the failure context
# and activity arguments that triggered the task.
#
# ```ballerina
# management:RetryTaskInfo info = check management:getRetryTaskInfo(taskId);
# ```
#
# + taskId - The child workflow ID of the retry task (`retrytask-{parentId}-{taskName}-{uuid}`)
# + return - Full retry task info including errorMessage, activityArgs, and userRoles, or an error
public isolated function getRetryTaskInfo(string taskId) returns RetryTaskInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;
