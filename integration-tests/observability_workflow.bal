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
// OBSERVABILITY WORKFLOW
// ================================================================================
// Fixtures for tests/observability_test.bal: one run emits start/close, activity, data-event and span
// telemetry; a failing flow covers failure outcomes.

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/workflow;

# Input for the observability workflow.
#
# + name - The name to echo through the activity
type ObservabilityInput record {|
    string name;
|};

@workflow:Activity
function observabilityEcho(string name) returns string {
    return "obs:" + name;
}

@workflow:Workflow
function observabilityFlow(workflow:Context ctx, ObservabilityInput input, ObservabilityEvents events)
        returns string|error {
    string echoed = check ctx->callActivity(observabilityEcho, {name: input.name});
    boolean approved = check wait events.obsApproval;
    return approved ? echoed : "rejected";
}

type ObservabilityEvents record {|
    future<boolean> obsApproval;
|};

@workflow:Workflow
function observabilityFailingFlow(workflow:Context ctx) returns error? {
    return error("observability failure scenario");
}

# The decision a person submits on the observability approval task.
#
# + approved - Whether the request was approved
type ObsDecision record {|
    boolean approved;
|};

@workflow:Activity
function obsRecoverableStep(string mode) returns string|error {
    if mode == "fail" {
        return error("observability step failed on purpose");
    }
    return "obs:recovered:" + mode;
}

# Pauses on a human task, so a decision on that task — and its telemetry — can be observed.
@workflow:Workflow
function observabilityApprovalFlow(workflow:Context ctx, ObservabilityInput input) returns string|error {
    ObsDecision decision = check ctx->awaitHumanTask("obsApprove", {name: input.name},
            userRoles = "OBS_APPROVER", title = "Observe this approval");
    return decision.approved ? "obs:approved" : "obs:declined";
}

# Fails its one step so a reviewer's decision on it — and its telemetry — can be observed.
@workflow:Workflow
function observabilityReviewFlow(workflow:Context ctx, ObservabilityInput input) returns string|error {
    string recovered = check ctx->callActivity(obsRecoverableStep, {mode: input.name},
            retryPolicy = {userRoles: "OBS_REVIEWER"});
    return recovered;
}

# Lookup tool the observability agent calls; runs as a durable activity.
# + item - Item to look up
# + return - Availability text
@workflow:Activity
function obsAgentLookup(string item) returns string {
    return item + " is available";
}

// Scripted: a sleep, an event wait that times out, an activity tool, a human task, then the answer;
// the next step is chosen by how many tool results the conversation already holds.
isolated client class ObsAgentMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        int toolResults = 0;
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage {
                    toolResults += 1;
                }
            }
        }
        if toolResults == 0 {
            return {role: ai:ASSISTANT, toolCalls: [{name: "sleep", arguments: {"seconds": 1}, id: "obs-1"}]};
        }
        if toolResults == 1 {
            return {role: ai:ASSISTANT, toolCalls: [{name: "awaitEvent_obsGreenLight", arguments: {}, id: "obs-2"}]};
        }
        if toolResults == 2 {
            return {role: ai:ASSISTANT, toolCalls: [{name: "obsAgentLookup", arguments: {"item": "laptop"}, id: "obs-3"}]};
        }
        if toolResults == 3 {
            return {role: ai:ASSISTANT, toolCalls: [{name: "obsSignoff", arguments: {"summary": "laptop"}, id: "obs-4"}]};
        }
        return {role: ai:ASSISTANT, content: "obs agent done"};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ObsAgentMockModelProvider obsAgentMockModel = new;

# Walks every kind of agent step, so each step's telemetry can be observed in one run.
final workflow:DurableAgent observabilityAgent = check new ({
    systemPrompt: {role: "", instructions: "Exercise every step once."},
    model: obsAgentMockModel,
    activities: [obsAgentLookup],
    events: {
        obsGreenLight: {request: string, response: string, cardinality: workflow:SINGLE_EVENT}
    },
    humanTasks: {
        obsSignoff: {userRoles: "OBS_APPROVER", title: "Sign off the observability agent"}
    },
    eventTimeout: {seconds: 2}
});
