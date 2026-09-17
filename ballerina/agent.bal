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

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/time;

// ============================================================================
// anydata mirrors of the ballerina/ai chat message types.
//
// `ai:ChatMessage` itself is not `anydata` because user/system message content
// may be an `ai:Prompt` object. Agent workflows only ever exchange string
// content, so the conversation history is kept in these plain-data mirrors —
// making it replay-safe workflow state that can cross the activity boundary —
// and converted to `ai:` types inside the `llmChat` activity.
// ============================================================================

// System message in an agent conversation.
type AgentSystemMessage record {|
    # Role of the message
    "system" role = "system";
    # Content of the message
    string content;
|};

// User message in an agent conversation.
type AgentUserMessage record {|
    # Role of the message
    "user" role = "user";
    # Content of the message
    string content;
|};

// A tool invocation requested by the model.
type AgentFunctionCall record {|
    # Name of the tool function
    string name;
    # Arguments to pass to the tool, keyed by parameter name
    map<json>? arguments = {};
    # Identifier for the tool call
    string id?;
|};

// Assistant (model) message in an agent conversation.
type AgentAssistantMessage record {|
    # Role of the message
    "assistant" role = "assistant";
    # Text content; nil when the model requested tool calls instead
    string? content = ();
    # An optional name for the participant
    string name?;
    # Tool calls requested by the model
    AgentFunctionCall[]? toolCalls = ();
|};

// Tool result message in an agent conversation.
type AgentFunctionMessage record {|
    # Role of the message
    "function" role = "function";
    # Name of the tool that produced this result
    string name;
    # Tool output serialized as a string
    string? content = ();
    # Identifier correlating this result to the tool call
    string id?;
|};

// Any message in an agent conversation.
type AgentChatMessage AgentSystemMessage|AgentUserMessage|AgentAssistantMessage|AgentFunctionMessage;

