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

import ballerina/ai;
import ballerina/jballerina.java;

import workflow.observe;

// ---------------------------------------------------------------------------
// Durable agent (object model) — declaration surface
// ---------------------------------------------------------------------------
// A durable agent is declared once as a module-level `final` object whose
// constructor config carries every capability (activities, tools, events,
// human tasks, peers). The compiler plugin validates the declaration and
// generates the module-init registration; the agent itself runs on the
// existing durable ReAct loop.
// ---------------------------------------------------------------------------

# How a declared event channel consumes its requests.
public enum EventCardinality {
    # The channel is consumed exactly once per run. Declare this only when the
    # channel receives a single event per agent instance — a later event on the
    # channel is never consumed
    SINGLE_EVENT,
    # The channel re-arms after every turn: the agent may wait on it repeatedly,
    # each wait consuming the next queued payload (default — events can originate
    # from multiple senders, which a once-only channel cannot guarantee)
    MULTI_EVENT
}

# One event channel of a durable agent, declared in the mapping form of `events`
# where the mapping key is the channel name — constant by construction, so the
# name needs no separate validation. `request`/`response` capture both sides'
# types and the channel's duplexity: a `response` type declares a duplex
# (request-response) channel whose turn answers are read with
# `getDataResult`/`waitForDataResult`; a nil `response` declares a one-way
# channel — data flows in, no result is read back.
#
# + request - Type of the payload sent to the agent on this channel; `sendData`
#             validates each payload against it
# + response - Type of the agent's reply for this channel; `()` for one-way channels
# + cardinality - Business cardinality of the channel: re-armed per turn
#                 (`MULTI_EVENT`, the default) or consumed once (`SINGLE_EVENT`)
public type EventConfig record {|
    typedesc<anydata> request;
    typedesc<anydata>? response = ();
    EventCardinality cardinality = MULTI_EVENT;
|};

# A named event channel of a durable agent — the array form of `events`, kept for
# existing declarations.
#
# # Deprecated
# Declare channels in the mapping form instead, keyed by name:
# `events: {chat: {request: string, response: string}}`. The mapping key is a
# compile-time constant by construction, which the `name` field has to be
# validated to be.
#
# + name - The channel name (unique across all of the agent's capabilities)
# + request - Type of the payload sent to the agent on this channel; `sendData`
#             validates each payload against it
# + response - Type of the agent's reply for this channel; `()` for one-way channels
# + cardinality - Business cardinality of the channel: re-armed per turn
#                 (`MULTI_EVENT`, the default) or consumed once (`SINGLE_EVENT`)
@deprecated
public type EventDecl record {|
    string name;
    typedesc<anydata> request;
    typedesc<anydata>? response = ();
    EventCardinality cardinality = MULTI_EVENT;
|};

# An activity capability of a durable agent, with optional gating and retry config.
# For the no-config case pass the bare `@workflow:Activity` function instead.
#
# + activity - The `@workflow:Activity` function
# + name - Tool name advertised to the model; defaults to the function name
# + description - Tool description advertised to the model; defaults to the
#                 function's doc comment
# + bindings - Fixed arguments partially applied to the activity (e.g. a
#              `connection`), hidden from the model: only the remaining data
#              parameters appear in the tool's schema. Client objects are bound
#              by referencing their module-level `final` variable
# + requiresApproval - When `true`, a `PRE_RUN` review activity gates every call
# + userRoles - Role(s) permitted to decide reviews of this activity
# + retryPolicy - Retry behaviour on failure, as for `ctx->callActivity`
public type ActivityDecl record {|
    function activity;
    string name?;
    string description?;
    map<anydata|object {}> bindings?;
    boolean requiresApproval = false;
    string|string[] userRoles?;
    AutoRetry|HumanReview|NoAutomaticRetry retryPolicy = NoAutomaticRetry;
|};

