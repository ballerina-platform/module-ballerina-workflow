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

// ================================================================================
// TYPES
// ================================================================================

# Describes a registered workflow type for use by the workflow launcher UI.
#
# + workflowType - Registered workflow function name (Temporal workflow type)
# + kind - What the definition starts: a `@workflow:Workflow` function (`WORKFLOW`)
#          or a `workflow:DurableAgent` declaration (`AGENT`). Both start through the
#          same endpoint and list as one set of definitions
# + inputSchema - JSON Schema of the start input for form rendering: a workflow's
#                 input parameters, or — for an agent — the uniform `{query, input}`
#                 start envelope, where `input` carries the declared `inputType`'s own
#                 schema and is absent when the agent declares no payload.
#                 `()` when the schema is unavailable
# + isActive - Whether this workflow type has an active registered worker
# + workerCount - Number of workers currently registered for this workflow type
public type WorkflowDefinition record {|
    string workflowType;
    string kind = "WORKFLOW";
    string? inputSchema;
    boolean isActive;
    int workerCount;
|};

# Information about a workflow execution (for testing/introspection).
# + workflowId - The unique identifier for the workflow instance
# + workflowType - The type (process name) of the workflow
# + status - The execution status ("RUNNING", "SUSPENDED", "COMPLETED", "FAILED", "CANCELED", "TERMINATED").
#            "SUSPENDED" is a running workflow paused via the suspend management API.
# + result - The workflow result if completed successfully
# + errorMessage - Error message if the workflow failed
# + activityInvocations - List of activities invoked by this workflow
public type WorkflowExecutionInfo record {
    string workflowId;
    string workflowType;
    string status;
    # What this instance is — WORKFLOW, AGENT, HUMAN_TASK, REVIEW_ACTIVITY or CHILD_WORKFLOW —
    # from the memo its starter stamped. A consumer routes to the right view by asking this,
    # never by parsing the id; nil only for instances started before the stamp existed.
    string? kind = ();
    anydata? result;
    string? errorMessage;
    ActivityInvocation[] activityInvocations;
};


# Information about an activity invocation (for testing/introspection).
# + activityName - The name of the activity that was invoked
# + input - The arguments passed to the activity
# + output - The result returned by the activity (nil if not yet completed or failed)
# + status - The status of the activity execution ("COMPLETED", "FAILED", "RUNNING", "PENDING")
# + errorMessage - Error message if the activity failed
# + attempt - The attempt number for this invocation (1-based; values greater than 1 indicate a retry)
public type ActivityInvocation record {
    string activityName;
    anydata[] input;
    anydata? output;
    string status;
    string? errorMessage;
    int attempt?;
};

# Groups human task instances by task type for a single parent workflow.
#
# + taskName - The task type name (the `taskName` passed to `awaitHumanTask`)
# + taskIds - Child workflow IDs of pending instances of this task type,
#             in the order they were started
public type HumanTaskGroup record {|
    string taskName;
    string[] taskIds;
|};

# What every task instance reports in a list, whichever kind it is: identity, where it came
# from, who may act on it, and who did.
#
# + kind - `HUMAN_TASK` or `REVIEW_ACTIVITY`
# + taskId - Child workflow ID of this task instance (a bare UUID; the kind travels in its memo)
# + taskName - Qualified task name (`workflowDefinition.taskOrActivityName`)
# + title - Display title given at creation, falling back to the task name when none was set
# + description - Supporting context shown with the form or decision
# + parentWorkflowId - Workflow ID of the parent that created this task
# + parentWorkflowType - Registered workflow type of the parent, or `()` if not available
# + stepId - The call site that created the task, or `()` when the parent did not name it
# + status - Current status, mirroring the underlying task workflow:
#            `PENDING` (awaiting a human) | `COMPLETED` (a human submitted a result or decision) |
#            `FAILED` (rejected via the fail operation, or timed out before anyone acted) |
#            `CANCELED` (retired internally because the parent workflow closed) |
#            `TERMINATED` (an admin terminated the task workflow)
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
# + userRoles - Roles permitted to act on this task
# + users - User ids permitted to act on it, whatever their roles
# + excludedUsers - User ids that may not act on it
# + excludedRoles - Roles that may not act on it
# + completedBy - User ID of whoever completed, rejected or decided it, or `()` while pending. Also
#                 `()` for tasks decided before the completer was recorded on the row
# + completedAt - When that decision was recorded, or `()` when unknown
# + canComplete - Whether the requesting caller may act on it
public type TaskSummary record {|
    string kind;
    string taskId;
    string taskName;
    string title = "";
    string description = "";
    # The Temporal namespace the task lives in (the project scope)
    string namespace?;
    # The task queue of the integration serving this task; route mutations there
    string taskQueue?;
    string parentWorkflowId;
    string? parentWorkflowType = ();
    string? stepId = ();
    string status;
    string startTime;
    string? closeTime;
    string[] userRoles = [];
    string[] users = [];
    string[] excludedUsers = [];
    string[] excludedRoles = [];
    string? completedBy = ();
    string? completedAt = ();
    boolean canComplete = false;
|};

