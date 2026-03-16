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
# This is a client object that provides access to workflow operations.
#
# This client provides:
# - Activity execution via `callActivity` remote method
# - Workflow timing via `sleep` and `currentTime` methods
# - Workflow state queries (replaying status, workflow ID, workflow type)
#
# Use `check ctx->callActivity(myActivity, {"arg1": val1, "arg2": val2})` to execute activities.
# Use Ballerina's `wait` action with event futures for signal handling.
public client class Context {
    private handle nativeContext;

    # Initialize the context with native workflow context handle.
    #
    # + nativeContext - Native context handle from the workflow engine
    public isolated function init(handle nativeContext) {
        self.nativeContext = nativeContext;
    }

    # Executes an activity function within the workflow context.
    # 
    # Activities are non-deterministic operations (I/O, database calls, external APIs)
    # that are executed exactly once during workflow execution. Even if the workflow
    # replays, a completed activity is not re-executed.
    #
    # The return type is inferred from the calling context, so the result is
    # automatically converted to the expected type without manual casting.
    #
    # By default, if the activity function returns an error, it is treated as a failure
    # and the engine will retry the activity based on the retry policy. To treat errors
    # as normal completion values, set `failOnError` to `false` in the options.
    #
    # Example:
    # ```ballerina
    # // Basic activity call (errors cause failure + retry by default)
    # string result = check ctx->callActivity(sendEmailActivity, {email: recipientEmail, subject: subject});
    #
    # // With custom retry policy
    # string result = check ctx->callActivity(sendEmailActivity, {email: recipientEmail}, 
    #     options = {retryPolicy: {maximumAttempts: 3, initialIntervalInSeconds: 2}});
    #
    # // Treat errors as normal completion (no retry on error)
    # string|error result = ctx->callActivity(riskyActivity, {data: input}, options = {failOnError: false});
    # ```
    #
    # + activityFunction - The activity function to execute (must be annotated with @Activity)
    # + args - Map containing the arguments to pass to the activity function
    # + options - Optional activity options for retry policy and error handling behavior
    # + T - The expected return type (inferred from context or explicitly specified)
    # + return - The result of the activity execution cast to type T, or an error if execution fails
    remote isolated function callActivity(function activityFunction, map<anydata> args = {},
            typedesc<anydata> T = <>, *ActivityOptions options) 
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "callActivity"
    } external;

    # Performs a durable sleep within the workflow.
    #
    # This sleep is **persisted by the workflow engine** and will survive program
    # restarts and workflow replays. The countdown continues even when the
    # program is down, and the workflow resumes correctly after the duration has
    # elapsed upon replay.
    #
    # **Do not** use runtime:sleep() inside workflows as it is not deterministic
    # across replays.
    #
    # Example:
    # ```ballerina
    # @workflow:Workflow
    # function reminderProcess(workflow:Context ctx, string userId) returns error? {
    #     // Wait 24 hours (durable - survives restarts)
    #     check ctx.sleep({hours: 24});
    # }
    # ```
    #
    # + duration - The duration to sleep
    # + return - An error if the sleep fails, otherwise nil
    public isolated function sleep(time:Duration duration) returns error? {
        decimal totalSeconds = <decimal>duration.hours * 3600 +
                               <decimal>duration.minutes * 60 +
                               duration.seconds;
        int millis = <int>(totalSeconds * 1000);
        return sleepContextNative(self.nativeContext, millis);
    }

    # Returns the current workflow time as a `time:Utc` value.
    #
    # The workflow engine does **not** use the real wall-clock time for workflow
    # executions. Instead it records the timestamp at each workflow task and
    # surfaces that as "now" during both the original execution *and* every
    # subsequent replay. This guarantees that calls to this method from the
    # same point in the workflow always return the **same** value, making the
    # workflow deterministic regardless of when the program processes it.
    #
    # Example:
    # ```ballerina
    # @workflow:Workflow
    # function orderProcess(workflow:Context ctx, Order input) returns OrderResult|error {
    #     time:Utc startTime = ctx.currentTime();
    #     // ...
    # }
    # ```
    #
    # + return - The current workflow time as `time:Utc`
    public isolated function currentTime() returns time:Utc {
        int millis = currentTimeMillisContextNative(self.nativeContext);
        int seconds = millis / 1000;
        decimal fraction = <decimal>(millis % 1000) / 1000d;
        return [seconds, fraction];
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

isolated function sleepContextNative(handle contextHandle, int millis) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "sleepMillis"
} external;

isolated function currentTimeMillisContextNative(handle contextHandle) returns int = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "currentTimeMillis"
} external;

isolated function isReplayingNative(handle contextHandle) returns boolean = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "isReplaying"
} external;

isolated function getWorkflowIdNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "getWorkflowId"
} external;

isolated function getWorkflowTypeNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "getWorkflowType"
} external;
