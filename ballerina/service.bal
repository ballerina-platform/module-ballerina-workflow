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

import workflow.internal;

# Workflow Event Listener.
#
# An event listener to manage workflow lifecycle and interactions with the workflow engine.
# Handles workflow registration, attachment, detachment, and provides access to the workflow client.
public isolated class WorkflowEventListener {

    final PersistentProvider persistentProvider;

    # Initializes the workflow event listener.
    #
    # + persistentProvider - The persistent provider for workflow management
    public isolated function init(PersistentProvider persistentProvider) {
        self.persistentProvider = persistentProvider;
    }

    # Attaches a workflow service to the listener.
    #
    # + svc - The workflow service to attach
    # + workflowName - The name of the workflow
    # + return - An error if attachment fails
    public isolated function attach(WorkflowModel svc, string workflowName) returns error? {

        WorkflowMethods details = check getServiceModel(svc);
        final WorkflowModelData data = {
            workflowName: workflowName,
            execute: details.execute,
            signals: details.signals,
            queries: details.queries,
            // Assume we have desugared the workflow activities at compile time.
            activities: (typeof svc).@internal:__WorkflowActivities ?: {}
        };
        check self.persistentProvider.registerWorkflowModel(svc, data);
    }

    # Detaches a workflow service from the listener.
    #
    # + svc - The workflow service to detach
    # + return - An error if detachment fails
    public isolated function detach(WorkflowModel svc) returns error? {
        check self.persistentProvider.unregisterWorkflowModel(svc);
    }

    # Starts the workflow listener.
    #
    # + return - An error if the start operation fails
    public isolated function 'start() returns error? {
        check self.persistentProvider.'start();
    }

    # Performs a graceful stop of the workflow listener.
    #
    # + return - An error if the stop operation fails
    public isolated function gracefulStop() returns error? {
        check self.persistentProvider.stop();
    }

    # Performs an immediate stop of the workflow listener.
    #
    # + return - An error if the stop operation fails
    public isolated function immediateStop() returns error? {
        check self.persistentProvider.stop();
    }

    # Gets the workflow engine client.
    #
    # + return - The workflow engine client or an error if the operation fails
    public isolated function getClient() returns WorkflowEngineClient|error {
        return check self.persistentProvider.getClient();
    }
}

# Workflow Service Model.
#
# A distinct service object type that represents a workflow service definition.
# Workflow services must implement the execute method and can optionally define signal and query methods.
public type WorkflowModel distinct service object {};
