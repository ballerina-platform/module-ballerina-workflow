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

// Captures this submodule's reference so native code can create records in this module,
// then validates the management API configuration so any misconfiguration causes a
// descriptive panic at startup rather than a silent runtime failure.
function init() {
    initManagementModule();
    validateManagementApiConfig();
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

# Gets execution info for a specific run of a workflow, identified by both workflow ID and run ID.
# Unlike `getWorkflowInfo`, this targets the exact run rather than the latest run.
#
# + workflowId - The workflow ID
# + runId - The specific run ID
# + return - Execution info, or an error
public isolated function getWorkflowInfoForRun(string workflowId, string runId)
        returns WorkflowExecutionInfo|error = @java:Method {
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

# Suspends a specific run of a workflow. Targets the exact `runId` rather than the
# latest run, which is correct when a workflow ID has multiple historical runs.
#
# + workflowId - The workflow ID to suspend
# + runId - The specific run ID to suspend
# + return - An error if the signal cannot be delivered
public isolated function suspendWorkflowRun(string workflowId, string runId) returns error? = @java:Method {
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

# Resumes a specific run of a suspended workflow. Targets the exact `runId` rather than
# the latest run, which is correct when a workflow ID has multiple historical runs.
#
# + workflowId - The workflow ID to resume
# + runId - The specific run ID to resume
# + return - An error if the signal cannot be delivered
public isolated function resumeWorkflowRun(string workflowId, string runId) returns error? = @java:Method {
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

# Lists all human task instances across all parent workflows, with optional filters.
# Queries Temporal's visibility API and filters executions whose workflow ID starts with
# `humantask-`. The `taskName` and `parentWorkflowId` fields are extracted from the task's
# Temporal memo (set when the task was created by `awaitHumanTask`).
#
# ```ballerina
# management:HumanTaskSummary[] pending =
#     check management:listAllHumanTasks(status = "PENDING");
#
# management:HumanTaskSummary[] recent =
#     check management:listAllHumanTasks(startTimeFrom = "2026-06-01T00:00:00Z");
# ```
#
# + status - Optional status filter: `PENDING` | `COMPLETED` | `TIMED_OUT` | `CANCELED` | `TERMINATED`
# + startTimeFrom - Optional ISO-8601 lower bound on task start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on task start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on task close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on task close time (inclusive)
# + return - Array of human task summaries, or an error
public isolated function listAllHumanTasks(string? status = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = ()) returns HumanTaskSummary[]|error = @java:Method {
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
# + userId - Optional user identifier stored in the audit trail (from `x-user-id` header)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeHumanTask(string taskWorkflowId, anydata result,
        [string, string...]? callerRoles = (), string? userId = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Fails (rejects) a pending human task with a reason and optional structured details.
# Internally sends a rejection payload to the waiting workflow so it can handle the
# rejection case. The caller's roles are validated against the task's `userRoles`.
#
# ```ballerina
# check management:failHumanTask(taskId, "Missing required documents",
#         details = {"missingDocs": ["invoice", "receipt"]},
#         callerRoles = ["finance_approver"]);
# ```
#
# + taskWorkflowId - Temporal workflow ID of the human task child workflow
# + reason - Human-readable reason for the rejection
# + details - Optional structured details about the failure (passed to the workflow)
# + callerRoles - Roles held by the caller; validated against the task's `userRoles`
# + userId - Optional user identifier stored in the audit trail (from `x-user-id` header)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function failHumanTask(string taskWorkflowId, string reason,
        map<json>? details = (), [string, string...]? callerRoles = (),
        string? userId = ()) returns error? {
    map<json> payload = {approved: false, __rejected: true, reason: reason};
    if details is map<json> {
        payload["details"] = details;
    }
    return completeHumanTask(taskWorkflowId, payload, callerRoles, userId);
}

# Cancels a pending human task by terminating its child workflow.
# Unlike `failHumanTask`, cancel does not send a result to the waiting workflow —
# the child workflow is terminated immediately, which causes the parent workflow's
# `awaitHumanTask` to return a `HumanTaskTimeoutError` or a terminal error.
#
# ```ballerina
# check management:cancelHumanTask(taskId, cancelledBy = "admin@example.com");
# ```
#
# + taskWorkflowId - Temporal workflow ID of the human task child workflow
# + cancelledBy - Optional user ID recorded in the termination reason for audit purposes
# + return - An error if the task cannot be found or terminated
public isolated function cancelHumanTask(string taskWorkflowId, string? cancelledBy = ()) returns error? {
    check assertIsHumanTaskNative(taskWorkflowId);
    return terminateWorkflow(taskWorkflowId, "",
            "Cancelled via management API" + (cancelledBy is string ? " by " + cancelledBy : ""));
}

isolated function assertIsHumanTaskNative(string taskId) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative",
    name: "assertIsHumanTask"
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
# + userId - Optional user identifier stored in the audit trail (from `x-user-id` header)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeRetryTask(string taskWorkflowId, RetryDecision decision,
        [string, string...]? callerRoles = (), string? userId = ()) returns error? = @java:Method {
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

# Lists all manual retry task instances across all parent workflows, with optional filters.
# Queries Temporal's visibility API for executions whose workflow ID starts with `retrytask-`.
#
# ```ballerina
# management:RetryTaskSummary[] pending = check management:listAllRetryTasks(status = "PENDING");
#
# management:RetryTaskSummary[] recent =
#     check management:listAllRetryTasks(startTimeFrom = "2026-06-01T00:00:00Z");
# ```
#
# + status - Optional status filter: `PENDING` | `COMPLETED` | `CANCELED` | `TERMINATED`
# + startTimeFrom - Optional ISO-8601 lower bound on task start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on task start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on task close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on task close time (inclusive)
# + return - Array of retry task summaries, or an error
public isolated function listAllRetryTasks(string? status = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = ()) returns RetryTaskSummary[]|error = @java:Method {
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

// ================================================================================
// WORKFLOW LIFECYCLE — TERMINATE AND CANCEL
// ================================================================================

# Terminates a running workflow immediately with an optional reason.
# Unlike cancel, terminate does not allow the workflow to perform cleanup.
#
# + workflowId - The workflow ID to terminate
# + runId - The specific run ID to terminate (pass empty string to use latest run)
# + reason - Optional human-readable reason
# + return - An error if the workflow cannot be found or terminated
public isolated function terminateWorkflow(string workflowId, string runId,
        string? reason = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Requests graceful cancellation of a running workflow.
# The workflow can handle the cancellation and perform cleanup before stopping.
#
# + workflowId - The workflow ID to cancel
# + runId - The specific run ID to cancel (pass empty string to use latest run)
# + return - An error if cancellation cannot be requested
public isolated function cancelWorkflow(string workflowId, string runId) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// WORKFLOW LISTING AND STARTING
// ================================================================================

# Starts a new workflow instance by its registered type name.
#
# + workflowType - The registered workflow type (function name)
# + input - Workflow input as a JSON-compatible value
# + workflowId - Optional explicit workflow ID; a UUID-v7 is generated if omitted
# + timeoutSeconds - Optional workflow execution timeout in seconds
# + startedBy - Optional starter user ID; stored with workflow metadata for filtering
# + return - Handle with workflowId and runId, or an error
public isolated function startWorkflowByType(string workflowType, json? input,
    string? workflowId = (), int? timeoutSeconds = (), string? startedBy = ())
    returns WorkflowHandle|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Lists workflow instances with optional filtering and pagination.
# Excludes humantask- and retrytask- child workflows automatically.
#
# + status - Optional status filter: `RUNNING` | `COMPLETED` | `FAILED` | `CANCELED` | `TERMINATED`
# + workflowType - Optional workflow type filter
# + workflowId - Optional workflow ID prefix filter
# + startedBy - Optional starter user ID filter (set via management API `x-user-id` when started)
# + 'limit - Maximum number of results (capped at maxPageSize)
# + pageToken - Opaque continuation token from a prior call
# + startTimeFrom - Optional ISO-8601 lower bound on workflow start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on workflow start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on workflow close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on workflow close time (inclusive)
# + return - Paginated list of workflow instance summaries, or an error
public isolated function listWorkflowInstances(string? status = (), string? workflowType = (),
    string? workflowId = (), string? startedBy = (), int 'limit = 20, string? pageToken = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = ())
        returns WorkflowInstancePage|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// EXECUTION VISUALIZATION
// ================================================================================

# Returns all execution history events for a workflow run in chronological order.
# Each event includes an event-type-specific attribute map suitable for timeline display.
#
# + workflowId - The workflow instance ID
# + runId - The specific run ID (pass empty string for the latest run)
# + return - Ordered array of history events, or an error
public isolated function getWorkflowHistory(string workflowId, string runId)
        returns HistoryEvent[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Parses the workflow execution history and returns a flat ordered list of activity,
# child-workflow, timer, and signal nodes with their status, timing, and I/O.
# Human-task and retry-task child workflows are classified with their specific types.
#
# + workflowId - The workflow instance ID
# + runId - The specific run ID (pass empty string for the latest run)
# + return - Ordered array of tree nodes, or an error
public isolated function getActivityTree(string workflowId, string runId)
        returns ActivityTreeNode[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Derives a directed execution graph from the workflow history suitable for
# rendering with D3.js or React Flow. Nodes represent execution steps;
# edges connect them in the order they were scheduled.
#
# + workflowId - The workflow instance ID
# + runId - The specific run ID (pass empty string for the latest run)
# + return - Graph with nodes and edges, or an error
public isolated function getExecutionGraph(string workflowId, string runId)
        returns ExecutionGraph|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

// ================================================================================
// HTTP SERVICE
// ================================================================================
// The management HTTP service is started automatically via the module-level
// listener in service.bal (port 7235 by default). Configure it in Config.toml:
//
//   management_service_port = 9090
//   enableTls = false
//   enableBasicAuth = false
//
// See service.bal for the full list of configurable variables.
