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
import ballerina/time;

# Configuration for a durable agent run: the agent's identity (system prompt),
# its model, and reasoning limits. Tools, activities, human tasks, and update
# channels are registered on the context before `buildAndRun`.
public type AgentRunConfig record {|
    # The system prompt assigned to the agent
    @display {label: "System Prompt"}
    ai:SystemPrompt systemPrompt;

    # The model provider used for the agent's LLM calls
    @display {label: "Model"}
    ai:ModelProvider model;

    # The maximum number of LLM reasoning iterations per conversation turn
    # before the agent fails with an error
    @display {label: "Maximum Iterations"}
    int maxIter = 16;

    # Specifies whether verbose logging is enabled
    @display {label: "Verbose"}
    boolean verbose = false;

    # How the agent consumes its update-channel requests: `SINGLE_EVENT` (each
    # channel once per run) or `MULTI_EVENT` (re-armable channels for multi-turn
    # conversations; requires `eventTimeout`)
    @display {label: "Interaction Pattern"}
    AgentInteractionPattern interaction = SINGLE_EVENT;

    # Maximum wait per update/event. On timeout the model is told the wait timed
    # out so it can wrap up gracefully. Required for `MULTI_EVENT`
    @display {label: "Event Timeout"}
    time:Duration? eventTimeout = ();

    # Hard cap on the total number of event waits per run; exceeding it fails
    # the agent (backstop for open-ended conversations)
    @display {label: "Maximum Event Waits"}
    int maxEventWaits = 50;

    # Approval policy for capabilities registered with `requiresApproval = true`.
    # When such a capability is about to run, a review activity is created and the
    # agent suspends durably until a human decides
    @display {label: "Approval"}
    ApprovalConfig approval = {};
|};

# Approval policy for gated agent capabilities.
#
# + userRoles - Role(s) permitted to decide the review. Defaults to `"manager"`
# + timeout - Maximum time to wait for a decision. On timeout the model is told
#             the review timed out so it can wrap up. Omit to wait indefinitely
public type ApprovalConfig record {|
    string|string[] userRoles = "manager";
    time:Duration? timeout = ();
|};

# How a durable agent consumes its update-channel requests and data events.
public enum AgentInteractionPattern {
    # Each registered update channel/event may be consumed once per run (default)
    SINGLE_EVENT,
    # Events are re-armable: the agent may wait repeatedly on the same event, each wait
    # consuming the next queued payload (conversational agents). Requires an event
    # timeout as its safety mechanism
    MULTI_EVENT
}