# An AI tool capability of a durable agent, with optional gating config. For the
# no-config case pass the `ai:ToolConfig`/`ai:BaseToolKit`/`@ai:AgentTool` function
# directly.
#
# + tool - The tool: an `@ai:AgentTool` function, an `ai:ToolConfig`, or a toolkit
# + requiresApproval - When `true`, a `PRE_RUN` review activity gates every call
# + userRoles - Role(s) permitted to decide reviews of this tool
public type ToolDecl record {|
    ai:BaseToolKit|ai:ToolConfig|ai:FunctionTool tool;
    boolean requiresApproval = false;
    string|string[] userRoles?;
|};

# One human task capability of a durable agent, declared in the mapping form of
# `humanTasks` where the mapping key is the task name.
#
# A `HumanTaskDefinition`: the agent declares both `resultType` and `payloadType`, and the
# payload it supplies when it asks is checked against the latter.
public type HumanTaskConfig HumanTaskDefinition;

# A named human task capability of a durable agent — the array form of
# `humanTasks`, kept for existing declarations.
#
# # Deprecated
# Declare tasks in the mapping form instead, keyed by name:
# `humanTasks: {signoff: {userRoles: "manager"}}`. The mapping key is a compile-time
# constant by construction, which the `name` field has to be validated to be.
#
# + name - The task name (unique across all of the agent's capabilities)
# + roles - Role(s) permitted to complete the task
# + resultType - Expected result type; drives form schema generation and validation
# + title - Short summary shown in the inbox; defaults to `name`
# + description - Additional context shown alongside the form
# + timeout - Maximum time to wait for completion; omit to wait indefinitely
@deprecated
public type HumanTaskDecl record {|
    string name;
    string|string[] roles;
    typedesc<anydata> resultType = anydata;
    string title?;
    string description?;
    Duration timeout?;
|};

# A peer durable agent advertised to this agent's model as a delegable tool.
# The framework runs the peer as a Temporal child workflow.
#
# + agent - The peer `workflow:DurableAgent`
# + name - Tool name advertised to the model (unique across all capabilities)
# + description - What the peer does, for the model
# + 'wait - When `true` (default) the delegation blocks durably for the peer's
#           result; when `false` the peer runs async and replies on `callbackChannel`
# + callbackChannel - Declared event channel that receives the async peer's reply;
#                     required when `wait = false`
# + requiresApproval - When `true`, a `PRE_RUN` review activity gates the delegation
# + userRoles - Role(s) permitted to decide reviews of this delegation
public type PeerDecl record {|
    DurableAgent agent;
    string name;
    string description?;
    boolean 'wait = true;
    string callbackChannel?;
    boolean requiresApproval = false;
    string|string[] userRoles?;
|};

# The complete, self-declarative configuration of a durable agent. Capability
# kinds are separate fields so each renders as its own edge type in the diagram.
#
# + systemPrompt - The agent's identity: role + instructions (the per-run query
#                  is appended as the user turn by `run`)
# + model - The model provider used for the agent's LLM calls
# + activities - `@workflow:Activity` functions, bare or as `ActivityDecl` when
#                gating/roles/bindings are needed
# + tools - AI tools: `@ai:AgentTool` functions, `ai:ToolConfig`s, toolkits, or
#           `ToolDecl` when gating is needed
# + events - Event channels keyed by channel name (`{chat: {request: string,
#            response: string}}`); the array form (`EventDecl[]`) is deprecated
# + humanTasks - Human task capabilities keyed by task name (`{signoff: {userRoles:
#                "manager"}}`); the array form (`HumanTaskDecl[]`) is deprecated.
#                Capability names share one namespace across activities, tools,
#                events, human tasks, and peers: a name claimed twice is rejected
#                when the agent registers, so the program fails at startup
# + peers - Peer durable agents advertised as delegable tools
# + maxIter - Hard cap on reasoning iterations per turn
# + eventTimeout - Maximum wait per event-channel wait (each chat turn, each event).
#                  Omit to wait indefinitely — a conversation stays open as long as it
#                  takes, and `maxEventWaits` remains the runaway backstop. On a timeout
#                  the model is told so it can wrap up gracefully
# + inputType - The type of the structured JSON payload the agent's `run` (and the
#               management API start) accepts alongside the query. `json` (the
#               default) accepts any JSON payload unvalidated; a narrower type —
#               typically a record — declares the payload's shape and is validated
#               against it at the call site and at run time; `()` declares a
#               no-payload agent whose only input is the query text
# + resultType - When declared, the agent produces a typed final result: as the
#                reasoning loop concludes, one more durable model call converts the
#                conversation outcome into this type, and `waitForResult`/`getResult`
#                return it. `()` keeps the final text response
public type DurableAgentConfig record {|
    ai:SystemPrompt systemPrompt;
    ai:ModelProvider model;
    typedesc<json>? inputType = json;
    typedesc<anydata>? resultType = ();
    (ActivityDecl|function)[] activities = [];
    (ToolDecl|ai:ToolConfig|ai:BaseToolKit|function)[] tools = [];
    map<EventConfig>|EventDecl[] events = {};
    map<HumanTaskConfig>|HumanTaskDecl[] humanTasks = {};
    PeerDecl[] peers = [];
    int maxIter = 16;
    Duration? eventTimeout = ();
|};

