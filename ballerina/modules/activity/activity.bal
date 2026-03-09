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
import ballerina/workflow;

# Performs a durable sleep within a workflow.
#
# This sleep is **persisted by the workflow engine** and will survive program
# restarts and workflow replays. The countdown continues even when the
# program is down, and the workflow resumes correctly after the duration has
# elapsed upon replay.
#
# Use this inside `@workflow:Workflow` functions whenever you need a delay.
# **Do not** use `runtime:sleep()` inside workflows as it is not deterministic
# across replays.
#
# # Example
# ```ballerina
# import ballerina/workflow.activity;
#
# @workflow:Workflow
# function reminderProcess(workflow:Context ctx, string userId) returns error? {
#     // Wait 24 hours (durable - survives restarts)
#     check activity:sleep({hours: 24});
# }
# ```
#
# + duration - The duration to sleep
# + return - An error if the sleep fails, otherwise nil
@workflow:Activity
public isolated function sleep(time:Duration duration) returns error? {
    decimal totalSeconds = <decimal>duration.hours * 3600 +
                           <decimal>duration.minutes * 60 +
                           duration.seconds;
    int millis = <int>(totalSeconds * 1000);
    return sleepNative(millis);
}

# Returns the current workflow time as a `time:Utc` value.
#
# The workflow engine does **not** use the real wall-clock time for workflow
# executions. Instead it records the timestamp at each workflow task and
# surfaces that as "now" during both the original execution *and* every
# subsequent replay. This guarantees that calls to `currentTime()` from the
# same point in the workflow always return the **same** value, making the
# workflow deterministic regardless of when the program processes it.
#
# This is fundamentally different from `time:utcNow()` (from `ballerina/time`),
# which calls the OS clock and returns a different value on every invocation.
# Using `time:utcNow()` directly inside a `@workflow:Workflow` function will
# produce **non-deterministic** behaviour and will cause errors during workflow
# replay. Always use `activity:currentTime()` inside workflow functions when
# you need the current time.
#
# # Example
# ```ballerina
# import ballerina/workflow.activity;
#
# @workflow:Workflow
# function orderProcess(workflow:Context ctx, Order input) returns OrderResult|error {
#     time:Utc startTime = activity:currentTime();
#     // ...
# }
# ```
#
# + return - The current workflow time as `time:Utc`
@workflow:Activity
public isolated function currentTime() returns time:Utc {
    int millis = currentTimeMillisNative();
    int seconds = millis / 1000;
    decimal fraction = <decimal>(millis % 1000) / 1000d;
    return [seconds, fraction];
}

// Native function declarations

isolated function sleepNative(int millis) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.activity.WorkflowActivityNative",
    name: "sleepMillis"
} external;

isolated function currentTimeMillisNative() returns int = @java:Method {
    'class: "io.ballerina.stdlib.workflow.activity.WorkflowActivityNative",
    name: "currentTimeMillis"
} external;