# The execution context for a durable AI agent. Injected as the first parameter
# of a `@workflow:DurableAgentFunction` function.
#
# Unlike `workflow:Context`, this context deliberately does not expose
# `callActivity`, `sleep`, or `awaitHumanTask`. Instead, capabilities are
# registered on the context and the agent decides when to use them inside the
# durable ReAct loop driven by `buildAndRun`:
#
# - `registerActivity` — a `@workflow:Activity` function becomes a tool that runs
#   as a durable Temporal activity
# - `registerAgentTool` — an AI tool (`@ai:AgentTool` function, `ai:ToolConfig`,
#   or `ai:BaseToolKit`) executed durably through the built-in activity wrapper
# - `registerHumanTask` — a human task becomes a tool; when the agent invokes it,
#   a human-task sub-workflow starts and the agent suspends durably until a
#   person completes it
# - `registerUpdateEvents` — declares a named two-way update channel (request and
#   optional response types); `workflow:updateAgent` drives it from outside
# - `buildAndRun` — builds the agent from everything registered above and hands
#   control to the durable ReAct loop; must be the last statement of the agent
public client class AgentContext {
    private handle nativeContext;

    # Creates an agent context wrapping the native handle. Called by the workflow
    # runtime; do not instantiate `AgentContext` directly.
    # + nativeContext - Native agent context handle from the workflow engine
    public isolated function init(handle nativeContext) {
        self.nativeContext = nativeContext;
    }

    # Registers a `@workflow:Activity` function as an agent tool. The tool runs as
    # a durable Temporal activity that the agent may invoke during reasoning.
    #
    # Arguments may be partially applied at registration via `bindings`: bound
    # values are fixed and never advertised to the model — only the remaining
    # data parameters appear in the tool's schema. Client-object parameters
    # (e.g. the `connection` of a built-in activity such as
    # `activity:callRestAPI`) must be bound this way, referencing a module-level
    # `final` client variable:
    #
    # ```ballerina
    # check ctx.registerActivity(activity:callRestAPI,
    #         name = "fetchExchangeRates",
    #         description = "Fetches currency exchange rates from the rates API",
    #         bindings = {connection: ratesApi, method: activity:GET});
    # ```
    #
    # + activity - The `@workflow:Activity` function to expose as a tool
    # + name - The tool name advertised to the model. Defaults to the function name
    # + description - The tool description advertised to the model
    # + bindings - Arguments fixed at registration, keyed by parameter name.
    #              Bound client objects are transported as `"connection:<name>"`
    #              markers and resolved on the executing worker
    # + requiresApproval - When `true`, the tool is gated: before the agent runs it,
    #              a review activity is created and the agent suspends durably until
    #              a human proceeds (optionally editing the arguments) or rejects
    # + retryPolicy - Failure behaviour: `NoRetry` (report the failure to the model),
    #              `AutoRetry` (durable backoff retries), or `ManualRetry` (create a
    #              review activity on failure so a human decides to rerun or fail)
    # + return - An error if the tool cannot be registered, otherwise nil
    public isolated function registerActivity(function activity, string? name = (),
            string? description = (), map<anydata|object {}>? bindings = (),
            boolean requiresApproval = false,
            AutoRetry|ManualRetry|NoRetry retryPolicy = NoRetry) returns error? {
        return recordActivityTool(self.nativeContext, activity, name, description, bindings,
                requiresApproval, retryPolicy);
    }

    # Registers an AI tool with the agent. Accepts an `ai:ToolConfig` value, a
    # function annotated with `@ai:AgentTool` (normalized via the ai module's tool
    # plumbing), or an `ai:BaseToolKit` implementation (expanded via its
    # `getTools()`). When the agent invokes the tool, the call is executed durably
    # through the built-in activity wrapper, delegating argument binding and
    # `ai:Context` injection to `ai:executeTool`.
    #
    # + tool - The tool to register
    # + requiresApproval - When `true`, every tool in this registration is gated:
    #            before the agent runs it, a review activity is created and the agent
    #            suspends durably until a human proceeds or rejects. `gatedTools`
    #            narrows this to specific tool names (for a toolkit / MCP server)
    # + gatedTools - When set, only these tool names require approval (the rest run
    #            freely). Use for a `BaseToolKit`/MCP server whose tools you do not
    #            control; ignored when `requiresApproval` is `false`
    # + return - An error if the tool cannot be registered (e.g. a function
    #            missing the `@ai:AgentTool` annotation), otherwise nil
    public isolated function registerAgentTool(ai:BaseToolKit|ai:ToolConfig|ai:FunctionTool tool,
            boolean requiresApproval = false, string[]? gatedTools = ()) returns error? {
        if tool is ai:BaseToolKit {
            foreach ai:ToolConfig config in tool.getTools() {
                check self.recordToolConfig(config, self.isGated(config.name, requiresApproval, gatedTools));
            }
        } else if tool is ai:ToolConfig {
            check self.recordToolConfig(tool, self.isGated(tool.name, requiresApproval, gatedTools));
        } else {
            ai:ToolConfig[] configs = ai:getToolConfigs([tool]);
            if configs.length() == 0 {
                return error("Agent tool functions must be annotated with @ai:AgentTool");
            }
            check self.recordToolConfig(configs[0], self.isGated(configs[0].name, requiresApproval, gatedTools));
        }
    }

    // A tool is gated when approval is requested and either no name filter is given
    // (the whole registration is gated) or the tool's name is in the filter.
    private isolated function isGated(string toolName, boolean requiresApproval, string[]? gatedTools)
            returns boolean {
        if !requiresApproval {
            return false;
        }
        return gatedTools is () || gatedTools.indexOf(toolName) != ();
    }

    # Declares a named two-way update channel for the agent. `workflow:updateAgent`
    # sends a request on the channel and blocks until the agent answers the turn
    # that consumed it. Inside the ReAct loop the channel also appears as a durable
    # wait: a channel named `chat` drives the conversation itself.
    #
    # + name - The update channel name (e.g. `"chat"`)
    # + requestType - The request payload type; validated when a request arrives
    # + responseType - The expected response type; when provided, the turn answer
    #                  is validated against it before completing the update
    # + return - An error if the channel cannot be registered, otherwise nil
    public isolated function registerUpdateEvents(string name, typedesc<anydata> requestType,
            typedesc<anydata>? responseType = ()) returns error? {
        return registerAgentUpdateEvent(self.nativeContext, name, requestType, responseType);
    }

    private isolated function recordToolConfig(ai:ToolConfig config, boolean requiresApproval = false)
            returns error? {
        map<json>? parameters = config.parameters;
        return recordAiTool(self.nativeContext, config.caller, config.name, config.description,
                parameters is () ? () : parameters.toJsonString(), requiresApproval);
    }

    # Registers a human task as an agent tool. This is the durable-agent
    # counterpart of `workflow:Context`'s `awaitHumanTask`: when the agent
    # decides to involve a person, invoking this tool starts a human-task
    # sub-workflow and suspends the agent durably until the task is completed
    # (via `workflow:completeHumanTask` or the management API).
    #
    # + taskName - Identifies the task type; must not contain `.` or `|`
    # + userRoles - One or more roles permitted to complete this task
    # + resultType - Expected result type; drives form schema generation and
    #                runtime validation of the completion payload
    # + title - Short summary shown in the inbox. Defaults to `taskName`
    # + description - Additional context shown alongside the form; also used as
    #                 the tool description advertised to the model
    # + timeout - Maximum time to wait for completion. On timeout the model is told
    #             the task timed out so it can react. Omit to wait indefinitely
    # + return - An error if the task cannot be registered, otherwise nil
    public isolated function registerHumanTask(string taskName, string|string[] userRoles,
            typedesc<anydata> resultType = anydata, string? title = (), string? description = (),
            time:Duration? timeout = ()) returns error? {
        return recordHumanTaskTool(self.nativeContext, taskName, userRoles, resultType, title, description,
                timeout);
    }

    # Builds the agent from everything registered on this context (activities, AI
    # tools, human tasks, update channels) and hands control to the durable ReAct
    # loop. This is a terminal operation: it must be the last statement of the
    # `@workflow:DurableAgentFunction` function (enforced by the compiler plugin). Every
    # LLM call and tool call is executed durably, so the agent survives worker
    # crashes and can suspend for days waiting on human tasks or updates.
    #
    # + query - The initial user query. When empty, the agent waits for the
    #           first `chat` update channel request
    # + config - The agent configuration (system prompt, model, limits)
    # + return - An error if the agent fails, otherwise nil
    public isolated function buildAndRun(@display {label: "Query"} string query = "",
            *AgentRunConfig config) returns error? {
        check setAgentInteraction(self.nativeContext, config.interaction, config.eventTimeout,
                config.maxEventWaits);
        check setAgentApproval(self.nativeContext, config.approval.userRoles, config.approval.timeout);
        setAgentModelProvider(self.nativeContext, config.model);
        check registerAgentModelForContext(self.nativeContext);
        string agentName = getAgentWorkflowType(self.nativeContext);
        string toolDefsJson = check getAgentToolDefs(self.nativeContext);
        json toolDefs = check toolDefsJson.fromJsonString();
        AgentToolDef[] defs = check toolDefs.cloneWithType();
        error? result = runAgentLoop(self.nativeContext, agentName, config, query, defs);
        // Settle any outstanding updateAgent requests before the workflow completes:
        // unconsumed updates receive the agent's final response (or its failure)
        // instead of failing with "workflow completed before the update completed".
        finishAgentUpdates(self.nativeContext, result is error ? result.message() : ());
        return result;
    }

    # Returns the agent's recorded final response for this run ("" when none was
    # recorded). Used by the object-model runner to surface the response as the
    # workflow result; not part of the public API surface.
    #
    # + return - The final response text
    isolated function getFinalResponse() returns string {
        return readAgentContextFinalResponse(self.nativeContext);
    }
}