# Returned by the non-blocking `getResult`/`getDataResult` reads when the agent
# instance (or the specific turn) is still in progress — e.g. suspended on a human
# task. Check back later, or use the blocking `waitForResult`/`waitForDataResult`
# forms, which durably wait and are resumable across crashes.
public type AgentBusyError distinct error;

# A durable AI agent declared as an object. Must be assigned to a module-level
# `final` variable (compiler-enforced) — the compiler plugin reads the constructor
# config to generate the Temporal registration at module init, and the module-level
# variable name becomes the agent's stable identity.
#
# The object itself is a declaration anchor: capability registration is generated
# at compile time from the constructor config, and the driver methods are lowered
# by the compiler plugin to the context-appropriate runtime primitive.
#
# ```ballerina
# final workflow:DurableAgent orderAgent = check new ({
#     systemPrompt: {role: "Order assistant", instructions: "Help the user."},
#     model: wso2Model,
#     activities: [checkInventory, reserveStock],
#     events: {chat: {request: string, response: string, cardinality: workflow:MULTI_EVENT}}
# });
# ```
public isolated class DurableAgent {

    private string agentName = "";

    # Declares the agent. Capabilities are fixed here and registered with the
    # workflow runtime at module init by the compiler plugin.
    #
    # + config - The agent's complete configuration
    # + return - An error when the configuration is invalid
    public isolated function init(*DurableAgentConfig config) returns error? {
    }

    # Binds the agent's stable identity — its module-level variable name — to this
    # object. Called by the compiler-plugin-generated module-init code; not part of
    # the public API surface. The first binding wins; later calls are ignored.
    #
    # + agentName - The agent's module-level variable name
    public isolated function bindAgentName(string agentName) {
        lock {
            if self.agentName == "" {
                self.agentName = agentName;
            }
        }
    }

    # Starts the agent durably and returns the new instance ID — always the ID,
    # never the result (a durable agent may suspend for days on a human task, so
    # no caller thread is blocked). Outside a workflow this is a top-level start;
    # inside a `@workflow:Workflow` the agent runs as a Temporal child workflow.
    #
    # + query - The user turn appended to the agent's system prompt
    # + input - Optional structured JSON payload for the run; must match the
    #           agent's declared `inputType`
    # + return - The new agent instance ID, or an error
    public isolated function run(string query, json input = ()) returns string|error {
        observe:StartAgentSpan span = observe:createStartAgentSpan(self.getAgentName());
        string|error result = self.runAgentNative(query, input);
        if result is string {
            span.addInstanceId(result);
            span.close();
        } else {
            span.close(result);
        }
        return result;
    }

    private isolated function runAgentNative(string query, json input) returns string|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "runAgent"
    } external;

    private isolated function getAgentName() returns string {
        lock {
            return self.agentName;
        }
    }

    # Sends an event to a running instance on a declared channel and returns a
    # correlation token for reading that turn's response.
    #
    # The instance must be one of **this** agent's: the compiler plugin checks the
    # channel and its payload against this declaration, and the runtime checks them
    # against the target instance's. The two agree exactly when the instance came
    # from this agent's `run`.
    #
    # + instanceId - An instance ID this agent's `run` returned
    # + eventName - A channel declared in the agent's `events`
    # + data - The payload; validated against the channel's declared `request` type
    # + return - A correlation token for `getDataResult`/`waitForDataResult`,
    #            or an error
    public isolated function sendData(string instanceId, string eventName, anydata data)
            returns string|error {
        observe:SendAgentEventSpan span =
                observe:createSendAgentEventSpan(self.getAgentName(), instanceId, eventName);
        string|error result = self.sendDataNative(instanceId, eventName, data);
        span.close(result is error ? result : ());
        return result;
    }

    private isolated function sendDataNative(string instanceId, string eventName, anydata data)
            returns string|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "sendData"
    } external;

    # Returns the final result of an instance if it has finished, without waiting.
    # While the instance is still working (e.g. suspended on a human task) a
    # `workflow:AgentBusyError` is returned — check back later, or use `waitForResult`.
    #
    # + instanceId - The agent instance ID returned by `run`
    # + T - Expected result type (inferred from context)
    # + return - The result as `T`, a `workflow:AgentBusyError` while in progress,
    #            or an error
    public isolated function getResult(string instanceId, typedesc<anydata> T = <>)
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "getResult"
    } external;

    # Returns the response for a specific `sendData` turn if it is ready, without
    # waiting. While the turn is unanswered a `workflow:AgentBusyError` is returned.
    #
    # + instanceId - The agent instance ID returned by `run`
    # + token - The correlation token returned by `sendData`
    # + T - Expected response type (inferred from context)
    # + return - The turn's response as `T`, a `workflow:AgentBusyError` while
    #            unanswered, or an error
    public isolated function getDataResult(string instanceId, string token,
            typedesc<anydata> T = <>) returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "getDataResult"
    } external;

    # Waits until the instance finishes and returns its result. Inside a workflow
    # this durably suspends the caller (no thread held); from a service it blocks
    # but is resumable — if the caller crashes, calling again after restart resumes
    # the wait, because the result lives in history.
    #
    # + instanceId - The agent instance ID returned by `run`
    # + T - Expected result type (inferred from context)
    # + return - The result as `T`, or an error
    public isolated function waitForResult(string instanceId, typedesc<anydata> T = <>)
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "waitForResult"
    } external;

    # Waits for a specific `sendData` turn's response (same durability guarantees
    # as `waitForResult`).
    #
    # + instanceId - The agent instance ID returned by `run`
    # + token - The correlation token returned by `sendData`
    # + T - Expected response type (inferred from context)
    # + return - The turn's response as `T`, or an error
    public isolated function waitForDataResult(string instanceId, string token,
            typedesc<anydata> T = <>) returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
        name: "waitForDataResult"
    } external;
}