# Runs the durable agent ReAct loop. Called from the object-model runner once every
# capability is registered on the context; not intended to be called directly.
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
# + systemPrompt - The agent's identity: role and instructions
# + maxIter - Reasoning iterations allowed per conversation turn
# + prompt - The initial user prompt, or "" to wait for the first chat event
# + toolDefs - The registered tool definitions (with dispatch kinds)
# + return - An error if the agent fails, otherwise nil
isolated function runAgentLoop(handle ctxHandle, string agentName, ai:SystemPrompt systemPrompt, int maxIter,
        string prompt, AgentToolDef[] toolDefs) returns error? {
    map<string> toolKinds = {};
    map<boolean> toolGated = {};
    boolean conversational = false;
    boolean hasChatEvent = false;
    foreach AgentToolDef def in toolDefs {
        toolKinds[def.name] = def.kind;
        toolGated[def.name] = def.gated;
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
    string role = systemPrompt.role.trim();
    string instructions = systemPrompt.instructions;
    string systemContent = role == "" ? instructions : string `${role} ${instructions}`;
    AgentChatMessage[] history = [<AgentSystemMessage>{content: systemContent}];
    if prompt != "" {
        history.push(<AgentUserMessage>{content: prompt});
    } else {
        // No initial prompt: wait durably for one chat event, if the agent
        // declared one in its signature.
        publishTranscript(ctxHandle, history);
        string? chatMessage = check awaitAgentChatEvent(ctxHandle);
        if chatMessage is string {
            history.push(<AgentUserMessage>{content: chatMessage});
        }
    }
    publishTranscript(ctxHandle, history);

    int maxIterations = int:max(1, maxIter);
    while true {
        // One conversation turn: a bounded ReAct loop over LLM + tool calls.
        boolean turnAnswered = false;
        foreach int _ in 0 ..< maxIterations {
            // Whatever side turns answered while the loop was parked joins the history
            // first, so the main conversation sees everything that was said. This point —
            // after any prior tool results, before the next model call — is the one place
            // an insertion can never split a tool call from its results.
            check mergeAsides(ctxHandle, history);
            AgentAssistantMessage assistant = check callAgentActivity("llmChat",
                    {"agentName": agentName, "messages": history.toJson(), "tools": llmToolDefs.toJson()});
            history.push(assistant);
            publishTranscript(ctxHandle, history);

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
        publishTranscript(ctxHandle, history);
    }
}

// Publishes the clean conversation view — system, user, and content-bearing assistant
// messages — that a side turn reasons over while the loop is parked. Tool calls and
// their results are deliberately absent: the park note carries the current state, and
// a transcript cut mid-tool-exchange would hand the side model an unanswered call.
isolated function publishTranscript(handle ctxHandle, AgentChatMessage[] history) {
    AgentChatMessage[] transcript = [];
    foreach AgentChatMessage message in history {
        if message is AgentSystemMessage|AgentUserMessage {
            transcript.push(message);
        } else if message is AgentAssistantMessage {
            string? content = message.content;
            if content is string && content != "" {
                transcript.push(<AgentAssistantMessage>{content: content});
            }
        }
    }
    publishAgentTranscript(ctxHandle, transcript.toJson());
}

// Merges the question/answer pairs side turns answered while the loop was parked into
// the history, verbatim, so the model knows what was already said on its behalf.
isolated function mergeAsides(handle ctxHandle, AgentChatMessage[] history) returns error? {
    string asidesJson = drainAgentAsides(ctxHandle);
    json parsed = check asidesJson.fromJsonString();
    if parsed !is json[] || parsed.length() == 0 {
        return;
    }
    foreach json aside in parsed {
        if aside !is map<json> {
            continue;
        }
        json question = aside["question"];
        json answer = aside["answer"];
        if question is string {
            history.push(<AgentUserMessage>{content: question});
        }
        if answer is string {
            history.push(<AgentAssistantMessage>{content: answer});
        }
    }
    publishTranscript(ctxHandle, history);
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
    if kind == "sleep" {
        // Durable sleep: a workflow-side timer, never an activity. There is no upper
        // bound - durable timers are exactly for long pauses, and a management-API wake
        // signal can end the sleep early at any time.
        int seconds = 0;
        anydata rawSeconds = args["seconds"];
        if rawSeconds is int {
            seconds = rawSeconds;
        } else if rawSeconds is float|decimal {
            seconds = <int>rawSeconds;
        }
        if seconds <= 0 {
            return "Error: sleep requires a positive 'seconds' argument.";
        }
        boolean completed = check agentInterruptibleSleep(ctxHandle, seconds * 1000);
        if completed {
            return string `Slept for ${seconds} seconds.`;
        }
        return string `Sleep was interrupted by a wake signal before the ${seconds} seconds elapsed.`;
    }

    // Workflow-context reads: what a plain workflow gets from ctx (getWorkflowId,
    // currentTime) the agent gets as always-available built-in tools. Both are
    // deterministic workflow-thread reads - answered in place, never an activity.
    if kind == "workflowid" {
        return agentWorkflowId(ctxHandle);
    }
    if kind == "currenttime" {
        int millis = agentCurrentTimeMillis(ctxHandle);
        time:Utc utc = [millis / 1000, <decimal>(millis % 1000) / 1000d];
        return time:utcToString(utc);
    }

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
        // The max-event-waits safety cap is an infrastructure failure that must end the
        // agent — never feed it back to the model as tool output.
        if result is error && result.message().startsWith("Agent exceeded the maximum number of event waits") {
            return result;
        }
    } else if kind.startsWith("peeragent:") {
        // Delegates to a peer durable agent running as a true Temporal child workflow.
        result = dispatchPeerAgent(ctxHandle, kind.substring(10), args);
    } else if kind.startsWith("peerevent:") {
        // Sends one of the peer's events to its companion instance; a duplex event returns the answer.
        int split = <int>kind.lastIndexOf(":");
        result = sendPeerEvent(kind.substring(10, split), kind.substring(split + 1), args);
    } else if kind == "collect" {
        anydata correlationId = args["correlationId"];
        result = correlationId is string ? collectPeerResult(correlationId)
            : error("collectPeerResult needs the correlation id a delegation returned");
        if result is AgentBusyError {
            return "The peer is still working; collect its result later.";
        }
    } else if kind == "replycaller" {
        anydata message = args["message"];
        result = replyToCaller(ctxHandle, message is string ? message : args.toJson().toJsonString());
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
isolated function executeAgentTool(string agentName, string toolName, json arguments)
        returns anydata|error {
    ai:FunctionTool fn = check getAgentToolFunction(agentName, toolName);
    // Normalize the payload-decoded arguments: after the Temporal round trip the value's
    // inherent type may be a plain anydata map, which fails an `is map<json>` test and
    // would silently drop every argument.
    map<json> args = {};
    json normalizedArgs = arguments.toJson();
    if normalizedArgs is map<json> {
        args = normalizedArgs;
    }
    // An MCP tool's caller takes a single `mcp:CallToolParams` argument; wrap the
    // model's arguments the same way the ai module's own tool store does.
    if isAgentMcpTool(agentName, toolName) {
        args = {params: {name: toolName, arguments: args}};
    }
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

// Durable, wake-interruptible sleep on the workflow thread (a Temporal timer); returns
// true when the timer ran to completion, false when a management wake signal ended it.
isolated function agentInterruptibleSleep(handle nativeContext, int millis) returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "agentInterruptibleSleep"
} external;

// The run's workflow instance ID, read deterministically on the workflow thread
// (backs the built-in getWorkflowId tool).
isolated function agentWorkflowId(handle nativeContext) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "getWorkflowId"
} external;

// The deterministic workflow clock in epoch milliseconds (backs the built-in
// getCurrentTime tool).
isolated function agentCurrentTimeMillis(handle nativeContext) returns int = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "currentTimeMillis"
} external;

// Whether the registered tool is an MCP tool (its caller takes mcp:CallToolParams).
isolated function isAgentMcpTool(string agentName, string toolName) returns boolean = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "isAgentMcpTool"
} external;

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
isolated function llmChat(string agentName, json messages, json tools)
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
isolated function generate(string agentName, string query) returns anydata|error {
    ai:ModelProvider model = check getAgentModel(agentName);
    ai:Prompt prompt = `${query}`;
    anydata result = check model->generate(prompt);
    return result;
}

