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

// ============================================================================
// anydata mirrors of the ballerina/ai chat message types.
//
// `ai:ChatMessage` itself is not `anydata` because user/system message content
// may be an `ai:Prompt` object. Agent workflows only ever exchange string
// content, so the conversation history is kept in these plain-data mirrors —
// making it replay-safe workflow state that can cross the activity boundary —
// and converted to `ai:` types inside the `llmChat` activity.
// ============================================================================

# System message in an agent conversation.
public type AgentSystemMessage record {|
    # Role of the message
    "system" role = "system";
    # Content of the message
    string content;
|};

# User message in an agent conversation.
public type AgentUserMessage record {|
    # Role of the message
    "user" role = "user";
    # Content of the message
    string content;
|};

# A tool invocation requested by the model.
public type AgentFunctionCall record {|
    # Name of the tool function
    string name;
    # Arguments to pass to the tool, keyed by parameter name
    map<json>? arguments = {};
    # Identifier for the tool call
    string id?;
|};

# Assistant (model) message in an agent conversation.
public type AgentAssistantMessage record {|
    # Role of the message
    "assistant" role = "assistant";
    # Text content; nil when the model requested tool calls instead
    string? content = ();
    # An optional name for the participant
    string name?;
    # Tool calls requested by the model
    AgentFunctionCall[]? toolCalls = ();
|};

# Tool result message in an agent conversation.
public type AgentFunctionMessage record {|
    # Role of the message
    "function" role = "function";
    # Name of the tool that produced this result
    string name;
    # Tool output serialized as a string
    string? content = ();
    # Identifier correlating this result to the tool call
    string id?;
|};

# Any message in an agent conversation.
public type AgentChatMessage AgentSystemMessage|AgentUserMessage|AgentAssistantMessage|AgentFunctionMessage;