// ---------------------------------------------------------------------------
// Object-model runner spec (internal)
// ---------------------------------------------------------------------------
// Built natively from the agent declaration registry; consumed by the shared
// runner workflow (runDurableAgentObject) to register capabilities on the
// native agent context and start the ReAct loop.

type DurableAgentActivitySpec record {|
    string toolName;
    function activity;
    json meta = ();
    map<anydata|object {}>? bindings = ();
|};

type DurableAgentToolSpec record {|
    string toolName;
    function tool;
    json meta = ();
|};

type DurableAgentEventSpec record {|
    string name;
    typedesc<anydata> request;
    typedesc<anydata>? response = ();
    string cardinality = "MULTI_EVENT";
|};

type DurableAgentHumanTaskSpec record {|
    string name;
    json meta = ();
    typedesc<anydata> resultType = anydata;
|};

type DurableAgentPeerSpec record {|
    string name;
    string targetAgent;
    json meta = ();
|};

type DurableAgentRunSpec record {|
    json systemPrompt;
    int maxIter;
    json eventTimeout = ();
    ai:ModelProvider model;
    typedesc<anydata>? resultType = ();
    DurableAgentActivitySpec[] activities = [];
    DurableAgentToolSpec[] tools = [];
    DurableAgentEventSpec[] events = [];
    DurableAgentHumanTaskSpec[] humanTasks = [];
    DurableAgentPeerSpec[] peers = [];
|};

isolated function getDurableAgentRunSpec(string agentName) returns DurableAgentRunSpec|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "getRunSpec"
} external;