# Built-in agent activity producing the agent's declared typed final result: one more
# model call converts the concluded conversation into the declaration's `resultType`.
# Runs on the worker, where the declaration registry resolves the target typedesc.
#
# + agentName - The agent's workflow type; keys the registered model provider
# + declaredAgent - The agent's declaration name; keys the run spec (result type)
# + query - The generation prompt carrying the conversation outcome
# + return - The generated value of the declared result type, or an error
@Activity
isolated function generateResult(string agentName, string declaredAgent, string query)
        returns anydata|error {
    ai:ModelProvider model = check getAgentModel(agentName);
    DurableAgentRunSpec spec = check getDurableAgentRunSpec(declaredAgent);
    typedesc<anydata> resultType = spec.resultType ?: anydata;
    ai:Prompt prompt = `${query}`;
    anydata result = check model->generate(prompt, resultType);
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

// Publishes the conversation transcript side turns reason over while the loop is parked.
isolated function publishAgentTranscript(handle nativeContext, json transcript) = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "publishTranscript"
} external;

// Drains the side-turn question/answer pairs recorded while the loop was parked,
// as a JSON array of {question, answer}.
isolated function drainAgentAsides(handle nativeContext) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "drainAsides"
} external;

// Looks up the model provider registered for an agent workflow type.
isolated function getAgentModel(string agentName) returns ai:ModelProvider|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "getAgentModel"
} external;

// ============================================================================
// Agent capability registration (internal)
//
// A durable agent's capabilities are registered on its native context handle by
// the object-model runner below (`runDurableAgentObject`) before the ReAct loop
// starts; there is no user-facing imperative agent API.
// ============================================================================

// Hard cap on event waits per run: the backstop for a conversation that never ends.
const int MAX_EVENT_WAITS = 50;

// Internal shape of a registered tool: the LLM-facing definition plus the
// dispatch kind ("activity", "aitool", "humantask", or "event:<name>") and
// whether its approvalPolicy gates it (a review activity is created before it runs).
type AgentToolDef record {|
    string name;
    string description;
    map<json> parameters?;
    string kind;
    boolean gated = false;
|};

