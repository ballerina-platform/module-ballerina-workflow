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
import ballerina/time;

# Workflow execution context providing workflow APIs.
# Automatically injected as first parameter of execute() method.
#
# This class provides access to workflow operations such as:
# - Durable sleep operations
# - Workflow state queries (replaying status, workflow ID, workflow type)
#
# Note: Use module-level `callActivity()` for activity execution.
# Use Ballerina's `wait` action with event futures for signal handling.
public class Context {
    private handle nativeContext;

    # Initialize the context with native workflow context handle.
    #
    # + nativeContext - Native context handle from Temporal
    public isolated function init(handle nativeContext) {
        self.nativeContext = nativeContext;
    }

    # Durable sleep that survives workflow restarts.
    #
    # Unlike regular sleep, this is persisted and will continue counting
    # even if the workflow is replayed or the worker restarts.
    #
    # + duration - Duration to sleep
    # + return - Error if sleep fails
    public isolated function sleep(time:Duration duration) returns error? {
        // Convert Duration to milliseconds
        decimal totalSeconds = <decimal>duration.hours * 3600 + 
                               <decimal>duration.minutes * 60 + 
                               duration.seconds;
        int millis = <int>(totalSeconds * 1000);
        return sleepNative(self.nativeContext, millis);
    }

    # Check if the workflow is currently replaying history.
    #
    # Useful for skipping side effects that should only happen on first execution.
    # For example, logging or metrics that shouldn't be duplicated during replay.
    #
    # + return - True if replaying, false if first execution
    public isolated function isReplaying() returns boolean {
        return isReplayingNative(self.nativeContext);
    }

    # Get the unique workflow ID.
    #
    # + return - The workflow ID
    public isolated function getWorkflowId() returns string|error {
        return getWorkflowIdNative(self.nativeContext);
    }

    # Get the workflow type name.
    #
    # + return - The workflow type
    public isolated function getWorkflowType() returns string|error {
        return getWorkflowTypeNative(self.nativeContext);
    }
}

// Native function declarations

isolated function sleepNative(
        handle contextHandle,
        int millis
) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.context.WorkflowContextNative",
    name: "sleepMillis"
} external;

isolated function isReplayingNative(handle contextHandle) returns boolean = @java:Method {
    'class: "io.ballerina.stdlib.workflow.context.WorkflowContextNative",
    name: "isReplaying"
} external;

isolated function getWorkflowIdNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.context.WorkflowContextNative",
    name: "getWorkflowId"
} external;

isolated function getWorkflowTypeNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.context.WorkflowContextNative",
    name: "getWorkflowType"
} external;
