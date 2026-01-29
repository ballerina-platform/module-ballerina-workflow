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

# Executes an activity function within the workflow context.
# 
# Activities are non-deterministic operations (I/O, database calls, external APIs)
# that should only be executed once during workflow execution and not during replay.
# The workflow runtime ensures exactly-once execution semantics for activities.
#
# + activityFunction - The activity function to execute (must be annotated with @Activity)
# + args - Variable arguments to pass to the activity function
# + return - The result of the activity execution, or an error if execution fails
public isolated function callActivity(function activityFunction, anydata... args) returns anydata|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Starts a new workflow process with the given input.
#
# Creates a new instance of the specified workflow process and begins execution.
# Returns a unique workflow ID that can be used to track, query, or send events
# to the running workflow.
#
# + processFunction - The process function to execute (must be annotated with @Process)
# + input - The input data for the workflow process
# + return - The unique workflow ID as a string, or an error if the process fails to start
public isolated function startProcess(function processFunction, anydata input) returns string|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Sends an event (signal) to a running workflow process.
#
# Events can be used to communicate with running workflows and trigger state changes.
# The workflow can wait for and react to these events using workflow primitives.
#
# + processFunction - The process function that identifies the workflow type
# + eventData - The event data to send to the workflow
# + return - `true` if the event was sent successfully, or an error if sending fails
public isolated function sendEvent(function processFunction, anydata eventData) returns boolean|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;

# Registers a workflow process function with the runtime.
#
# Makes the process available for execution when `startProcess` is called.
# This is typically called during application initialization to register
# all available workflow processes.
#
# + processFunction - The process function to register (must be annotated with @Process)
# + processName - The unique name to register the process under
# + return - `true` if registration was successful, or an error if registration fails
public isolated function registerProcess(function processFunction, string processName) returns boolean|error = @java:Method {
    'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.WorkflowNative"
} external;