# Registers an AI tool with the agent. Accepts an `ai:ToolConfig` value, a
# function annotated with `@ai:AgentTool` (normalized via the ai module's tool
# plumbing), or an `ai:BaseToolKit` implementation (expanded via its
# `getTools()`). When the agent invokes the tool, the call is executed durably
# through the built-in activity wrapper, delegating argument binding and
# `ai:Context` injection to `ai:executeTool`.
#
# + agentCtx - The native agent context handle
# + tool - The tool to register
# + approvalPolicy - A `ReviewTaskDefinition` gates every call behind a PRE_RUN review
# + return - An error if the tool cannot be registered (e.g. a function
#            missing the `@ai:AgentTool` annotation), otherwise nil
isolated function registerAgentTool(handle agentCtx, ai:BaseToolKit|ai:ToolConfig|ai:FunctionTool tool,
        ApprovalPolicy approvalPolicy = NoApproval, boolean mcpTool = false) returns error? {
    if tool is ai:BaseToolKit {
        // MCP toolkit callers take a single `mcp:CallToolParams` argument, so the
        // execution wrapper must know to wrap the model's arguments accordingly.
        boolean isMcp = tool is ai:McpBaseToolKit;
        foreach ai:ToolConfig config in tool.getTools() {
            check recordToolConfig(agentCtx, config, approvalPolicy, isMcp);
        }
    } else if tool is ai:ToolConfig {
        check recordToolConfig(agentCtx, tool, approvalPolicy, mcpTool);
    } else {
        ai:ToolConfig[] configs = ai:getToolConfigs([tool]);
        if configs.length() == 0 {
            return error("Agent tool functions must be annotated with @ai:AgentTool");
        }
        check recordToolConfig(agentCtx, configs[0], approvalPolicy, mcpTool);
    }
}

isolated function recordToolConfig(handle agentCtx, ai:ToolConfig config,
        ApprovalPolicy approvalPolicy = NoApproval, boolean mcpTool = false) returns error? {
    map<json>? parameters = config.parameters;
    return recordAiTool(agentCtx, config.caller, config.name, config.description,
            parameters is () ? () : parameters.toJsonString(), approvalPolicy, mcpTool);
}

// ============================================================================
// Native bindings for agent capability registration
// ============================================================================

isolated function recordActivityTool(handle nativeContext, function tool, string? name,
        string? description, map<anydata|object {}>? bindings, ApprovalPolicy approvalPolicy,
        RetryPolicy retryPolicy) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordActivityTool"
} external;

isolated function recordAiTool(handle nativeContext, function tool, string name, string description,
        string? parametersJson, ApprovalPolicy approvalPolicy, boolean mcpTool) returns error? = @java:Method {
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


isolated function recordHumanTaskTool(handle nativeContext, string taskName, string|string[]? userRoles,
        string|string[]? users, string|string[]? excludedUsers, string|string[]? excludedRoles,
        string|string[]? administratorRoles, string|string[]? administratorUsers, typedesc<anydata> resultType, string? title, string? description, Duration? timeout,
        typedesc<map<json>>? taskInputType) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordHumanTaskTool"
} external;

isolated function registerAgentUpdateEvent(handle nativeContext, string name, typedesc<anydata> requestType,
        typedesc<anydata>? responseType) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "registerUpdateEvent"
} external;

isolated function setAgentInteraction(handle nativeContext, string pattern, Duration? eventTimeout,
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

isolated function recordPeerTool(handle nativeContext, string name, string description,
        string kindSpec) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordPeerTool"
} external;

isolated function readAgentContextFinalResponse(handle contextHandle) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "getFinalResponse"
} external;

// ============================================================================
// Object-model durable agent runner
// ============================================================================

