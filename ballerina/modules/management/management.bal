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

// Captures this submodule's reference so native code can create records in this
// module. This module is a pure Ballerina API: it opens no port and starts no
// service. The management HTTP API lives in `ballerina/workflow.management.rest`,
// which imports this module and owns the listener lifecycle.
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

# Gets the latest response produced by a durable agent (a `workflow:DurableAgent`).
# In a multi-turn conversation this is the answer of the most recent turn; it is
# available while the agent is still running (e.g. suspended waiting for the next
# chat event) as well as after it completes.
#
# ```ballerina
# import ballerina/workflow.management;
#
# string? answer = check management:getAgentResponse(agentId);
# ```
#
# + agentId - The agent's workflow ID (from `workflow:run`)
# + return - The latest response text, `()` when the agent has not produced one yet,
#            or an error
public isolated function getAgentResponse(string agentId) returns string?|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Lists all workflow types registered with this worker, for use in the workflow launcher UI.
# Returns one entry per registered workflow function. The `inputSchema` field is derived at
# runtime from the registered workflow function's signature, or `()` when the workflow takes
# no data input.
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
# Sends a `__wf_suspend` signal; the workflow stops making progress at its next durable
# operation (activity call, timer, human task, retry task, or child workflow) and
# holds there until `resumeWorkflow` is called. While suspended, the workflow's reported
# status is `SUSPENDED`. An operation already in flight when the signal arrives finishes
# first — suspension takes effect at the next operation boundary.
#
# ```ballerina
# check management:suspendWorkflow(workflowId);
# ```
#
# Wakes a durable agent instance out of its built-in `sleep` tool by sending the
# `__agent_wake` signal. Harmless when the instance is not sleeping: the request
# is consumed by the next sleep.
#
# ```ballerina
# check management:wakeAgent(instanceId);
# ```
#
# + workflowId - The agent instance ID to wake
# + return - An error if the signal cannot be delivered
public isolated function wakeAgent(string workflowId) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

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
# event history for child workflow start events whose workflow TYPE has the
# `humantask-` prefix (the ID itself is a bare UUID; what a task is travels in its
# type and memo).
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
# Queries Temporal's visibility API for executions whose workflow TYPE starts with
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
# + status - Optional status filter: `PENDING` | `COMPLETED` | `FAILED` | `CANCELED` | `TERMINATED`
# + startTimeFrom - Optional ISO-8601 lower bound on task start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on task start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on task close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on task close time (inclusive)
# + taskQueue - Optional task queue filter: only tasks served by that integration.
#               Omitted, all task queues in the configured namespace are returned
# + return - Array of human task summaries, or an error
public isolated function listAllHumanTasks(string? status = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = (),
        string? taskQueue = ()) returns HumanTaskSummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns detailed info for a single human task, including memo fields.
# Calls Temporal DescribeWorkflowExecution to read the memo set at task creation.
#
# ```ballerina
# management:HumanTaskInfo info = check management:getHumanTaskInfo(taskId);
# ```
#
# + taskId - The child workflow ID of the human task (a bare UUID; the kind travels in its memo)
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
# + details - Optional structured details about the failure (recorded with the rejection)
# + callerRoles - Roles held by the caller; validated against the task's `userRoles`
# + userId - Optional user identifier stored in the audit trail (from `x-user-id` header)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function failHumanTask(string taskWorkflowId, string reason,
        map<json>? details = (), [string, string...]? callerRoles = (),
        string? userId = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "failHumanTask"
} external;

// Note: there is deliberately no cancel operation for human tasks. A task is CANCELED
// only internally — when its parent workflow closes and the task is retired. Admins who
// must force-stop a task can terminate it (TERMINATED) via terminateWorkflow.

// ================================================================================
// REVIEW ACTIVITIES
// ================================================================================
//
// A review activity is a human reviewing an activity call. It is created either
// before a gated activity runs (an approval gate, PRE_RUN — used by upcoming
// gated-activity policies)
// or after an activity fails under the ManualRetry policy (a rerun decision,
// ON_FAILURE). Both surface the same decision: proceed / proceed-with-input /
// reject.

