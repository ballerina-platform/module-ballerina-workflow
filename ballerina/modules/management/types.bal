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
// TYPES
// ================================================================================

# Describes a registered workflow type for use by the workflow launcher UI.
#
# + workflowType - Registered workflow function name (Temporal workflow type)
# + inputSchema - JSON Schema of the workflow's input type for form rendering.
#                 `()` until the compiler plugin generates this at build time.
# + isActive - Whether this workflow type has an active registered worker
# + workerCount - Number of workers currently registered for this workflow type
public type WorkflowDefinition record {|
    string workflowType;
    string? inputSchema;
    boolean isActive;
    int workerCount;
|};

# Information about a workflow execution (for testing/introspection).
# + workflowId - The unique identifier for the workflow instance
# + workflowType - The type (process name) of the workflow
# + status - The execution status ("RUNNING", "COMPLETED", "FAILED", "CANCELED", "TERMINATED")
# + result - The workflow result if completed successfully
# + errorMessage - Error message if the workflow failed
# + activityInvocations - List of activities invoked by this workflow
public type WorkflowExecutionInfo record {
    string workflowId;
    string workflowType;
    string status;
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

# Summary of a human task instance for list views.
#
# + taskId - Child workflow ID of this task instance (`humantask-{parentId}-{taskName}-{uuid}`)
# + taskName - Task type name (the `taskName` passed to `awaitHumanTask`)
# + parentWorkflowId - Workflow ID of the parent that created this task
# + parentWorkflowType - Registered workflow type of the parent, or `()` if not available
# + status - Current status: PENDING | COMPLETED | TIMED_OUT | CANCELED | TERMINATED
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
# + userRoles - Roles permitted to complete this task
# + canComplete - Whether the requesting caller has a role that permits completion
public type HumanTaskSummary record {|
    string taskId;
    string taskName;
    string parentWorkflowId;
    string? parentWorkflowType;
    string status;
    string startTime;
    string? closeTime;
    string[] userRoles;
    boolean canComplete = false;
|};

# Detailed info about a human task, including memo fields set at task creation.
#
# + taskId - Child workflow ID of this task instance
# + taskName - Task type name
# + parentWorkflowId - Workflow ID of the parent that created this task
# + status - Current status: PENDING | COMPLETED | TIMED_OUT | CANCELED | TERMINATED
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
# + title - Display title shown in the task inbox
# + description - Supporting context for the reviewer
# + userRoles - Roles permitted to complete this task
# + payload - Read-only context map rendered alongside the form
# + createdAt - ISO-8601 timestamp stored in memo at task start
# + formSchema - JSON Schema for the completion form (populated by compiler plugin; `()` until then)
# + completedBy - User ID of the person who completed the task, or `()` if not yet completed
# + completedAt - ISO-8601 timestamp when the task was completed, or `()` if pending
# + result - The value submitted when completing the task, or `()` if not yet completed
public type HumanTaskInfo record {|
    string taskId;
    string taskName;
    string parentWorkflowId;
    string status;
    string startTime;
    string? closeTime;
    string title;
    string description;
    [string, string...] userRoles;
    map<json>? payload;
    string createdAt;
    string? formSchema;
    string? completedBy;
    string? completedAt;
    json? result;
|};

// ================================================================================
// MANUAL RETRY TASK TYPES
// ================================================================================

# Decision submitted by a human to resolve a failed activity.
#
# + action - `"retry"` re-runs the activity with the original arguments;
#            `"retry-with-input"` re-runs it with the `input` map overriding arguments;
#            `"fail"` surfaces the original error back to the workflow.
# + input - New named arguments for the activity. Only relevant when `action` is
#           `"retry-with-input"`. Keys must match the activity's parameter names.
public type RetryDecision record {|
    "retry"|"retry-with-input"|"fail" action;
    map<anydata>? input = ();
|};

# Summary of a manual retry task instance for list views.
#
# + taskId - Temporal workflow ID of this retry task (`retrytask-{parentId}-{taskName}-{uuid}`)
# + taskName - User-facing task name (from `ManualRetry.taskName`, qualified with workflow type)
# + activityName - Fully-qualified name of the failed activity (`workflowType.activityName`)
# + parentWorkflowId - Workflow ID of the parent that triggered this task
# + status - Current status: `PENDING` | `COMPLETED` | `CANCELED` | `TERMINATED`
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
public type RetryTaskSummary record {|
    string taskId;
    string taskName;
    string activityName;
    string parentWorkflowId;
    string status;
    string startTime;
    string? closeTime;
|};

# Detailed info about a manual retry task, including the failure context.
#
# + taskId - Temporal workflow ID of this retry task
# + taskName - User-facing task name
# + activityName - Fully-qualified name of the failed activity
# + parentWorkflowId - Workflow ID of the parent that triggered this task
# + status - Current status: `PENDING` | `COMPLETED` | `CANCELED` | `TERMINATED`
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
# + userRoles - Roles permitted to complete this retry task
# + errorMessage - Error message from the failed activity invocation
# + activityArgs - Arguments that were passed to the failed activity invocation
# + createdAt - ISO-8601 timestamp stored in memo at task creation
# + decidedBy - User ID of the person who submitted the retry decision, or `()` if pending
# + decidedAt - ISO-8601 timestamp when the decision was submitted, or `()` if pending
public type RetryTaskInfo record {|
    string taskId;
    string taskName;
    string activityName;
    string parentWorkflowId;
    string status;
    string startTime;
    string? closeTime;
    [string, string...] userRoles;
    string errorMessage;
    map<json>? activityArgs;
    string createdAt;
    string? decidedBy;
    string? decidedAt;
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

# Audit record returned by manual retry task decision operations.
#
# + success - Always true on the success path
# + decision - The decision taken: `"retry"`, `"retry-with-input"`, or `"fail"`
# + decidedBy - User ID extracted from the `x-user-id` request header
# + decidedAt - ISO-8601 timestamp of when the decision was processed
public type RetryDecisionInfo record {|
    boolean success;
    string decision;
    string decidedBy;
    string decidedAt;
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

# Classification of a node in the activity tree or execution graph.
public enum ActivityNodeType {
    ACTIVITY,
    TIMER,
    SIGNAL,
    CHILD_WORKFLOW,
    HUMAN_TASK,
    RETRY_TASK
}

# A node in the activity execution tree for a workflow instance.
#
# + id - Unique node identifier (Temporal scheduledEventId or initiatedEventId as string)
# + name - Activity, task, or workflow type name
# + 'type - Node classification
# + status - Current status: RUNNING | COMPLETED | FAILED | TIMED_OUT | CANCELED
# + startTime - ISO-8601 timestamp when this node started, or `()`
# + endTime - ISO-8601 timestamp when this node ended, or `()` if still running
# + input - Decoded activity/workflow input, or `()`
# + output - Decoded activity/workflow result, or `()`
# + failure - Failure detail if the node failed, otherwise `()`
# + attempt - Temporal attempt number (1-indexed)
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
# + metadata - Optional extra key-value pairs for the UI (e.g. taskId for human tasks)
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

# Configuration for the management HTTP service.
#
# + port - TCP port to listen on
# + basePath - Base path prefix for all endpoints
# + cors - Optional CORS configuration
# + maxPageSize - Maximum allowed page size for list operations
# + defaultPageSize - Default page size when the caller does not specify one
public type ManagementServiceConfig record {|
    int port = 8234;
    string basePath = "/workflow-api";
    CorsConfig? cors = ();
    int maxPageSize = 100;
    int defaultPageSize = 20;
|};

# CORS configuration for the management HTTP service.
#
# + allowOrigins - Allowed origins
# + allowMethods - Allowed HTTP methods
# + allowHeaders - Allowed request headers
public type CorsConfig record {|
    string[] allowOrigins = ["*"];
    string[] allowMethods = ["GET", "POST", "PUT", "DELETE", "OPTIONS"];
    string[] allowHeaders = ["Content-Type", "x-user-id", "x-user-roles", "Authorization"];
|};

// ================================================================================
// WORKFLOW INSTANCE TYPES
// ================================================================================

# Summary of a workflow instance for list views.
#
# + workflowId - Unique workflow instance ID
# + runId - Temporal run ID for this execution
# + workflowType - Registered workflow type name
# + status - Execution status: RUNNING | COMPLETED | FAILED | CANCELED | TERMINATED | TIMED_OUT
# + startTime - ISO-8601 timestamp when the workflow started
# + closeTime - ISO-8601 timestamp when it ended, or `()` if still running
# + input - Workflow input as JSON, or `()` if not available
public type WorkflowInstanceSummary record {|
    string workflowId;
    string runId;
    string workflowType;
    string status;
    string startTime;
    string? closeTime;
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

# Paginated list of retry task summaries.
#
# + items - Retry task summaries for this page
# + nextPageToken - Opaque continuation token, or `()` on the last page
# + hasMore - True when more pages follow
public type RetryTaskPage record {|
    RetryTaskSummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};