# Shared runner workflow for object-model durable agents. Registered by the
# compiler plugin as the workflow function of every `final workflow:DurableAgent`
# declaration (workflow type `workflow-<agentName>`), so the whole function-based
# agent substrate — adapter dispatch, model/tool registries, management views —
# works unchanged. The runner resolves the agent's declaration by name, registers
# its capabilities on the native agent context, hands control to the durable
# ReAct loop, and returns the agent's final
# response as the workflow result (so `getResult`/`waitForResult` read it from
# the instance).
#
# This is an internal function: it is handed to the native runtime once at
# workflow-module init (see `registerDurableAgentRunnerNatives` in module.bal),
# and `wfInternal:registerDurableAgentRunner(agentName)` wires it up per agent —
# generated user code never references it.
#
# + agentCtx - The native agent context handle (injected by the workflow adapter)
# + runInput - The run request: `{agentName, query, input}`
# + return - The agent's final response, or an error
isolated function runDurableAgentObject(handle agentCtx, map<anydata> runInput)
        returns anydata|error {
    string agentName = check runInput["agentName"].ensureType();
    string query = check runInput["query"].ensureType();
    anydata payload = runInput["input"];

    DurableAgentRunSpec spec = check getDurableAgentRunSpec(agentName);

    foreach DurableAgentActivitySpec activitySpec in spec.activities {
        check registerDeclaredActivity(agentCtx, activitySpec);
    }
    foreach DurableAgentToolSpec toolSpec in spec.tools {
        check registerDeclaredTool(agentCtx, toolSpec);
    }
    boolean multiEvent = false;
    foreach DurableAgentEventSpec eventSpec in spec.events {
        check registerAgentUpdateEvent(agentCtx, eventSpec.name, eventSpec.request, eventSpec.response);
        if eventSpec.cardinality == "MULTI_EVENT" {
            multiEvent = true;
        }
    }
    foreach DurableAgentHumanTaskSpec taskSpec in spec.humanTasks {
        check registerDeclaredHumanTask(agentCtx, taskSpec);
    }
    foreach DurableAgentPeerSpec peerSpec in spec.peers {
        check registerDeclaredPeer(agentCtx, peerSpec);
    }
    // A run delegated with a reply address gets a tool to answer that caller.
    anydata replyTo = runInput["replyTo"];
    if replyTo is map<anydata> {
        check recordReplyToCallerTool(agentCtx, replyTo);
    }

    ai:SystemPrompt systemPrompt = check spec.systemPrompt.cloneWithType();

    // Structured run input is surfaced to the model as part of the user turn.
    string effectiveQuery = payload is () ? query
        : query + "\n\nInput:\n" + payload.toJsonString();

    // Only a declared eventTimeout bounds the waits: an unbounded chat session is the
    // point of a durable agent, and a default here silently killed conversations that
    // idled past it. The declared maxEventWaits remains the runaway backstop.
    Duration? eventTimeout = ();
    json declaredTimeout = spec.eventTimeout;
    if declaredTimeout != () {
        eventTimeout = check declaredTimeout.cloneWithType();
    }
    check setAgentInteraction(agentCtx, multiEvent ? MULTI_EVENT : SINGLE_EVENT, eventTimeout, spec.maxEventWaits);
    setAgentModelProvider(agentCtx, spec.model);
    check registerAgentModelForContext(agentCtx);
    string toolDefsJson = check getAgentToolDefs(agentCtx);
    json toolDefs = check toolDefsJson.fromJsonString();
    AgentToolDef[] defs = check toolDefs.cloneWithType();
    error? loopResult = runAgentLoop(agentCtx, getAgentWorkflowType(agentCtx), systemPrompt, spec.maxIter,
            effectiveQuery, defs);
    // Settle any outstanding event turns before the workflow completes: unconsumed events
    // receive the agent's final response (or its failure) instead of failing as orphaned updates.
    finishAgentUpdates(agentCtx, loopResult is error ? loopResult.message() : ());
    if loopResult is error {
        return loopResult;
    }
    typedesc<anydata>? declaredResultType = spec.resultType;
    if declaredResultType is () {
        return readAgentContextFinalResponse(agentCtx);
    }
    // Declared result type: one more durable model call converts the concluded
    // conversation into the declared type; waitForResult/getResult return it.
    string finalText = readAgentContextFinalResponse(agentCtx);
    anydata typedResult = check callAgentActivity("generateResult", {
        "agentName": getAgentWorkflowType(agentCtx),
        "declaredAgent": agentName,
        "query": "Produce the final result of this conversation." +
            (finalText == "" ? "" : "\n\nConversation outcome:\n" + finalText)
    });
    return typedResult;
}

# Registers one declared activity capability on the runner's context, converting
# the declaration metadata (description, gating, reviewer roles, retry policy)
# captured at compile time.
#
# `ActivityDecl.bindings` does not travel this path: the declaration metadata is
# JSON and bound client objects are not serializable, so bindings are unsupported
# in the declaration form until activity binding support lands.
#
# + agentCtx - The native agent context handle
# + activitySpec - The declared activity
# + return - An error when registration fails
// Registers one declared AI tool on the runner's context. The declaration carries the
// normalized tool config (name/description/parameters) plus the ToolDecl gating fields;
// the config is rebuilt here rather than re-derived, because an `ai:ToolConfig` literal's
// caller need not be annotated with `@ai:AgentTool`.
isolated function registerDeclaredTool(handle agentCtx, DurableAgentToolSpec toolSpec) returns error? {
    string description = toolSpec.toolName;
    map<json>? parameters = ();
    ApprovalPolicy approvalPolicy = NoApproval;
    json meta = toolSpec.meta;
    if meta is map<json> {
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
        json parametersJson = meta["parameters"];
        if parametersJson is string {
            json parsed = check parametersJson.fromJsonString();
            parameters = check parsed.cloneWithType();
        }
        json approvalJson = meta["approvalPolicy"];
        if approvalJson is map<json> {
            approvalPolicy = check approvalJson.cloneWithType(ReviewTaskDefinition);
        }
    }
    boolean mcpTool = meta is map<json> && meta["isMcp"] == true;
    ai:FunctionTool caller = check toolSpec.tool.ensureType();
    ai:ToolConfig config = {
        name: toolSpec.toolName,
        description,
        parameters,
        caller
    };
    check registerAgentTool(agentCtx, config, approvalPolicy, mcpTool);
}