# Completes a pending review activity by sending the human's decision back to the
# waiting workflow. The `taskWorkflowId` is the child workflow ID of the review
# activity, available via `listPendingReviewActivities` or `listAllReviewActivities`.
#
# ```ballerina
# // Proceed with the original arguments (run the gated call / rerun the failed one)
# check management:completeReviewActivity(taskId, {action: "proceed"});
#
# // Proceed with edited arguments
# check management:completeReviewActivity(taskId, {action: "proceed-with-input", input: {"orderId": "NEW-123"}});
#
# // Reject: skip the call / fail the activity, with feedback for the agent
# check management:completeReviewActivity(taskId, {action: "reject", feedback: "Amount too high"});
# ```
#
# + taskWorkflowId - Temporal workflow ID of the review activity child workflow (a bare UUID;
#                    its `reviewactivity-`-prefixed kind travels in the workflow type and memo)
# + decision - The review decision: proceed, proceed with new input, or reject
# + callerRoles - Roles held by the caller; validated against the task's configured `userRoles`
# + userId - Optional user identifier stored in the audit trail (from `x-user-id` header)
# + return - An error if the task cannot be found, is already completed, or the caller is unauthorized
public isolated function completeReviewActivity(string taskWorkflowId, ReviewDecision decision,
        [string, string...]? callerRoles = (), string? userId = ()) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative",
    name: "completeReviewActivity"
} external;

# Returns pending review activity child workflows started by the given parent workflow,
# grouped by task name and sorted alphabetically. Scans the parent's event history for
# child workflow start events whose workflow TYPE has the `reviewactivity-` prefix (the
# ID itself is a bare UUID).
#
# ```ballerina
# management:ReviewActivitySummary[] tasks = check management:listPendingReviewActivities(parentWorkflowId);
# foreach management:ReviewActivitySummary task in tasks {
#     check management:completeReviewActivity(task.taskId, {action: "proceed"});
# }
# ```
#
# + parentWorkflowId - The Temporal workflow ID of the parent workflow
# + return - Array of pending review activity summaries, or an error
public isolated function listPendingReviewActivities(string parentWorkflowId)
        returns ReviewActivitySummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative",
    name: "listPendingReviewActivities"
} external;

# Lists all review activity instances across all parent workflows, with optional filters.
# Queries Temporal's visibility API for executions whose workflow TYPE starts with
# `reviewactivity-`.
#
# ```ballerina
# management:ReviewActivitySummary[] pending = check management:listAllReviewActivities(status = "PENDING");
#
# management:ReviewActivitySummary[] recent =
#     check management:listAllReviewActivities(startTimeFrom = "2026-06-01T00:00:00Z");
# ```
#
# + status - Optional status filter: `PENDING` | `COMPLETED` | `FAILED` | `CANCELED` | `TERMINATED`
# + startTimeFrom - Optional ISO-8601 lower bound on task start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on task start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on task close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on task close time (inclusive)
# + taskQueue - Optional task queue filter; without it, every queue in the configured namespace
# + return - Array of review activity summaries, or an error
public isolated function listAllReviewActivities(string? status = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = (),
        string? taskQueue = ()) returns ReviewActivitySummary[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative",
    name: "listAllReviewActivities"
} external;

# Reads only the facts a bulk decision needs about a review activity, with one
# describe and no history scan. Not public: it exists so a batch does not pay
# `getReviewActivityInfo`'s two history scans per task for audit fields it never reads.
#
# + taskId - The review activity's workflow ID
# + return - Its trigger, status, and permitted roles, or an error when the ID is not
#            a review activity
isolated function getReviewActivityState(string taskId) returns ReviewActivityState|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns detailed info for a single review activity, including the failure context,
# the activity arguments that triggered the task, and the JSON Schema of the input
# accepted by the `proceed-with-input` decision (`formSchema`).
#
# ```ballerina
# management:ReviewActivityInfo info = check management:getReviewActivityInfo(taskId);
# ```
#
# + taskId - The child workflow ID of the review activity (a bare UUID; the kind travels in its memo)
# + return - Full review activity info including errorMessage, activityArgs, formSchema, and userRoles,
#            or an error (including when the ID refers to a human task or any non-review workflow)
public isolated function getReviewActivityInfo(string taskId) returns ReviewActivityInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative",
    name: "getReviewActivityInfo"
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
# + input - Workflow input as a JSON-compatible value. A durable agent is started with
#           its `{query, input}` envelope: `query` is the user turn, and `input` is the
#           payload validated against the agent's declared `inputType`
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
# Excludes humantask- and reviewactivity- child workflows automatically.
#
# + status - Optional status filter: `RUNNING` | `SUSPENDED` | `COMPLETED` | `FAILED` | `CANCELED` | `TERMINATED`.
#            `RUNNING` excludes suspended workflows; `SUSPENDED` returns only workflows
#            paused via the suspend management API.
# + workflowType - Optional workflow type filter
# + workflowId - Optional workflow ID prefix filter
# + startedBy - Optional starter user ID filter (set via management API `x-user-id` when started)
# + 'limit - Maximum number of results (capped at maxPageSize)
# + pageToken - Opaque continuation token from a prior call
# + startTimeFrom - Optional ISO-8601 lower bound on workflow start time (inclusive)
# + startTimeTo - Optional ISO-8601 upper bound on workflow start time (inclusive)
# + closeTimeFrom - Optional ISO-8601 lower bound on workflow close time (inclusive)
# + closeTimeTo - Optional ISO-8601 upper bound on workflow close time (inclusive)
# + taskQueue - Optional task queue filter; without it, every queue in the configured namespace
# + kind - Optional kind filter: `WORKFLOW`, `HUMAN_TASK`, `REVIEW_ACTIVITY`, `CHILD_WORKFLOW`
#          or `AGENT`. Without it the listing excludes task and review children, as it did
#          before kinds existed. Each summary reports its own `kind`, so an unfiltered listing
#          is still self-describing. Filtering needs the `WorkflowKind` search attribute; where
#          the server has none — the in-memory dev server, which supports no custom attributes —
#          the listing comes back unfiltered with a warning rather than failing.
# + return - Paginated list of workflow instance summaries, or an error
public isolated function listWorkflowInstances(string? status = (), string? workflowType = (),
    string? workflowId = (), string? startedBy = (), int 'limit = 20, string? pageToken = (),
        string? startTimeFrom = (), string? startTimeTo = (),
        string? closeTimeFrom = (), string? closeTimeTo = (),
        string? taskQueue = (), string? kind = ())
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

