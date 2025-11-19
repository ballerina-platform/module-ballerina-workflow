// Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

# Workflow Persistent Provider interface.
# 
# Defines the contract for workflow persistence providers that manage workflow lifecycle,
# registration, and execution operations.
public type PersistentProvider distinct isolated object {

    # Registers a workflow model with the provider.
    #
    # + svc - The workflow service object
    # + data - The workflow model data containing workflow definition
    # + return - An error if registration fails
    public isolated function registerWorkflowModel(WorkflowModel svc, WorkflowModelData data) returns error?;

    # Unregisters a workflow model from the provider.
    #
    # + svc - The workflow service object to unregister
    # + return - An error if unregistration fails
    public isolated function unregisterWorkflowModel(WorkflowModel svc) returns error?;

    # Starts the workflow provider.
    #
    # + return - An error if the start operation fails
    public isolated function 'start() returns error?;

    # Stops the workflow provider.
    #
    # + return - An error if the stop operation fails
    public isolated function stop() returns error?;

    # Gets the workflow engine client.
    #
    # + return - The workflow engine client or an error if the operation fails
    public isolated function getClient() returns WorkflowEngineClient|error;

    # Gets the workflow operators for executing workflow operations.
    #
    # + return - The workflow operators or an error if the operation fails
    public isolated function getWorkflowOperators() returns WorkflowOperators|error;
};

# Workflow operators for executing workflow-specific operations.
#
# Provides operations like await and sleep that can be used within workflow execution context.
public type WorkflowOperators distinct isolated client object {

    # Waits for a condition to be true.
    #
    # + conditionFunc - The condition to wait for
    # + return - An error if the operation fails or is not in workflow context
    isolated remote function await(boolean conditionFunc) returns NotInWorkflowError|error?;

    # Sleeps for the specified duration.
    #
    # + duration - The duration to sleep
    # + return - An error if the operation fails or is not in workflow context
    isolated remote function sleep(Duration duration) returns NotInWorkflowError|error?;
};

# A client to interact with the workflow engine.
#
# Provides methods to execute workflows, send signals, perform queries, and call activities.
public type WorkflowEngineClient isolated client object {

    # Executes a workflow.
    #
    # + svc - The workflow service to execute
    # + args - Arguments to pass to the workflow execute method
    # + return - The workflow execution object or an error if execution fails
    isolated remote function execute(WorkflowModel svc, anydata... args) returns Execution|error;

    # Sends a signal to a running workflow.
    #
    # + svc - The workflow service
    # + execution - The workflow execution to signal
    # + signalName - The name of the signal method
    # + args - Arguments to pass to the signal method
    # + return - An error if the signal operation fails
    isolated remote function signal(WorkflowModel svc, Execution execution, string signalName, anydata... args) returns error?;

    # Queries a workflow execution.
    #
    # + svc - The workflow service
    # + execution - The workflow execution to query
    # + queryName - The name of the query method
    # + args - Arguments to pass to the query method
    # + return - The query result or an error if the operation fails
    isolated remote function query(WorkflowModel svc, Execution execution, string queryName, anydata... args) returns anydata|error;

    # Calls an activity function within a workflow.
    #
    # + acticity - The activity function to call
    # + args - Arguments to pass to the activity function
    # + return - The activity result or an error if the operation fails
    isolated remote function callActivity(function acticity, anydata... args) returns anydata|error;
};
