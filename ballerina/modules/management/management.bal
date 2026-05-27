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

// Captures this submodule's reference so native code can create records in this module.
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