# Summary of a human task instance for list views.
public type HumanTaskSummary record {|
    *TaskSummary;
|};

# One item of a person's unified work queue: a human task, or a review activity — which is a
# human task with a fixed decision contract. The kinds stay distinct (each opens its own UX);
# what they share is the queue and its filters.
#
# + trigger - Reviews only: `PRE_RUN` (approval gate) | `ON_FAILURE` (rerun decision)
public type WorkItemSummary record {|
    *TaskSummary;
    string? trigger = ();
|};

# One page of the unified work queue.
#
# + items - The page of work items
# + nextPageToken - Cursor for the next page, or `()` on the last one
# + hasMore - Whether more items exist past this page
public type WorkItemPage record {|
    WorkItemSummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};

# What every task instance reports in detail, on top of its summary.
#
# + createdAt - ISO-8601 timestamp stored in memo at task start
# + formSchema - JSON Schema for the completion form (populated by compiler plugin; `()` until then)
public type TaskInfo record {|
    *TaskSummary;
    string createdAt;
    string? formSchema;
|};

# Detailed info about a human task, including memo fields set at task creation.
#
# + taskInput - Read-only context map rendered alongside the form
# + result - The value submitted when completing the task, or `()` if not yet completed
public type HumanTaskInfo record {|
    *TaskInfo;
    map<json>? taskInput;
    json? result;
|};

// ================================================================================
// MANUAL RETRY TASK TYPES
// ================================================================================

# Decision submitted by a human to resolve a review activity — a proposed
# activity call awaiting approval before it runs, or a failed activity awaiting
# a rerun decision.
#
# + action - `"proceed"` runs (or reruns) the activity with the original arguments;
#            `"proceed-with-input"` runs it with the `input` map overriding arguments;
#            `"reject"` skips the activity: the proposed call is not made, or the
#            original failure is surfaced back to the workflow.
# + input - New named arguments for the activity. Only relevant when `action` is
#           `"proceed-with-input"`. Keys must match the activity's parameter names.
# + feedback - Optional reviewer note. On `"reject"` it is relayed to the caller
#              so the workflow can act on it (e.g. surface it in the failure message).
public type ReviewDecision record {|
    "proceed"|"proceed-with-input"|"reject" action;
    map<anydata>? input = ();
    string? feedback = ();
|};

# Summary of a review activity instance for list views.
#
# + activityName - Fully-qualified name of the reviewed activity (`workflowType.activityName`)
# + trigger - Why the review was created: `PRE_RUN` (approval gate) | `ON_FAILURE` (rerun decision)
public type ReviewActivitySummary record {|
    *TaskSummary;
    string activityName;
    string trigger;
|};

# Detailed info about a review activity, including the proposal or failure context.
#
# + activityName - Fully-qualified name of the reviewed activity
# + trigger - Why the review was created: `PRE_RUN` (approval gate) | `ON_FAILURE` (rerun decision)
# + errorMessage - Error message from the failed activity invocation (empty for a pre-run gate)
# + taskInput - Arguments proposed for (or passed to) the activity invocation; use these to
#               pre-fill the `formSchema` form
# + decision - The decision recorded, or `()` while pending
public type ReviewActivityInfo record {|
    *TaskInfo;
    string activityName;
    string trigger;
    string errorMessage;
    map<json>? taskInput;
    ReviewDecision? decision;
|};