# Runs the durable agent ReAct loop. Called from `AgentContext.buildAndRun`;
# not intended to be called directly.
#
# Conversation history is a workflow-local variable (replay-safe). Tool calls
# dispatch by kind: activities and AI tools run as durable Temporal activities
# (AI tools through the `executeAgentTool` wrapper), human-task tools start a
# human-task sub-workflow and suspend the agent until completion, and event
# tools suspend the agent until the corresponding data event arrives.
#
# Under `MULTI_EVENT` interaction with a declared `chat` event, the loop owns
# conversation continuity: after each final answer it automatically waits for
# the next chat message. The conversation ends explicitly — the model calls the
# built-in `endConversation` tool (e.g. when the user says goodbye) — or
# gracefully when the event timeout elapses with no new message.
#
# + ctxHandle - The native agent context handle
# + agentName - The agent's workflow type (keys the registered model provider)
# + config - The system prompt and reasoning limits
# + prompt - The initial user prompt, or "" to wait for the first chat event
# + toolDefs - The registered tool definitions (with dispatch kinds)
# + return - An error if the agent fails, otherwise nil
isolated function runAgentLoop(handle ctxHandle, string agentName, AgentRunConfig config, string prompt,
        AgentToolDef[] toolDefs) returns error? {
    map<string> toolKinds = {};
    map<boolean> toolGated = {};
    boolean conversational = false;
    boolean hasChatEvent = false;
    foreach AgentToolDef def in toolDefs {
        toolKinds[def.name] = def.kind;
        toolGated[def.name] = def.requiresApproval;
        if def.kind == "end" {
            conversational = true; // the endConversation tool is advertised under MULTI_EVENT
        }
        if def.kind == "event:chat" {
            hasChatEvent = true;
        }
    }
    boolean autoContinue = conversational && hasChatEvent;

    ai:ChatCompletionFunctions[] llmToolDefs = [];
    foreach AgentToolDef def in toolDefs {
        // Under framework-owned continuity the loop re-arms the chat wait itself after
        // every answer. Never advertise the chat wait-tool to the model: calling it
        // mid-turn would desynchronize the update/reply pairing of the current turn.
        if autoContinue && def.kind == "event:chat" {
            continue;
        }
        ai:ChatCompletionFunctions llmDef = {name: def.name, description: def.description};
        map<json>? parameters = def.parameters;
        if parameters is map<json> {
            llmDef.parameters = parameters;
        }
        llmToolDefs.push(llmDef);
    }

    // Render the system prompt the same way `ai:Agent` does: role followed by
    // the specific instructions.
    string role = config.systemPrompt.role.trim();
    string instructions = config.systemPrompt.instructions;
    string systemContent = role == "" ? instructions : string `${role} ${instructions}`;
    AgentChatMessage[] history = [<AgentSystemMessage>{content: systemContent}];
    if prompt != "" {
        history.push(<AgentUserMessage>{content: prompt});
    } else {
        // No initial prompt: wait durably for one chat event, if the agent
        // declared one in its signature.
        string? chatMessage = check awaitAgentChatEvent(ctxHandle);
        if chatMessage is string {
            history.push(<AgentUserMessage>{content: chatMessage});
        }
    }

    int maxIterations = int:max(1, config.maxIter);
    while true {
        // One conversation turn: a bounded ReAct loop over LLM + tool calls.
        boolean turnAnswered = false;
        foreach int _ in 0 ..< maxIterations {
            AgentAssistantMessage assistant = check callAgentActivity("llmChat",
                    {"agentName": agentName, "messages": history.toJson(), "tools": llmToolDefs.toJson()});
            history.push(assistant);

            // Record every content-bearing reply (not only the final one): in a
            // multi-turn conversation the memo/response always holds the latest turn.
            string? content = assistant.content;
            boolean contentRecorded = false;
            if content is string && content != "" {
                check setAgentResponse(ctxHandle, content);
                contentRecorded = true;
            }

            AgentFunctionCall[]? toolCalls = assistant.toolCalls;
            if toolCalls is () || toolCalls.length() == 0 {
                turnAnswered = true;
                break;
            }

            foreach AgentFunctionCall call in toolCalls {
                if toolKinds[call.name] == "end" {
                    // Explicit end of the conversation. When the model put its
                    // farewell in the tool arguments instead of the content,
                    // record it as the final response.
                    if !contentRecorded {
                        map<json>? endArgs = call.arguments;
                        json farewell = endArgs is map<json> ? endArgs["farewell"] : ();
                        if farewell is string && farewell != "" {
                            check setAgentResponse(ctxHandle, farewell);
                        }
                    }
                    return;
                }
                string output = check dispatchAgentTool(ctxHandle, agentName, call, toolKinds[call.name],
                        autoContinue, toolGated[call.name] ?: false);
                AgentFunctionMessage functionMessage = {name: call.name, content: output};
                string? callId = call.id;
                if callId is string {
                    functionMessage.id = callId;
                }
                history.push(functionMessage);
            }
        }
        if !turnAnswered {
            return error(string `Agent exceeded the maximum number of iterations per turn (${maxIterations})`);
        }
        if !autoContinue {
            return;
        }
        // Conversational agent: keep the conversation open — wait durably for the
        // next chat message. A wait timeout ends the conversation gracefully; the
        // max-event-waits safety cap fails it hard.
        anydata|error next = awaitAgentEvent(ctxHandle, "chat");
        if next is error {
            if next.message().includes("Timed out") {
                return;
            }
            return next;
        }
        history.push(<AgentUserMessage>{content: next is string ? next : next.toJsonString()});
    }
}