isolated function registerDeclaredActivity(handle agentCtx, DurableAgentActivitySpec activitySpec)
        returns error? {
    string? description = ();
    ApprovalPolicy approvalPolicy = NoApproval;
    RetryPolicy retryPolicy = NoRetry;
    json meta = activitySpec.meta;
    if meta is map<json> {
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
        json approvalJson = meta["approvalPolicy"];
        if approvalJson is map<json> {
            approvalPolicy = check approvalJson.cloneWithType(ReviewTaskDefinition);
        }
        json retryJson = meta["retryPolicy"];
        if retryJson is map<json> {
            retryPolicy = check retryPolicyOf(retryJson);
        }
    }
    check recordActivityTool(agentCtx, activitySpec.activity, activitySpec.toolName, description,
        activitySpec.bindings, approvalPolicy, retryPolicy);
}

// A review names an audience, AutoRetry names attempts, RetryBeforeReview names both.
final readonly & string[] REVIEW_ONLY_KEYS = ["userRoles", "users", "excludedUsers", "excludedRoles",
    "administratorRoles", "administratorUsers", "title", "description", "timeout"];

isolated function retryPolicyOf(map<json> retryJson) returns RetryPolicy|error {
    boolean review = retryJson["userRoles"] !is () || retryJson["users"] !is ();
    boolean retries = retryJson["maxRetries"] !is ();
    if !review && retryJson.keys().some(key => REVIEW_ONLY_KEYS.indexOf(key) !is ()) {
        return error("retryPolicy must name 'userRoles' or 'users' when it declares a review");
    }
    if review && retries {
        return check retryJson.cloneWithType(RetryBeforeReview);
    }
    if review {
        return check retryJson.cloneWithType(ReviewTaskDefinition);
    }
    return check retryJson.cloneWithType(AutoRetry);
}


# Registers one declared peer agent on the runner's context, converting the
# declaration metadata (description, wait, callbackChannel, gating).
#
# + agentCtx - The native agent context handle
# + peerSpec - The declared peer
# + return - An error when registration fails
isolated function registerDeclaredPeer(handle agentCtx, DurableAgentPeerSpec peerSpec)
        returns error? {
    string? description = ();
    json meta = peerSpec.meta;
    if meta is map<json> {
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
    }
    string[]? allowedEvents = ();
    if meta is map<json> {
        json allowedJson = meta["allowedEvents"];
        if allowedJson is json[] {
            allowedEvents = check allowedJson.cloneWithType();
        }
    }
    string target = peerSpec.targetAgent;
    string desc = description ?: ("Delegates a task or question to the peer durable agent '" + target + "'.");
    // The run entry keeps the peer's own name; each exposed event becomes `<peer>_<event>` (a model
    // tool name admits no dot) with the event's request type as its schema.
    check recordPeerTool(agentCtx, peerSpec.name, desc, "peeragent:" + target);
    string toolsJson = check peerEventTools(target, allowedEvents);
    json[] eventTools = check (check toolsJson.fromJsonString()).ensureType();
    foreach json tool in eventTools {
        string eventName = check tool.name;
        boolean duplex = check tool.duplex;
        string schema = check tool.schema;
        string eventDesc = (duplex ? "Sends '" : "Notifies '") + eventName + "' to peer '" + target + "'"
            + (duplex ? " and returns its answer." : "; the peer does not answer on this event.");
        check recordPeerEventTool(agentCtx, peerSpec.name + "_" + eventName, eventDesc,
            "peerevent:" + target + ":" + eventName, schema);
    }
}

