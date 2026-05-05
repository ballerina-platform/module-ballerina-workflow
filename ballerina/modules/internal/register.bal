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

# Registers a workflow function with the program runtime.
#
# This is an **internal** function used by the compiler plugin to register
# workflows at module initialization time. It is not intended to be
# called directly by users.
#
# + workflowFunction - The workflow function to register (must be annotated with @workflow:Workflow)
# + workflowName - The unique name to register the workflow under
# + activities - Optional map of activity function pointers used by the workflow
# + return - `true` if registration was successful, or an error if registration fails
public isolated function registerWorkflow(function workflowFunction, string workflowName, map<function>? activities = ()) returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "registerWorkflow"
} external;

# Starts the workflow runtime after all workflows have been registered.
#
# This is an **internal** function used by the compiler plugin as the last
# generated module-level statement, ensuring that polling only begins after
# every `registerWorkflow` call has executed.
#
# + return - `true` if the worker started successfully, or an error if starting fails
public isolated function startWorkflowRuntime() returns boolean|error {
    check startWorkflowRuntimeNative();
    return true;
}

# Native call to start the singleton worker.
# + return - An error if starting fails, otherwise nil
isolated function startWorkflowRuntimeNative() returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "startSingletonWorker"
} external;

# Registers a module-level `final` client object so that it can be passed as
# an argument to activity functions whose parameter type is a client object.
#
# This is an **internal** function used by the compiler plugin. It is emitted
# by the source modifier for every module-level `final` variable whose type is a
# `client object` and is invoked during module initialization, before
# `startWorkflowRuntime`.
#
# When an activity is called with such a client as one of its arguments, the
# native runtime substitutes the value with the marker string
# `"connection:<name>"` for transport, then resolves it back to the registered
# client on the activity worker side using the same name.
#
# + name - The Ballerina variable name of the client (used as the lookup key)
# + connection - The client object reference to register
# + return - `true` on success. Re-registering the same client object under the
#            same name is idempotent and also returns `true`; an error is
#            returned only if a different client is already registered there.
public isolated function registerConnection(string name, object {} connection)
        returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "registerConnection"
} external;