// Dispatches one tool call by kind and renders the result as text for the model.
// Tool failures are fed back as text so the model can recover; only
// infrastructure errors propagate.
isolated function dispatchAgentTool(handle ctxHandle, string agentName, AgentFunctionCall call, string? kind,
        boolean autoContinue, boolean requiresApproval = false) returns string|error {
    if kind is () {
        return string `Error: unknown tool '${call.name}'`;
    }

    // Under framework-owned continuity the loop re-arms the chat wait itself after every
    // answer. If the model still asks to wait for chat (e.g. replaying an older history),
    // correct it instead of waiting — waiting here would desynchronize the update/reply
    // pairing of the current turn.
    if autoContinue && kind == "event:chat" {
        return "The chat conversation is already open - do not wait for it. " +
                "Answer the user's current message directly.";
    }

    map<anydata> args = {};
    map<json>? callArgs = call.arguments;
    if callArgs is map<json> {
        foreach [string, json] [name, value] in callArgs.entries() {
            args[name] = value;
        }
    }

    // Gated capability: create a PRE_RUN review activity and suspend durably until a
    // human decides. On reject the model is told why (so it re-plans); on proceed the
    // tool runs, optionally with arguments the reviewer edited.
    if requiresApproval && (kind == "activity" || kind == "aitool" || kind.startsWith("peeragent:")) {
        string decisionJson = check awaitAgentToolReview(ctxHandle, call.name, args.toJson().toJsonString());
        json decision = check decisionJson.fromJsonString();
        string action = check decision.action;
        if action == "reject" {
            json feedbackJson = check decision.feedback;
            string feedback = feedbackJson is string && feedbackJson != "" ? feedbackJson : "no reason given";
            return string `The human reviewer rejected calling '${call.name}'. Reason: ${feedback}. ` +
                    "Do not retry it; consider an alternative or ask the user how to proceed.";
        }
        if action == "proceed-with-input" {
            json edited = check decision.input;
            if edited is map<json> {
                args = {};
                foreach [string, json] [name, value] in edited.entries() {
                    args[name] = value;
                }
            }
        }
    }

    anydata|error result;
    if kind == "activity" {
        // Resolved through the context so registration-time bindings (fixed
        // arguments, connection markers) are merged in and a tool-name override
        // maps back to the underlying activity function.
        result = callAgentActivityTool(ctxHandle, call.name, args);
    } else if kind == "aitool" {
        // AI tool function pointers run through the built-in activity wrapper.
        result = callAgentActivity("executeAgentTool",
                {"agentName": agentName, "toolName": call.name, "arguments": args.toJson()});
    } else if kind == "humantask" {
        // Starts a human-task sub-workflow and suspends the agent durably
        // until a person completes it.
        result = awaitAgentHumanTask(ctxHandle, call.name, args.toJson());
    } else if kind.startsWith("event:") {
        // Suspends the agent durably until the data event arrives.
        result = awaitAgentEvent(ctxHandle, kind.substring(6));
    } else if kind.startsWith("peeragent:") {
        // Delegates to a peer durable agent running as a true Temporal child workflow.
        result = dispatchPeerAgent(ctxHandle, kind.substring(10), args);
    } else {
        return string `Error: unsupported tool kind '${kind}' for tool '${call.name}'`;
    }

    if result is error {
        return string `Error: ${result.message()}`;
    }
    // String results pass through raw (no JSON quoting).
    return result is string ? result : result.toJsonString();
}

# The built-in activity wrapper that executes a registered AI tool function
# pointer. AI tools (`ai:ToolConfig` / `@ai:AgentTool` functions / toolkit
# tools) are not `@workflow:Activity` functions, so the ReAct loop invokes them
# durably through this wrapper, delegating typed argument binding and
# `ai:Context` injection to the ai module's `ai:executeTool`.
#
# + agentName - The agent's workflow type; keys the tool registry
# + toolName - The registered tool name
# + arguments - Tool arguments keyed by parameter name
# + return - The tool result, or an error
@Activity
public isolated function executeAgentTool(string agentName, string toolName, json arguments)
        returns anydata|error {
    ai:FunctionTool fn = check getAgentToolFunction(agentName, toolName);
    map<json> args = arguments is map<json> ? arguments : {};
    ai:ToolExecutionResult execution = ai:executeTool(fn, args);
    any|error result = execution.result;
    if result is error {
        return result;
    }
    if result is anydata {
        return result;
    }
    // Non-anydata tool results (objects, streams) cannot cross the activity
    // boundary; surface their textual form to the model instead.
    return result.toString();
}