# Registers one declared human task capability on the runner's context.
#
# + agentCtx - The native agent context handle
# + taskSpec - The declared human task
# + return - An error when registration fails
isolated function registerDeclaredHumanTask(handle agentCtx, DurableAgentHumanTaskSpec taskSpec)
        returns error? {
    string|string[]? roles = ();
    string|string[]? users = ();
    string|string[]? excludedUsers = ();
    string|string[]? excludedRoles = ();
    string|string[]? administratorRoles = ();
    string|string[]? administratorUsers = ();
    string? title = ();
    string? description = ();
    Duration? timeout = ();
    json meta = taskSpec.meta;
    if meta is map<json> {
        // `userRoles` is the one spelling across a workflow's task, an agent's task and a
        // review; `roles` is the pre-unification name of the same thing.
        roles = check namesOf(meta["userRoles"] is () ? meta["roles"] : meta["userRoles"]);
        users = check namesOf(meta["users"]);
        excludedUsers = check namesOf(meta["excludedUsers"]);
        excludedRoles = check namesOf(meta["excludedRoles"]);
        administratorRoles = check namesOf(meta["administratorRoles"]);
        administratorUsers = check namesOf(meta["administratorUsers"]);
        json titleJson = meta["title"];
        if titleJson is string {
            title = titleJson;
        }
        json descriptionJson = meta["description"];
        if descriptionJson is string {
            description = descriptionJson;
        }
        json timeoutJson = meta["timeout"];
        if timeoutJson is map<json> {
            timeout = check timeoutJson.cloneWithType();
        }
    }
    check recordHumanTaskTool(agentCtx, taskSpec.name, roles, users, excludedUsers, excludedRoles,
            administratorRoles, administratorUsers, taskSpec.resultType, title, description, timeout,
            taskSpec.taskInputType);
}

// A single name or a list of them from declaration metadata; `()` when absent.
isolated function namesOf(json names) returns string|string[]?|error {
    if names is string {
        return names;
    }
    if names is json[] {
        return check names.cloneWithType();
    }
    return ();
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
isolated function dispatchPeerAgent(handle ctxHandle, string targetAgent, map<anydata> args)
        returns anydata|error {
    anydata queryArg = args["query"];
    string query = queryArg is string ? queryArg : args.toJson().toJsonString();
    anydata waitArg = args["wait"];
    boolean waitForAnswer = waitArg is boolean ? waitArg : true;
    anydata replyEvent = args["replyEvent"];
    if replyEvent is string && replyEvent.trim().length() == 0 {
        return error("replyEvent must name one of this agent's events; it cannot be empty");
    }
    map<anydata>? replyTo = replyEvent is string
        ? {instanceId: check agentWorkflowId(ctxHandle), eventName: replyEvent} : ();

    string childId = check runPeerAgent(targetAgent, query, replyTo);
    if waitForAnswer && replyTo is () {
        return waitForPeerAgentResult(childId);
    }
    return "Delegated to peer agent '" + targetAgent + "' (correlation id " + childId + ")."
        + (replyTo is () ? " Call collectPeerResult with that id when you need the answer."
            : " It will answer on your '" + <string>replyEvent + "' event.");
}

isolated function runPeerAgent(string targetAgent, string query, map<anydata>? replyTo)
        returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "runPeerAgent"
} external;

isolated function peerEventTools(string targetAgent, string[]? allowedEvents) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "peerEventTools"
} external;

isolated function sendPeerEvent(string targetAgent, string eventName, map<anydata> payload)
        returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "sendPeerEvent"
} external;

isolated function collectPeerResult(string childId) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "collectPeerResult"
} external;

isolated function recordPeerEventTool(handle nativeContext, string name, string description, string kindSpec,
        string schemaJson) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordPeerEventTool"
} external;

isolated function recordReplyToCallerTool(handle nativeContext, map<anydata> replyTo) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "recordReplyToCallerTool"
} external;

isolated function replyToCaller(handle nativeContext, string message) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentContextNative",
    name: "replyToCaller"
} external;

isolated function waitForPeerAgentResult(string childId) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "waitForPeerAgentResult"
} external;


// Hands the object-model runner and the built-in agent activities to the native
// agent registry once, at workflow-module init. Generated user code then wires an
// agent with wfInternal:registerDurableAgentRunner(agentName) alone — none of the
// runner machinery appears in this module's public API.
function registerDurableAgentRunnerNatives() {
    setDurableAgentObjectRunner(runDurableAgentObject,
        {"llmChat": llmChat, "generate": generate, "generateResult": generateResult,
            "executeAgentTool": executeAgentTool});
}

isolated function setDurableAgentObjectRunner(function runner, map<function> builtinActivities) = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative",
    name: "setObjectRunner"
} external;
