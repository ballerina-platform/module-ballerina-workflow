// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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
// BUILT-IN CONTEXT WORKFLOWS (sleep, currentTime)
// ================================================================================
//
// Workflows that exercise ctx.sleep() and ctx.currentTime() methods
// on the Context client class to ensure line and method coverage.
//
// ================================================================================

import ballerina/workflow;
import ballerina/time;

// ================================================================================
// TYPES
// ================================================================================

# Input for built-in activity workflows.
#
# + id - The workflow identifier
type BuiltinActivityInput record {|
    string id;
|};

# Result from currentTime workflow.
#
# + seconds - Epoch seconds from workflow time
# + beforeSleep - Epoch seconds before sleep
# + afterSleep - Epoch seconds after sleep
type TimeResult record {|
    int seconds;
    int beforeSleep?;
    int afterSleep?;
|};

// ================================================================================
// WORKFLOW DEFINITIONS
// ================================================================================

# Workflow that calls ctx.currentTime() and returns the result.
# Covers the currentTime() method on Context and its millis-to-Utc conversion.
#
# + ctx - Workflow context for deterministic time access
# + input - Workflow input (unused; required by framework)
# + return - A `TimeResult` with the current epoch seconds, or an error
@workflow:Workflow
function currentTimeWorkflow(workflow:Context ctx, BuiltinActivityInput input) returns TimeResult|error {
    time:Utc now = ctx.currentTime();
    return {seconds: now[0]};
}

# Workflow that calls ctx.sleep() with a short duration.
# Covers the sleep() method on Context and its Duration-to-millis conversion.
#
# + ctx - Workflow context providing deterministic sleep
# + input - Workflow input (unused; required by framework)
# + return - A confirmation string on success, or an error
@workflow:Workflow
function sleepWorkflow(workflow:Context ctx, BuiltinActivityInput input) returns string|error {
    check ctx.sleep({seconds: 1});
    return "slept successfully";
}

# Workflow that calls ctx.currentTime() before and after ctx.sleep() to verify
# that workflow time advances.
#
# + ctx - Workflow context providing deterministic time and sleep
# + input - Workflow input (unused; required by framework)
# + return - A `TimeResult` with epoch seconds before and after sleep, or an error
@workflow:Workflow
function sleepWithTimeWorkflow(workflow:Context ctx, BuiltinActivityInput input) returns TimeResult|error {
    time:Utc before = ctx.currentTime();
    check ctx.sleep({seconds: 2});
    time:Utc after = ctx.currentTime();
    return {
        seconds: after[0],
        beforeSleep: before[0],
        afterSleep: after[0]
    };
}