// Looks up a registered AI tool function pointer for the wrapper activity.
isolated function getAgentToolFunction(string agentName, string toolName)
        returns ai:FunctionTool|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "getAgentToolFunction"
} external;

# The built-in LLM chat activity. Executes one model call outside the workflow
# thread so that the non-deterministic LLM interaction is recorded in the
# workflow history and never re-executed on replay.
#
# + agentName - The agent's workflow type; keys the registered model provider
# + messages - Conversation history as JSON (`AgentChatMessage[]`)
# + tools - Tool definitions as JSON (`ai:ChatCompletionFunctions[]`)
# + return - The assistant's reply, or an error
@Activity
public isolated function llmChat(string agentName, json messages, json tools)
        returns AgentAssistantMessage|error {
    ai:ModelProvider model = check getAgentModel(agentName);
    AgentChatMessage[] history = check messages.cloneWithType();
    ai:ChatCompletionFunctions[] toolDefs = check tools.cloneWithType();

    ai:ChatMessage[] aiMessages = [];
    foreach AgentChatMessage message in history {
        aiMessages.push(check toAiMessage(message));
    }

    ai:ChatAssistantMessage reply = check model->chat(aiMessages, toolDefs);
    return reply.cloneWithType();
}

# The built-in structured-generation activity. Durably wraps
# `ai:ModelProvider->generate`, producing a value of the caller's expected type
# from a natural-language query.
#
# + agentName - The agent's workflow type; keys the registered model provider
# + query - The natural-language prompt describing what to generate
# + return - The generated value as `anydata` (coerced to the caller's type by
#            the dependent-typing path), or an error
@Activity
public isolated function generate(string agentName, string query) returns anydata|error {
    ai:ModelProvider model = check getAgentModel(agentName);
    ai:Prompt prompt = `${query}`;
    anydata result = check model->generate(prompt);
    return result;
}

// Converts a mirror message to the corresponding ballerina/ai message type.
isolated function toAiMessage(AgentChatMessage message) returns ai:ChatMessage|error {
    if message is AgentSystemMessage {
        return <ai:ChatSystemMessage>{role: ai:SYSTEM, content: message.content};
    }
    if message is AgentUserMessage {
        return <ai:ChatUserMessage>{role: ai:USER, content: message.content};
    }
    if message is AgentAssistantMessage {
        return message.cloneWithType(ai:ChatAssistantMessage);
    }
    return message.cloneWithType(ai:ChatFunctionMessage);
}

// ============================================================================
// Native bindings used by the agent loop
// ============================================================================

// Executes a registered agent tool (or the built-in llmChat) as a durable
// Temporal activity, resolving the activity type from the current workflow.
isolated function callAgentActivity(string name, map<anydata> args, typedesc<anydata> targetType = <>)
        returns targetType|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "callActivity"
} external;

// Executes a registered activity tool by its advertised tool name, merging any
// registration-time bindings into the model-supplied arguments before running
// the underlying activity durably.
isolated function callAgentActivityTool(handle nativeContext, string toolName, map<anydata> args,
        typedesc<anydata> targetType = <>) returns targetType|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "callActivityTool"
} external;

// Waits durably for the agent's "chat" event, if declared in the signature.
// Returns nil when the agent declares no chat event.
isolated function awaitAgentChatEvent(handle nativeContext) returns string?|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "awaitChatEvent"
} external;

// Suspends the agent durably until the named data event arrives; returns its data.
isolated function awaitAgentEvent(handle nativeContext, string eventName) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "awaitEvent"
} external;

// Starts a human-task sub-workflow and suspends the agent durably until a
// person completes it; returns the completion result.
isolated function awaitAgentHumanTask(handle nativeContext, string taskName, json payload)
        returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "awaitHumanTask"
} external;

// Stores the agent's final textual response for later retrieval.
isolated function setAgentResponse(handle nativeContext, string response) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "setResponse"
} external;

