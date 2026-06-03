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
