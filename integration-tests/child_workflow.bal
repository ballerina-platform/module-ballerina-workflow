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
// CHILD WORKFLOW COMPOSITION
// ================================================================================
//
// These workflows exercise the child-workflow composition methods on the workflow
// context: ctx->runChildWorkflow (start, no wait), ctx->getChildWorkflowResult
// (non-blocking read / WorkflowBusyError), ctx->waitForChildWorkflow (durable wait),
// ctx->callWorkflow (start + wait fused), and ctx->sendDataToChildWorkflow (signal).
// Children are true Temporal child workflows whose lifecycle is tied to the parent.
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

# Input for a workflow that receives data via sendDataToChildWorkflow from another workflow.
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
function childWorkflow(workflow:Context ctx, ChildInput input) returns string|error {
    return "child-processed:" + input.value;
}

# A slow child workflow used to observe the WorkflowBusyError path: it sleeps long
# enough that a non-blocking result read right after the start finds it still running.
#
# + input - The child workflow input
# + return - The processed result or error
@workflow:Workflow
function slowChildWorkflow(workflow:Context ctx, ChildInput input) returns string|error {
    check ctx.sleep({seconds: 3});
    return "slow-child-processed:" + input.value;
}

# A parent workflow that starts a child workflow with ctx->runChildWorkflow and
# durably waits for its result with ctx->waitForChildWorkflow. The child is a true
# Temporal child workflow tied to this parent's lifecycle.
#
# + ctx - The workflow context
# + input - The parent workflow input
# + return - The result combining parent and child workflow data, or error
@workflow:Workflow
function parentWorkflow(workflow:Context ctx, ParentInput input) returns string|error {
    string childWorkflowId = check ctx->runChildWorkflow(childWorkflow,
        input = {value: "from-parent-" + input.id});

    // Durable wait: suspends the parent (no thread held) until the child completes.
    string childResult = check ctx->waitForChildWorkflow(childWorkflowId);

    // Process the child result through an activity
    string formatted = check ctx->callActivity(formatChildResultActivity,
        {"childResult": childResult});
    return formatted;
}

# A parent workflow that fans out two children with ctx->runChildWorkflow and then
# gathers both results with ctx->waitForChildWorkflow.
#
# + ctx - The workflow context
# + input - The parent workflow input
# + return - The combined child results, or error
@workflow:Workflow
function fanOutParentWorkflow(workflow:Context ctx, ParentInput input) returns string|error {
    string firstChildId = check ctx->runChildWorkflow(childWorkflow,
        input = {value: "first-" + input.id});
    string secondChildId = check ctx->runChildWorkflow(childWorkflow,
        input = {value: "second-" + input.id});

    string firstResult = check ctx->waitForChildWorkflow(firstChildId);
    string secondResult = check ctx->waitForChildWorkflow(secondChildId);
    return firstResult + "|" + secondResult;
}

# A parent workflow that starts a slow child and reads its result non-blockingly:
# the first ctx->getChildWorkflowResult right after the start observes a
# workflow:WorkflowBusyError (the child is still sleeping), after which the parent
# switches to the durable wait.
#
# + ctx - The workflow context
# + input - The parent workflow input
# + return - The child result prefixed with whether the busy state was observed
@workflow:Workflow
function busyCheckParentWorkflow(workflow:Context ctx, ParentInput input) returns string|error {
    string childId = check ctx->runChildWorkflow(slowChildWorkflow,
        input = {value: input.id});

    string|error early = ctx->getChildWorkflowResult(childId);
    string busyObserved = early is workflow:WorkflowBusyError ? "busy" : "not-busy";

    string childResult = check ctx->waitForChildWorkflow(childId);
    return busyObserved + ":" + childResult;
}

# A parent workflow that starts a child and waits for its result in one fused call
# using ctx->callWorkflow.
#
# + ctx - The workflow context
# + input - The parent workflow input
# + return - The child result, or error
@workflow:Workflow
function callWorkflowParentWorkflow(workflow:Context ctx, ParentInput input) returns string|error {
    string childResult = check ctx->callWorkflow(childWorkflow,
        input = {value: "called-" + input.id});
    return childResult;
}

# A receiver workflow that waits for data sent via ctx->sendDataToChildWorkflow.
#
# + input - The receiver workflow input
# + events - The signal futures (notification)
# + return - The received data or error
@workflow:Workflow
function receiverWorkflow(workflow:Context ctx, ReceiverInput input, ReceiverEvents events) returns string|error {
    // Wait for notification signal
    map<anydata> notification = check wait events.notification;
    string message = <string>(notification["message"]);
    return "received:" + message;
}

# A sender workflow that sends data to another workflow instance using
# ctx->sendDataToChildWorkflow — the in-workflow counterpart of workflow:sendData,
# implemented as a deterministic external-workflow signal.
#
# + ctx - The workflow context
# + input - The sender workflow input containing the target workflow ID
# + return - Confirmation message or error
@workflow:Workflow
function senderWorkflow(workflow:Context ctx, SenderInput input) returns string|error {
    check ctx->sendDataToChildWorkflow(input.targetWorkflowId,
        "notification", {"message": "hello-from-sender"});

    return "sent-to:" + input.targetWorkflowId;
}