// Looks up the model provider registered for an agent workflow type.
isolated function getAgentModel(string agentName) returns ai:ModelProvider|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "getAgentModel"
} external;



// ============================================================================
// Object-model durable agent runner
// ============================================================================

# Shared runner workflow for object-model durable agents. Registered by the
# compiler plugin as the workflow function of every `final workflow:DurableAgent`
# declaration (workflow type `workflow-<agentName>`), so the whole function-based
# agent substrate — adapter dispatch, model/tool registries, management views —
# works unchanged. The runner resolves the agent's declaration by name, registers
# its capabilities on the AgentContext exactly as a function-based agent body
# would, hands control to the durable ReAct loop, and returns the agent's final
# response as the workflow result (so `getResult`/`waitForResult` read it from
# the instance).
#
# This is an **internal** function referenced by generated code; do not call it
# directly.
#
# + ctx - The agent context (injected by the workflow adapter)
# + runInput - The run request: `{agentName, query, input}`
# + return - The agent's final response, or an error
public isolated function runDurableAgentObject(AgentContext ctx, map<anydata> runInput)
        returns anydata|error {
    string agentName = check runInput["agentName"].ensureType();
    string query = check runInput["query"].ensureType();
    anydata payload = runInput["input"];

    DurableAgentRunSpec spec = check getDurableAgentRunSpec(agentName);

    foreach DurableAgentActivitySpec activitySpec in spec.activities {
        check registerDeclaredActivity(ctx, activitySpec);
    }
    foreach DurableAgentToolSpec toolSpec in spec.tools {
        ai:FunctionTool toolFn = check toolSpec.tool.ensureType();
        check ctx.registerAgentTool(toolFn);
    }
    boolean multiEvent = false;
    foreach DurableAgentEventSpec eventSpec in spec.events {
        check ctx.registerUpdateEvents(eventSpec.name, eventSpec.request, eventSpec.response);
        if eventSpec.cardinality == "MULTI_EVENT" {
            multiEvent = true;
        }
    }
    foreach DurableAgentHumanTaskSpec taskSpec in spec.humanTasks {
        check registerDeclaredHumanTask(ctx, taskSpec);
    }
    foreach DurableAgentPeerSpec peerSpec in spec.peers {
        check registerDeclaredPeer(ctx, peerSpec);
    }

    ai:SystemPrompt systemPrompt = check spec.systemPrompt.cloneWithType();

    // Structured run input is surfaced to the model as part of the user turn.
    string effectiveQuery = payload is () ? query
        : query + "\n\nInput:\n" + payload.toJsonString();

    check ctx.buildAndRun(effectiveQuery,
        systemPrompt = systemPrompt,
        model = spec.model,
        maxIter = spec.maxIter,
        interaction = multiEvent ? MULTI_EVENT : SINGLE_EVENT,
        // Per-channel cardinality (and its timeout policy) lands with typed events;
        // until then a multi-event agent uses a bounded default wait per turn.
        eventTimeout = multiEvent ? {minutes: 30} : ()
    );
    return ctx.getFinalResponse();
}

# Registers one declared activity capability on the runner's context, converting
# the declaration metadata (description, gating, retry policy) captured at compile
# time.
#
# + ctx - The agent context
# + activitySpec - The declared activity
# + return - An error when registration fails
isolated function registerDeclaredActivity(AgentContext ctx, DurableAgentActivitySpec activitySpec)
        returns error? {
    string? description = ();
    boolean requiresApproval = false;
    AutoRetry|ManualRetry|NoRetry retryPolicy = NoRetry;
    json meta = activitySpec.meta;
    if meta is map<json> {
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
        json approvalJson = meta["requiresApproval"];
        if approvalJson is boolean {
            requiresApproval = approvalJson;
        }
        json retryJson = meta["retryPolicy"];
        if retryJson is string {
            retryPolicy = retryJson;
        } else if retryJson is json[] {
            retryPolicy = check retryJson.cloneWithType(ManualRetry);
        } else if retryJson is map<json> {
            retryPolicy = check retryJson.cloneWithType(AutoRetry);
        }
    }
    check ctx.registerActivity(activitySpec.activity, activitySpec.toolName, description,
        (), requiresApproval, retryPolicy);
}

