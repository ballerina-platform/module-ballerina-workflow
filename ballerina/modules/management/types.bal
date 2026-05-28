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
public type WorkflowDefinition record {|
    string workflowType;
    string? inputSchema;
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
# + taskName - The task type name (the `taskName` field from `HumanTaskConfig`)
# + taskIds - Child workflow IDs of pending instances of this task type,
#             in the order they were started
public type HumanTaskGroup record {|
    string taskName;
    string[] taskIds;
|};

# Summary of a human task instance for list views.
#
# + taskId - Child workflow ID of this task instance (`humantask-{parentId}-{taskName}-{uuid}`)
# + taskName - Task type name (the `taskName` from `HumanTaskConfig`)
# + parentWorkflowId - Workflow ID of the parent that created this task
# + status - Current status: PENDING | COMPLETED | TIMED_OUT | CANCELED | TERMINATED
# + startTime - ISO-8601 timestamp when the task was created
# + closeTime - ISO-8601 timestamp when the task ended, or `()` if still pending
public type HumanTaskSummary record {|
    string taskId;
    string taskName;
    string parentWorkflowId;
    string status;
    string startTime;
    string? closeTime;
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
public type HumanTaskInfo record {|
    string taskId;
    string taskName;
    string parentWorkflowId;
    string status;
    string startTime;
    string? closeTime;
    string title;
    string description;
    string[] userRoles;
    map<json>? payload;
    string createdAt;
    string? formSchema;
|};
