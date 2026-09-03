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
// BRANCH TRACING WORKFLOW
// ================================================================================
//
// One activity, called from both arms of an `if`. The two calls are indistinguishable in
// history by name — which is the whole problem. The compiler stamps each with its call site
// (`recordDecision#1` / `recordDecision#2`), the runtime carries it into the activity's
// invocation, and the activity tree reports it, so a viewer can tell which arm ran.
//
// ================================================================================

import ballerina/workflow;

# Input for the branch tracing workflow.
#
# + id - The workflow identifier
# + approved - Which arm to take
type BranchInput record {|
    string id;
    boolean approved;
|};

# Records a decision. Called from both arms, so its name alone says nothing about the path.
#
# + id - The claim identifier
# + outcome - What was decided
# + return - The recorded line, or an error
@workflow:Activity
function recordDecision(string id, string outcome) returns string|error {
    return id + ":" + outcome;
}

# Names its step instead of taking the generated ordinal, so the id survives edits that would
# renumber it.
#
# + ctx - Workflow context
# + input - The claim to record
# + return - The recorded line, or an error
@workflow:Workflow
function namedStepWorkflow(workflow:Context ctx, BranchInput input) returns string|error {
    string recorded = check ctx->callActivity(recordDecision,
            {"id": input.id, "outcome": "named"}, stepId = "record-outcome");
    return recorded;
}

# Starts a child workflow with a chosen step id, so the memo carrier is exercised. Activities carry
# their id in the call config and sleeps in the timer summary; children, human tasks and review tasks
# all carry theirs in the child's **memo**, and that path had no test until a mismatched key was found
# by hand — the writer used a constant, the reader a literal, and they drifted.
#
# + ctx - Workflow context
# + input - The claim to record
# + return - The child's recorded line, or an error
@workflow:Workflow
function childStepWorkflow(workflow:Context ctx, BranchInput input) returns string|error {
    string child = check ctx->runChildWorkflow(namedStepWorkflow, input, stepId = "spawn-audit");
    string recorded = check ctx->waitForChildWorkflow(child);
    return recorded;
}

# Takes one of two arms, calling the same activity from each.
#
# + ctx - Workflow context
# + input - Which arm to take
# + return - The recorded line, or an error
@workflow:Workflow
function branchTraceWorkflow(workflow:Context ctx, BranchInput input) returns string|error {
    if input.approved {
        string recorded = check ctx->callActivity(recordDecision,
                {"id": input.id, "outcome": "approved"});
        return recorded;
    } else {
        string recorded = check ctx->callActivity(recordDecision,
                {"id": input.id, "outcome": "rejected"});
        return recorded;
    }
}