// ================================================================================
// COMPLETION AUDIT
// ================================================================================

# Audit record returned by human task completion operations.
#
# + success - Always true on the success path
# + completedBy - User ID extracted from the `x-user-id` request header
# + completedAt - ISO-8601 timestamp of when the completion was processed
public type CompletionInfo record {|
    boolean success;
    string completedBy;
    string completedAt;
|};

# Audit record returned by review activity decision operations.
#
# + success - Always true on the success path
# + decision - The decision taken: `"proceed"`, `"proceed-with-input"`, or `"reject"`
# + completedBy - User ID extracted from the `x-user-id` request header
# + completedAt - ISO-8601 timestamp of when the decision was processed
public type ReviewDecisionInfo record {|
    boolean success;
    string decision;
    string completedBy;
    string completedAt;
|};

# What deciding one review activity in bulk needs to know about it: whether it
# reviews a failure, whether it is still open, and who may decide it.
#
# Deliberately not `ReviewActivityInfo`. That record also reports who decided and
# when, which live only in the workflow's history and cost two paginated scans to
# read — a price a batch would pay per task for fields it never looks at.
#
# + trigger - `ON_FAILURE` for a failed activity, `PRE_RUN` for a gated call
# + status - `PENDING` while the review is still open
# + userRoles - Roles permitted to decide it; with `users` empty, means unrestricted
# + users - User ids permitted to decide it
# + excludedUsers - User ids that may not decide it
# + excludedRoles - Roles that may not decide it
type ReviewActivityState record {|
    string trigger;
    string status;
    string[] userRoles;
    string[] users = [];
    string[] excludedUsers = [];
    string[] excludedRoles = [];
|};

// ================================================================================
// BULK RETRY TYPES
// ================================================================================
// Applying one decision to many failed-activity reviews. The decision is limited to
// retry or fail: there is no field for replacement arguments anywhere in the request,
// so a bulk decision cannot change the payload an activity is retried with. Editing
// arguments stays a single-task operation (`proceed-with-input`), where the reviewer
// sees the activity they are editing.

# What to do with each review activity in a bulk decision.
#
# `"retry"` reruns the activity with its original arguments (the single-task
# `proceed` decision); `"fail"` surfaces the original failure to the workflow (the
# single-task `reject` decision).
public type BulkRetryAction "retry"|"fail";

# What happened to one review activity in a bulk decision.
#
# `APPLIED` — the decision was submitted.
# `SKIPPED` — the task was not eligible and nothing was submitted: it was already
# decided, or it gates a proposed call (`PRE_RUN`) rather than reviewing a failure.
# `FAILED` — the decision could not be submitted: the task does not exist, the caller
# may not decide it, or the runtime rejected it.
public enum BulkItemOutcome {
    APPLIED,
    SKIPPED,
    FAILED
}

# The outcome of one review activity within a bulk decision.
#
# + taskId - Workflow ID of the review activity this outcome belongs to
# + outcome - Whether the decision was applied, skipped, or failed
# + reason - Why, for `SKIPPED` and `FAILED`; `()` when the decision was applied
public type BulkItemResult record {|
    string taskId;
    BulkItemOutcome outcome;
    string? reason;
|};

# The result of a bulk decision. A bulk decision reports per-task outcomes rather
# than failing as a whole: one task decided by another operator in the meantime, or
# one the caller may not decide, does not stop the rest.
#
# + action - The decision applied to every eligible task
# + requested - Number of tasks the selector resolved to
# + applied - Number of tasks the decision was submitted for
# + skipped - Number of tasks that were not eligible
# + failed - Number of tasks the decision could not be submitted for
# + items - Per-task outcomes, in the order the tasks were processed
# + decidedBy - User ID of the caller, or `"unknown"` when the caller presented none
# + decidedAt - ISO-8601 timestamp of when the bulk decision was processed
public type BulkRetryResult record {|
    BulkRetryAction action;
    int requested;
    int applied;
    int skipped;
    int failed;
    BulkItemResult[] items;
    string decidedBy;
    string decidedAt;
|};

