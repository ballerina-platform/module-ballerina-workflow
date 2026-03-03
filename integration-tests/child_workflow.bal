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
// CHILD WORKFLOW (implicit activity tests)
// ================================================================================
//
// These workflows test that workflow:run and workflow:sendData work as implicit
// activities when called from inside a @Workflow function. The function pointer
// is resolved to a string name for Temporal serialization.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPES
// ================================================================================

# Input for the child workflow that is spawned from a parent.
#
# + value - A value to process
type ChildInput record {|
    string value;
|};

# Input for the parent workflow that spawns a child workflow.
#
# + id - The parent workflow identifier
type ParentInput record {|
    string id;
|};

# Input for a workflow that receives data via sendData from another workflow.
#
# + id - The workflow identifier
type ReceiverInput record {|
    string id;
|};

# Signal record for the receiver workflow.
#
# + notification - A future that will receive the notification data
type ReceiverEvents record {|
    future<map<anydata>> notification;
|};

# Input for a workflow that sends data to a target workflow.
#
# + targetWorkflowId - The ID of the workflow to send data to
type SenderInput record {|
    string targetWorkflowId;
|};

// ================================================================================
// ACTIVITY DEFINITIONS
// ================================================================================

# Activity that formats a child workflow result.
#
# + childResult - The result from the child workflow
# + return - A formatted string or error
@workflow:Activity
function formatChildResultActivity(string childResult) returns string|error {
    return "Parent received: " + childResult;
}

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# A simple child workflow that processes a value and returns a result.
# This is the target workflow that will be started by the parent workflow.
#
# + input - The child workflow input
# + return - The processed result or error
@workflow:Workflow
function childWorkflow(ChildInput input) returns string|error {
    return "child-processed:" + input.value;
}

# A parent workflow that starts a child workflow using workflow:run()
# from inside the workflow function. This tests the implicit activity behavior
# where workflow:run is automatically routed through a built-in activity
# for determinism.
#
# + ctx - The workflow context
# + input - The parent workflow input
# + return - The result combining parent and child workflow data, or error
@workflow:Workflow
function parentWorkflow(workflow:Context ctx, ParentInput input) returns string|error {
    // Start a child workflow from inside this workflow.
    // This call will be automatically routed through the __workflow_run
    // implicit activity for deterministic execution.
    string childWorkflowId = check workflow:run(childWorkflow, {value: "from-parent-" + input.id});

    // Wait for the child workflow to complete and get its result
    workflow:WorkflowExecutionInfo childResult = check workflow:getWorkflowResult(childWorkflowId, 30);

    if childResult.status == "COMPLETED" {
        string resultStr = <string>childResult.result;
        // Process the child result through an activity
        string formatted = check ctx->callActivity(formatChildResultActivity,
            {"childResult": resultStr});
        return formatted;
    }

    return error("Child workflow failed: " + (childResult.errorMessage ?: "unknown"));
}

# A receiver workflow that waits for data sent via workflow:sendData.
# Used to test the implicit sendData activity from inside another workflow.
#
# + input - The receiver workflow input
# + events - The signal futures (notification)
# + return - The received data or error
@workflow:Workflow
function receiverWorkflow(ReceiverInput input, ReceiverEvents events) returns string|error {
    // Wait for notification signal
    map<anydata> notification = check wait events.notification;
    string message = <string>(notification["message"]);
    return "received:" + message;
}

# A sender workflow that sends data to another workflow using workflow:sendData()
# from inside the workflow function. This tests the implicit activity behavior.
#
# + ctx - The workflow context
# + input - The sender workflow input containing the target workflow ID
# + return - Confirmation message or error
@workflow:Workflow
function senderWorkflow(workflow:Context ctx, SenderInput input) returns string|error {
    // Send data to the receiver workflow from inside this workflow.
    // This call will be automatically routed through the __workflow_sendData
    // implicit activity for deterministic execution.
    check workflow:sendData(receiverWorkflow, input.targetWorkflowId,
        "notification", {"message": "hello-from-sender"});

    return "sent-to:" + input.targetWorkflowId;
}
