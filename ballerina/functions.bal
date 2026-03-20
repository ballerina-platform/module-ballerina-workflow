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


# Runs a new workflow instance.
#
# Creates a new instance of the specified workflow and begins execution.
# Returns a unique workflow ID that can be used to track, query, or send signals
# to the running workflow.
#
# + processFunction - The process function to execute (must be annotated with @Workflow)
# + input - Optional workflow input data. If nil, the workflow is created with no input.
# + return - The unique workflow ID as a string, or an error if the workflow fails to start
public isolated function run(function processFunction, map<anydata>? input = ()) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "run"
} external;

# Sends data to a running workflow process.
#
# Data can be sent to running workflows to trigger state changes.
# The workflow can wait for and react to this data using future-based event handling.
#
# + workflow - The workflow function that identifies the workflow type (must be annotated with @Workflow)
# + workflowId - The unique workflow ID to send the data to (obtained from `run`)
# + dataName - The name identifying the data. Must match a field name in the workflow's
#              events record parameter.
# + data - The data to send to the workflow
# + return - An error if sending fails, otherwise nil
public isolated function sendData(function workflow, string workflowId, string dataName, anydata data) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

// Internal functions

# Starts the workflow runtime after all workflows have been registered.
# This must be called after all registerWorkflow calls are complete.
# The workflow runtime will begin polling for workflow and activity tasks.
#
# + return - An error if starting fails, otherwise nil
isolated function startWorkflowRuntime() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "startSingletonWorker"
} external;

# Stops the workflow runtime gracefully.
# Any in-progress workflows will be allowed to complete their current tasks.
#
# + return - An error if stopping fails, otherwise nil
isolated function stopWorkflowRuntime() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorker"
} external;

# Stops the workflow runtime immediately (forceful shutdown).
# In-flight workflow and activity tasks are interrupted rather than drained.
# Waits for threads to exit before returning.
#
# + return - An error if stopping fails, otherwise nil
isolated function stopWorkflowRuntimeNow() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorkerNow"
} external;

# Returns information about all registered workflows and their activities.
#
# This function is useful for testing and runtime introspection to verify
# that workflows have been properly registered with their activities.
#
# + return - A map of workflow names to their registration information, or an error
isolated function getRegisteredWorkflows() returns WorkflowRegistry|error {
    return getRegisteredWorkflowsNative();
}

# Native implementation to get registered workflows.
# + return - A map of workflow names to their registration information, or an error
isolated function getRegisteredWorkflowsNative() returns WorkflowRegistry|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "getRegisteredWorkflows"
} external;

# Gets the execution result of a workflow.
# This function waits for the workflow to complete and returns its result.
# Used for testing to verify workflow execution outcomes.
#
# + workflowId - The ID of the workflow to get the result for
# + timeoutSeconds - Maximum time to wait for the workflow to complete
# + return - The workflow execution info including result, or an error
public isolated function getWorkflowResult(string workflowId, int timeoutSeconds = 30) returns WorkflowExecutionInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Gets information about a workflow execution without waiting for completion.
# Returns the current state including any activity invocations.
# Used for testing to inspect workflow state during execution.
#
# + workflowId - The ID of the workflow to get info for
# + return - The workflow execution info, or an error
public isolated function getWorkflowInfo(string workflowId) returns WorkflowExecutionInfo|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative"
} external;