# Registers one declared peer agent on the runner's context, converting the
# declaration metadata (description, wait, callbackChannel, gating).
#
# + ctx - The agent context
# + peerSpec - The declared peer
# + return - An error when registration fails
isolated function registerDeclaredPeer(AgentContext ctx, DurableAgentPeerSpec peerSpec)
        returns error? {
    string? description = ();
    boolean waitForReply = true;
    string? callbackChannel = ();
    boolean requiresApproval = false;
    json meta = peerSpec.meta;
    if meta is map<json> {
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
        json waitJson = meta["wait"];
        if waitJson is boolean {
            waitForReply = waitJson;
        }
        json channelJson = meta["callbackChannel"];
        if channelJson is string {
            callbackChannel = channelJson;
        }
        json approvalJson = meta["requiresApproval"];
        if approvalJson is boolean {
            requiresApproval = approvalJson;
        }
    }
    check ctx.registerPeerAgent(peerSpec.name, peerSpec.targetAgent, description,
        waitForReply, callbackChannel, requiresApproval);
}

# Registers one declared human task capability on the runner's context.
#
# + ctx - The agent context
# + taskSpec - The declared human task
# + return - An error when registration fails
isolated function registerDeclaredHumanTask(AgentContext ctx, DurableAgentHumanTaskSpec taskSpec)
        returns error? {
    string|string[] roles = "manager";
    string? title = ();
    string? description = ();
    json meta = taskSpec.meta;
    if meta is map<json> {
        json rolesJson = meta["roles"];
        if rolesJson is string {
            roles = rolesJson;
        } else if rolesJson is json[] {
            roles = check rolesJson.cloneWithType();
        }
        json titleJson = meta["title"];
        if titleJson is string {
            title = titleJson;
        }
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
    }
    check ctx.registerHumanTask(taskSpec.name, roles, taskSpec.resultType, title, description, ());
}

# Dispatches one model-requested peer delegation. The peer runs as a true Temporal
# child workflow of this agent. Synchronous peers ("peeragent:<target>") suspend
# durably for the peer's final response; asynchronous peers
# ("peeragent:<target>#<channel>") return immediately with a correlation id, and a
# detached wait injects the peer's reply into the declared callback event channel.
#
# + ctxHandle - The agent context handle
# + peerSpec - The encoded target ("<target>" or "<target>#<callbackChannel>")
# + args - The model's tool-call arguments ({query})
# + return - The peer's response (sync), a dispatch acknowledgement (async), or an error
isolated function dispatchPeerAgent(handle ctxHandle, string peerSpec, map<anydata> args)
        returns anydata|error {
    int? separator = peerSpec.indexOf("#");
    string targetAgent = separator is int ? peerSpec.substring(0, separator) : peerSpec;
    string? callbackChannel = separator is int ? peerSpec.substring(separator + 1) : ();

    anydata queryArg = args["query"];
    string query = queryArg is string ? queryArg : args.toJson().toJsonString();

    string childId = check runPeerAgent(targetAgent, query);
    if callbackChannel is () {
        return waitForPeerAgentResult(childId);
    }
    check armPeerAgentCallback(ctxHandle, childId, callbackChannel);
    return "Delegated to peer agent '" + targetAgent + "' asynchronously (correlation id "
        + childId + "). Its reply will arrive as the '" + callbackChannel
        + "' event - wait for that event when you need the result.";
}

isolated function runPeerAgent(string targetAgent, string query) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "runPeerAgent"
} external;

isolated function waitForPeerAgentResult(string childId) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "waitForPeerAgentResult"
} external;

isolated function armPeerAgentCallback(handle ctxHandle, string childId, string callbackChannel)
        returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "armPeerAgentCallback"
} external;
