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


# Creates a new workflow process instance with the given input.
#
# Creates a new instance of the specified workflow process and begins execution.
# The workflow ID is extracted from the `id` field in the input data.
# Returns a unique workflow ID that can be used to track, query, or send events
# to the running workflow.
#
# + processFunction - The process function to execute (must be annotated with @Process)
# + input - The workflow input data (must contain "id" field for correlation)
# + return - The unique workflow ID as a string, or an error if the process fails to start
public isolated function createInstance(function processFunction, map<anydata> input) returns string|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative",
    name: "createInstance"
} external;

# Sends an event (signal) to a running workflow process.
#
# Events can be used to communicate with running workflows and trigger state changes.
# The workflow can wait for and react to these events using workflow primitives.
# The `id` field in the event data is used to identify the target workflow instance.
#
# + processFunction - The process function that identifies the workflow type
# + eventData - The signal data (must contain "id" field for workflow correlation)
# + signalName - Optional name of the signal. This should match a field name in the 
#                workflow's events record parameter. If not provided, defaults to process name.
# + return - `true` if the event was sent successfully, or an error if sending fails
public isolated function sendEvent(function processFunction, map<anydata> eventData, string? signalName = ()) returns boolean|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Registers a workflow process function with the singleton worker.
#
# Makes the process available for execution when `createInstance` is called.
# The process is registered with the singleton worker that was created at
# module initialization time. This function should be called during
# application initialization to register all workflow processes.
#
# + processFunction - The process function to register (must be annotated with @Process)
# + processName - The unique name to register the process under
# + activities - Optional map of activity function pointers used by the process
# + return - `true` if registration was successful, or an error if registration fails
public isolated function registerProcess(function processFunction, string processName, map<function>? activities = ()) returns boolean|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "registerProcessWithWorker"
} external;


// Internal functions

# Starts the singleton worker after all processes have been registered.
# This must be called after all registerProcess calls are complete.
# The worker will begin polling for workflow and activity tasks.
#
# + return - An error if starting fails, otherwise nil
isolated function startWorker() returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "startSingletonWorker"
} external;

# Stops the singleton worker gracefully.
# Any in-progress workflows will be allowed to complete their current tasks.
#
# + return - An error if stopping fails, otherwise nil
isolated function stopWorker() returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "stopSingletonWorker"
} external;

# Returns information about all registered workflow processes and their activities.
#
# This function is useful for testing and runtime introspection to verify
# that workflow processes have been properly registered with their activities.
#
# + return - A map of process names to their registration information, or an error
isolated function getRegisteredWorkflows() returns WorkflowRegistry|error {
    return getRegisteredWorkflowsNative();
}

# Native implementation to get registered workflows.
# + return - A map of process names to their registration information, or an error
isolated function getRegisteredWorkflowsNative() returns WorkflowRegistry|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative",
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
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Gets information about a workflow execution without waiting for completion.
# Returns the current state including any activity invocations.
# Used for testing to inspect workflow state during execution.
#
# + workflowId - The ID of the workflow to get info for
# + return - The workflow execution info, or an error
public isolated function getWorkflowInfo(string workflowId) returns WorkflowExecutionInfo|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;
