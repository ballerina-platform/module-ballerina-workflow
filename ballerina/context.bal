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

# Workflow execution context providing activity execution, durable sleep,
# deterministic time, and multi-future await APIs.
public client class Context {
    private handle nativeContext;

    # + nativeContext - Native context handle from the workflow engine
    public isolated function init(handle nativeContext) {
        self.nativeContext = nativeContext;
    }

    # Executes an activity function. The activity runs exactly once, even if the process crashes and restarts.
    #
    # ```ballerina
    # PaymentResult result = check ctx->callActivity(processPayment, args = {"orderId": orderId});
    # ```
    #
    # + activityFunction - The activity function (must have `@Activity`)
    # + args - Arguments to pass to the activity. Values are normally `anydata`.
    #          Module-level `final` `client object` variables may also be passed for
    #          activity parameters whose declared type is a client object; the
    #          compiler plugin validates the call site and substitutes a
    #          `"connection:<name>"` marker for transport across the workflow
    #          execution boundary.
    # + T - Expected return type (inferred from context)
    # + options - Retry and error-handling options
    # + return - The activity result as `T`, or an error
    remote isolated function callActivity(function activityFunction,
            map<anydata|object {}> args = {},
            typedesc<anydata> T = <>, *ActivityOptions options) 
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "callActivity"
    } external;

    # Durable sleep that survives process crashes and restarts. Do not use `runtime:sleep()` in workflows.
    #
    # ```ballerina
    # check ctx.sleep({seconds: 30});
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

    # Returns the deterministic workflow time. Use instead of `time:utcNow()` inside workflows.
    #
    # ```ballerina
    # time:Utc now = ctx.currentTime();
    # ```
    #
    # + return - The current workflow time as `time:Utc`
    public isolated function currentTime() returns time:Utc {
        int millis = currentTimeMillisContextNative(self.nativeContext);
        int seconds = millis / 1000;
        decimal fraction = <decimal>(millis % 1000) / 1000d;
        return [seconds, fraction];
    }

    # Checks whether the workflow is recovering from a failure (re-executing recorded history).
    #
    # + return - `true` if recovering, `false` on first execution
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

    # Waits for at least `minCount` data futures to complete. Results are positional tuples
    # aligned to input order. Use nullable types (`T?`) for partial waits.
    #
    # ```ballerina
    # // Wait for all
    # [Approval, Payment] result = check ctx->await([events.approval, events.payment]);
    # // Wait for any (1 of 2)
    # [Approval?, Payment?] result = check ctx->await([events.approval, events.payment], minCount = 1);
    # ```
    #
    # + futures - Data futures from the workflow's events record
    # + minCount - Minimum completions required (default: all)
    # + timeout - Maximum wait duration; returns error on timeout
    # + T - Expected return type (inferred from context)
    # + return - Positional tuple of values (or nil for incomplete), or an error
    remote isolated function await(future<anydata>[] futures,
            int:Unsigned32 minCount = <int:Unsigned32>futures.length(),
            time:Duration? timeout = (),
            typedesc<anydata> T = <>) returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WaitUtils",
        name: "awaitFutures"
    } external;
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
