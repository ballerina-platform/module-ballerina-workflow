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

# Supported workflow providers.
public enum Provider {
    TEMPORAL
}

# Temporal-specific configuration parameters.
#
# + taskQueue - The task queue for workflow execution (default: "BALLERINA_WORKFLOW_TASK_QUEUE")
# + maxConcurrentWorkflows - Maximum number of concurrent workflow executions (default: 100)
# + maxConcurrentActivities - Maximum number of concurrent activity executions (default: 100)
# + authentication - Optional authentication configuration
public type TemporalParams record {|
    string taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";
    int maxConcurrentWorkflows = 100;
    int maxConcurrentActivities = 100;
    AuthConfig? authentication = ();
|};

# Authentication configuration for workflow provider.
#
# + apiKey - Optional API key for authentication
# + mtlsCert - Optional mTLS certificate path
# + mtlsKey - Optional mTLS private key path
public type AuthConfig record {|
    string? apiKey = ();
    string? mtlsCert = ();
    string? mtlsKey = ();
|};

# Workflow module configuration.
# This is a generic configuration that can support multiple providers.
#
# + provider - The workflow provider to use (currently only TEMPORAL is supported)
# + url - URL of the workflow server (default: "localhost:7233")
# + namespace - Workflow namespace (default: "default")
# + params - Provider-specific parameters
public type WorkflowConfig record {|
    Provider provider = TEMPORAL;
    string url = "localhost:7233";
    string namespace = "default";
    TemporalParams params = {};
|};

# Information about a registered workflow process.
#
# + name - The name of the registered process
# + activities - Array of activity names associated with this process
# + events - Array of event names (signals) this process can receive
type ProcessRegistration record {
    string name;
    string[] activities;
    string[] events;
};

# Information about all registered workflows.
# This is a map where keys are process names and values are their registration info.
type WorkflowRegistry map<ProcessRegistration>;

# Base input data type for workflow and signal data.
# All workflow inputs and signal data must include a mandatory "id" field
# which is used internally by the workflow engine for correlation.
# This is a type alias for documentation purposes - use `map<anydata>` with "id" field.
#
# Expected structure:
# ```
# {
#     id: "unique-identifier",
#     ... // other fields
# }
# ```
#
# + id - Unique identifier for the workflow instance or signal (used for correlation)
public type InputData record {|
    string id;
    anydata...;
|};

# Workflow input data type alias.
# Used when starting a workflow. The "id" field becomes the workflow ID in Temporal.
public type WorkflowData InputData;

# Signal input data type alias.
# Used when sending signals. The "id" field identifies the target workflow instance.
public type SignalData InputData;

# Data type with correlation keys for workflow-signal matching.
#
# When using correlation keys, define your input and signal types with `readonly` fields.
# The readonly fields become correlation keys that the workflow engine uses to:
# 1. Generate a composite workflow ID (e.g., "processName-customerId=C123-orderId=O456")
# 2. Create Temporal Search Attributes for workflow discovery
# 3. Validate signal data has matching correlation keys
#
# Example with readonly correlation keys:
# ```ballerina
# # Workflow input with correlation keys
# type OrderInput record {|
#     readonly string customerId;  // Correlation key
#     readonly string orderId;     // Correlation key
#     string product;              // Regular field
#     int quantity;                // Regular field
# |};
#
# # Signal data MUST have same readonly fields (name and type)
# type PaymentSignal record {|
#     readonly string customerId;  // Must match OrderInput.customerId
#     readonly string orderId;     // Must match OrderInput.orderId
#     decimal amount;              // Signal-specific data
#     string paymentMethod;
# |};
#
# @workflow:Process
# function orderProcess(workflow:Context ctx, OrderInput input, 
#                       record {| future<PaymentSignal> payment; |} events) 
#                       returns OrderResult|error {
#     PaymentSignal payment = check events.payment;  // Wait for payment signal
#     // ...
# }
# ```
#
# The compiler plugin validates that:
# - Signal types have the same readonly fields (name AND type) as the input type
# - If no readonly fields exist, falls back to requiring an "id" field
#
# At runtime, sending a signal like:
# ```ballerina
# workflow:sendEvent("payment", {customerId: "C123", orderId: "O456", amount: 99.99});
# ```
# Will automatically find the workflow with matching correlation keys.
public type CorrelatedData record {|
    anydata...;
|};

# Type constraint for process input with correlation keys.
# Use this as a type constraint when you want to enforce correlation key pattern.
#
# Example:
# ```ballerina
# type MyInput record {|
#     *workflow:CorrelatedInput;  // Inherit readonly id
#     string data;
# |};
# ```
#
# + id - The correlation identifier (readonly for type safety)
public type CorrelatedInput record {|
    readonly string id;
    anydata...;
|};

# Information about an activity invocation (for testing/introspection).
# + activityName - The name of the activity that was invoked
# + input - The arguments passed to the activity
# + output - The result returned by the activity (nil if not yet completed or failed)
# + status - The status of the activity execution ("COMPLETED", "FAILED", "RUNNING", "PENDING")
# + errorMessage - Error message if the activity failed
public type ActivityInvocation record {
    string activityName;
    anydata[] input;
    anydata? output;
    string status;
    string? errorMessage;
};

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

# Error type alias for duplicate workflow errors.
# When a workflow with the same correlation keys already exists, an error is thrown
# with "DuplicateWorkflowError" in the message. Check error message for details.
public type DuplicateWorkflowError error;