# Resolves the run a reset acts on, so the run whose points are validated is the run
# that is reset. Module-private: it exists to pin "latest" for the two calls a reset
# makes, not as API.
#
# + workflowId - The workflow instance ID
# + runId - An explicit run ID, or `""` for the latest run
# + return - The concrete run ID, or an error
isolated function resolveRunId(string workflowId, string runId) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Finds the run's first or last workflow-task event without reading its whole history.
# Module-private: resetting to the beginning or the tail needs one event, and the full
# read refuses the long-lived and wedged runs those targets exist for.
#
# + workflowId - The workflow instance ID
# + runId - The run ID, or `""` for the latest run
# + first - `true` for the first workflow task, `false` for the last
# + return - The event ID, or an error when the run has no workflow task yet
isolated function findBoundaryWorkflowTask(string workflowId, string runId, boolean first)
        returns int|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Returns the events this run can be reset to — its workflow-task events — each
# annotated with the activity-tree nodes that task scheduled. A reset target is a
# workflow task, so the annotation is what lets a caller see which steps a point
# re-runs before choosing it.
#
# + workflowId - The workflow instance ID
# + runId - The specific run ID (pass empty string for the latest run)
# + return - Ordered array of reset points, or an error
public isolated function listResetPoints(string workflowId, string runId)
        returns ResetPoint[]|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.ManagementNative"
} external;

# Resets a run to a workflow-task event: history up to that point is preserved and
# everything after it re-executes as a **new run of the same workflow ID**.
#
# Everything downstream of the point runs again, including the error handling and
# compensation the workflow already performed on its first pass, and replay happens
# against the worker's current code — a workflow function that changed since the run
# started can fail to replay.
#
# + workflowId - The workflow instance ID
# + runId - The specific run ID (pass empty string for the latest run)
# + eventId - The workflow-task event to reset to, from `listResetPoints`
# + reason - Audit reason recorded with the reset
# + reapplyType - `signal` | `none` | `all-eligible`
# + reapplyExclude - Event categories to withhold from reapply
# + identity - The caller recorded as the reset's identity
# + idempotencyKey - Identifies the request so a retry is a no-op rather than a second
#                    reset. Pass the request as the caller made it, not what it resolved
#                    to: with `runId` omitted, "latest" names a different run once the
#                    first reset has created one. Empty derives a key from the arguments.
# + return - Handle carrying the unchanged workflow ID and the new run ID, or an error
public isolated function resetWorkflowExecution(string workflowId, string runId, int eventId,
        string reason, string reapplyType, string[] reapplyExclude, string identity,
        string idempotencyKey = "")
        returns WorkflowHandle|error = @java:Method {
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
// The management HTTP API lives in `ballerina/workflow.management.rest`. Import
// that module and enable it in Config.toml to expose these operations over REST:
//
//   import ballerina/workflow.management.rest as _;
//
//   [ballerina.workflow.management.rest]
//   enableManagementApi = true
//   port = 8234