// ================================================================================
// RESET TYPES
// ================================================================================
// Resetting replays a run up to a chosen point and re-executes everything after it
// as a new run of the same workflow ID. The point is a *workflow task*, not an
// activity: activities scheduled by one task always come back together, and every
// step after the point re-runs — including the error handling and compensation the
// workflow already performed.

# An event a run can be reset to, and what resetting there re-runs.
#
# + eventId - Workflow-task event ID to reset to
# + eventType - The eligible workflow-task event: `WORKFLOW_TASK_COMPLETED`,
#               `WORKFLOW_TASK_FAILED`, or `WORKFLOW_TASK_TIMED_OUT`
# + timestamp - ISO-8601 time of the event
# + nodeIds - Activity-tree node IDs this task scheduled — all of them re-execute
#             together. Empty when the task scheduled no visible work.
# + nodeNames - Display names for `nodeIds`, in the same order
# + isFirstFailure - True for the point that re-runs the run's first failed step —
#                    the default "retry from where it broke"
public type ResetPoint record {|
    int eventId;
    string eventType;
    string timestamp;
    string[] nodeIds;
    string[] nodeNames;
    boolean isFirstFailure;
|};

# Which point of a run to reset to.
#
# `"first-workflow-task"` replays the run from its first workflow task, so it runs
# again from the beginning with the input it started with. `"last-workflow-task"`
# resets to the most recent workflow task, which is how a run wedged on a failing
# workflow task is moved onto fixed code. `"workflow-task-id"` targets one point
# from `listResetPoints`, which is how a caller starts from a selected step.
public type ResetTypeName "first-workflow-task"|"last-workflow-task"|"workflow-task-id";

# Which post-reset events are re-delivered to the new run.
#
# + 'type - `"signal"` re-delivers signals (the engine default), `"none"` re-delivers
#           nothing, and `"all-eligible"` also re-delivers updates. Durable agent turns
#           arrive as updates, so an agent reset with `"signal"` replays the agent
#           without its conversation.
# + exclude - Event categories to withhold even when `'type` would re-deliver them
public type ResetReapply record {|
    "signal"|"none"|"all-eligible" 'type = "signal";
    ("signal"|"update"|"nexus"|"cancel-request")[] exclude = [];
|};

// ================================================================================
// EXECUTION VISUALIZATION TYPES
// ================================================================================

# A single failure description extracted from a Temporal activity or child-workflow failure.
#
# + message - Human-readable failure message
# + 'type - Application failure type string, or `()` if unavailable
# + cause - Message of the root-cause failure, or `()` if no cause chain
public type FailureInfo record {|
    string message;
    string? 'type;
    string? cause;
|};

# A single event from the Temporal workflow execution history.
#
# + eventId - Monotonically increasing event sequence number
# + eventType - Temporal event type name (e.g. `ACTIVITY_TASK_SCHEDULED`)
# + timestamp - ISO-8601 wall-clock timestamp of the event
# + attributes - Event-type-specific attribute map
public type HistoryEvent record {|
    int eventId;
    string eventType;
    string timestamp;
    map<json> attributes;
|};

# Classification of a node in the activity tree or execution graph. `DATA` marks
# a received data event (`workflow:sendData` answering a `wait dataEvents.<name>`).
public enum ActivityNodeType {
    ACTIVITY,
    TIMER,
    DATA,
    CHILD_WORKFLOW,
    HUMAN_TASK,
    REVIEW_ACTIVITY
}