// Internal shape of a registered tool: the LLM-facing definition plus the
// dispatch kind ("activity", "aitool", "humantask", or "event:<name>") and
// whether the tool is gated (a review activity is created before it runs).
type AgentToolDef record {|
    string name;
    string description;
    map<json> parameters?;
    string kind;
    boolean requiresApproval = false;
|};

// ============================================================================
// Native bindings for AgentContext
// ============================================================================

isolated function recordActivityTool(handle nativeContext, function tool, string? name,
        string? description, map<anydata|object {}>? bindings, boolean requiresApproval,
        AutoRetry|ManualRetry|NoRetry retryPolicy) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordActivityTool"
} external;

isolated function recordAiTool(handle nativeContext, function tool, string name, string description,
        string? parametersJson, boolean requiresApproval) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordAiTool"
} external;

// Starts a PRE_RUN review activity for a gated tool and blocks until a human decides.
// Returns the decision as JSON: {"action": "proceed"|"proceed-with-input"|"reject",
// "input"?: {...}, "feedback"?: "..."}.
isolated function awaitAgentToolReview(handle nativeContext, string toolName, string argsJson)
        returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "awaitToolReview"
} external;

isolated function setAgentApproval(handle nativeContext, string|string[] userRoles, time:Duration? timeout)
        returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "setAgentApproval"
} external;

isolated function recordHumanTaskTool(handle nativeContext, string taskName, string|string[] userRoles,
        typedesc<anydata> resultType, string? title, string? description, time:Duration? timeout)
        returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordHumanTaskTool"
} external;

isolated function registerAgentUpdateEvent(handle nativeContext, string name, typedesc<anydata> requestType,
        typedesc<anydata>? responseType) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "registerUpdateEvent"
} external;

isolated function setAgentInteraction(handle nativeContext, string pattern, time:Duration? eventTimeout,
        int maxEventWaits) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "setInteraction"
} external;

isolated function finishAgentUpdates(handle nativeContext, string? failureMessage) = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "finishAgentUpdates"
} external;

isolated function getAgentToolDefs(handle nativeContext) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "getToolDefs"
} external;

isolated function getAgentWorkflowType(handle nativeContext) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "getWorkflowType"
} external;

isolated function setAgentModelProvider(handle nativeContext, object {} model) = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "setModelProvider"
} external;

isolated function registerAgentModelForContext(handle nativeContext) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "registerModel"
} external;

isolated function readAgentContextFinalResponse(handle contextHandle) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "getFinalResponse"
} external;