# A node in the activity execution tree for a workflow instance.
#
# + id - Unique node identifier (Temporal scheduledEventId or initiatedEventId as string)
# + name - Activity, task, or workflow type name
# + 'type - Node classification
# + status - Current status: RUNNING | WAITING | COMPLETED | FAILED | TIMED_OUT | CANCELED
#            (WAITING marks a data event the workflow is currently blocked on)
# + startTime - ISO-8601 timestamp when this node started, or `()`
# + endTime - ISO-8601 timestamp when this node ended, or `()` if still running
# + input - Decoded activity/workflow input, or `()`
# + output - Decoded activity/workflow result, or `()`
# + failure - Failure detail if the node failed, otherwise `()`
# + attempt - Temporal attempt number (1-indexed)
# + stepId - Which step of the workflow ran: the id chosen with `stepId` at the call site, or the
#            generated `<target>#<ordinal>`, matching a node in the descriptor's `graph`. `()` when
#            the execution carries none — an instance started before the runtime recorded it
# + children - Nested child nodes, or `()` for leaf nodes
public type ActivityTreeNode record {|
    string id;
    string name;
    ActivityNodeType 'type;
    string status;
    string? startTime;
    string? endTime;
    anydata? input;
    anydata? output;
    FailureInfo? failure;
    int attempt;
    string? stepId = ();
    # For a review: its own node in the descriptor graph (`<reviewedStep>#review`). Nil for
    # other node kinds
    string? reviewStepId = ();
    ActivityTreeNode[]? children;
|};

# Directed graph representing workflow execution flow for visualization.
#
# + nodes - Graph nodes (activities, tasks, timers, signals)
# + edges - Directed edges connecting nodes in execution order
public type ExecutionGraph record {|
    GraphNode[] nodes;
    GraphEdge[] edges;
|};

# A node in the execution graph.
#
# + id - Unique node identifier
# + label - Display label
# + 'type - Node classification (same values as `ActivityNodeType`)
# + status - Current status
# + metadata - Optional extra key-value pairs for the UI: `taskId` for human tasks, and
#              `stepId` — which step ran, for highlighting the descriptor's graph
public type GraphNode record {|
    string id;
    string label;
    ActivityNodeType 'type;
    string status;
    map<json>? metadata;
|};

# A directed edge in the execution graph.
#
# + 'source - Source node ID
# + target - Target node ID
# + label - Optional edge label
public type GraphEdge record {|
    string 'source;
    string target;
    string? label;
|};

// ================================================================================
// HTTP SERVICE CONFIGURATION
// ================================================================================

// ================================================================================
// WORKFLOW INSTANCE TYPES
// ================================================================================

# Summary of a workflow instance for list views.
#
# + workflowId - Unique workflow instance ID
# + runId - Temporal run ID for this execution
# + workflowType - Registered workflow type name
# + status - Execution status: RUNNING | SUSPENDED | COMPLETED | FAILED | CANCELED | TERMINATED | TIMED_OUT.
#            SUSPENDED is a running workflow paused via the suspend management API.
# + startTime - ISO-8601 timestamp when the workflow started
# + closeTime - ISO-8601 timestamp when it ended, or `()` if still running
# + kind - What this instance is — WORKFLOW, AGENT, HUMAN_TASK, REVIEW_ACTIVITY, CHILD_WORKFLOW —
#          from the memo its starter stamped (ids carry no classification)
# + input - Workflow input as JSON, or `()` if not available
public type WorkflowInstanceSummary record {|
    # The Temporal namespace the task lives in (the project scope)
    string namespace?;
    # The task queue of the integration serving this task; route mutations there
    string taskQueue?;
    string workflowId;
    string runId;
    string workflowType;
    string status;
    string startTime;
    string? closeTime;
    string? kind = ();
    json? input;
|};

# Paginated list of workflow instances.
#
# + items - Workflow summaries for this page
# + nextPageToken - Opaque token to fetch the next page, or `()` on the last page
# + hasMore - True when more pages follow
public type WorkflowInstancePage record {|
    WorkflowInstanceSummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};

# Handle returned when a new workflow is started.
#
# + workflowId - Unique ID of the started workflow instance
# + runId - Temporal run ID
public type WorkflowHandle record {|
    string workflowId;
    string runId;
|};

// ================================================================================
// PAGINATED TASK TYPES
// ================================================================================

# Paginated list of human task summaries.
#
# + items - Human task summaries for this page
# + nextPageToken - Opaque continuation token, or `()` on the last page
# + hasMore - True when more pages follow
public type HumanTaskPage record {|
    HumanTaskSummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};

# Paginated list of review activity summaries.
#
# + items - Review activity summaries for this page
# + nextPageToken - Opaque continuation token, or `()` on the last page
# + hasMore - True when more pages follow
public type ReviewActivityPage record {|
    ReviewActivitySummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};
