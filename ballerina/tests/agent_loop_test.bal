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

// ============================================================================
// Durable agent (imperative AgentContext) unit tests (IN_MEMORY mode)
// ============================================================================
//
// The compiler plugin doesn't run on the workflow package itself, so these tests
// register agents with `wfInternal:registerWorkflow` using the tools + built-in
// activities map (mirroring the init code the plugin generates for user code).
// The agent bodies use the real imperative API (ctx.registerActivity +
// ctx.buildAndRun). The LLM is a scripted mock ai:ModelProvider; the full
// durable loop runs against the embedded Temporal test server. Agents return no
// value, so the final answer is observed via the recorded final response.
// ============================================================================

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

// ── Scripted mock model providers ────────────────────────────────────────────

isolated client class MockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages !is ai:ChatMessage[] {
            return {role: ai:ASSISTANT, content: "unexpected single message"};
        }
        string item = "laptop";
        foreach ai:ChatMessage message in messages {
            if message is ai:ChatFunctionMessage && message.name == "checkStock" {
                string? content = message.content;
                return {role: ai:ASSISTANT, content: "Stock check result: " + (content ?: "")};
            }
            if message is ai:ChatUserMessage {
                string|ai:Prompt content = message.content;
                if content is string && content.includes("fail") {
                    item = "fail";
                }
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [{name: "checkStock", arguments: {"item": item}, id: "call-1"}]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final MockModelProvider mockAgentModel = new;

// Turn driver for the object-model event API: sendEvent/waitForEventResult key on the
// instance id, so one driver object exercises turns against any agent instance. Replaces
// the removed workflow:updateAgent in these loop tests.
final DurableAgent agentTurnDriver = check new ({
    systemPrompt: {role: "driver", instructions: "unused"},
    model: mockAgentModel
});

isolated function updateAgentTurn(string agentId, string eventName, anydata data)
        returns string|error {
    string token = check agentTurnDriver.sendEvent(agentId, eventName, data);
    return agentTurnDriver.waitForEventResult(agentId, token);
}

isolated client class LoopingMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        return {role: ai:ASSISTANT, toolCalls: [{name: "checkStock", arguments: {"item": "loop"}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final LoopingMockModelProvider loopingAgentModel = new;

isolated client class UnknownToolMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: content ?: "no tool output"};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "noSuchTool", arguments: {}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final UnknownToolMockModelProvider unknownToolAgentModel = new;

// Retrieves the recorded final response of a completed agent.
isolated function getAgentFinalResponse(string workflowId) returns string? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentResponseStore",
    name: "getFinalResponse"
} external;

// ── Agent tools (activities) ─────────────────────────────────────────────────

@Activity
isolated function checkStock(string item) returns string|error {
    if item == "fail" {
        return error("Inventory service unavailable for: " + item);
    }
    return item + " is in stock";
}

// ── Agent functions (imperative) ──────────────────────────────────────────────

type AgentOrderInput record {|
    string id;
    string request;
|};

function stockAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "You are an inventory assistant."},
            model = mockAgentModel);
}

function chatStockAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.registerUpdateEvents("chat", string);
    // No initial prompt: the agent waits for one chat event.
    check ctx.buildAndRun(systemPrompt = {role: "", instructions: "You are an inventory assistant."},
            model = mockAgentModel);
}

function loopingAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Looping agent."},
            model = loopingAgentModel,
            maxIter = 2);
}

function unknownToolAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Unknown tool agent."},
            model = unknownToolAgentModel);
}

// ── AI tool (registerTools / executeAgentTool wrapper) ──────────────────────

@ai:AgentTool
isolated function lookupPrice(string item) returns string {
    return item + " costs $999";
}

isolated client class AiToolMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "lookupPrice" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Price info: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "lookupPrice", arguments: {"item": "laptop"}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final AiToolMockModelProvider aiToolAgentModel = new;

function priceAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerAgentTool(lookupPrice);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "You are a pricing assistant."},
            model = aiToolAgentModel);
}

// ── Human task as an agent tool (registerHumanTask) ─────────────────────────

type ApprovalResult record {|
    boolean approved;
    string comment;
|};

isolated client class HumanTaskMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "approveOrder" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Approval outcome: " + (content ?: "")};
                }
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [{name: "approveOrder", arguments: {"orderId": "ORD-9", "reason": "high value"}}]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final HumanTaskMockModelProvider humanTaskAgentModel = new;

function approvalAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.registerHumanTask("approveOrder", "APPROVER", ApprovalResult,
            title = "Approve order", description = "Ask a person to approve the order.");
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "You are an approval assistant."},
            model = humanTaskAgentModel);
}

// ── Data event as an agent wait-tool ─────────────────────────────────────────

isolated client class EventToolMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "awaitEvent_approval" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Event outcome: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "awaitEvent_approval", arguments: {}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final EventToolMockModelProvider eventToolAgentModel = new;

function eventWaitingAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerActivity(checkStock);
    check ctx.registerUpdateEvents("approval", string);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "You wait for events."},
            model = eventToolAgentModel);
}

// ── Multi-turn conversation (MULTI_EVENT interaction) ───────────────────────

// Scripted conversation driven by the loop's framework-owned continuity: turn 1
// answers, later turns echo the latest user (chat) message, "bye" ends it.
isolated client class ConversationMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        // Framework-owned continuity: each chat message arrives as a user message and
        // the loop re-arms the chat wait after every answer — the mock never waits itself.
        string? lastChat = ();
        int userTurns = 0;
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatUserMessage {
                    userTurns += 1;
                    string|ai:Prompt content = message.content;
                    if content is string {
                        lastChat = content;
                    }
                }
            }
        }
        if userTurns <= 1 {
            return {role: ai:ASSISTANT, content: "Turn 1 answer"};
        }
        string chatText = lastChat ?: "";
        if chatText.includes("bye") {
            return {
                role: ai:ASSISTANT,
                content: "Conversation ended",
                toolCalls: [{name: "endConversation", arguments: {}}]
            };
        }
        if chatText.includes("json") {
            // Structured answer: updateAgent callers with a record-typed T parse this.
            return {role: ai:ASSISTANT, content: "{\"status\": \"ok\", \"count\": 2}"};
        }
        return {role: ai:ASSISTANT, content: "Echo: " + chatText};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ConversationMockModelProvider conversationAgentModel = new;

function conversationAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Chat with the user until they say bye."},
            model = conversationAgentModel, interaction = MULTI_EVENT, eventTimeout = {seconds: 60});
}

// MULTI_EVENT without the mandatory eventTimeout — must fail at registration.
function unsafeConversationAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "unsafe"},
            model = conversationAgentModel, interaction = MULTI_EVENT);
}

// Model that always waits — exercises the maxEventWaits safety cap.
function cappedConversationAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Chat forever."},
            model = conversationAgentModel, interaction = MULTI_EVENT, eventTimeout = {seconds: 60}, maxEventWaits = 2);
}

// ── Event wait timeout ───────────────────────────────────────────────────────

// Calls awaitEvent_approval once; on the timeout text, wraps up gracefully.
isolated client class TimeoutMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "awaitEvent_approval" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Wrapped up: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "awaitEvent_approval", arguments: {}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final TimeoutMockModelProvider timeoutAgentModel = new;

function timeoutAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("approval", string);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Wait for approval."},
            model = timeoutAgentModel, interaction = SINGLE_EVENT, eventTimeout = {seconds: 2});
}

// ── ai:Context-taking tool (via ai:executeTool delegation) ──────────────────

@ai:AgentTool
isolated function contextualLookup(ai:Context ctx, string item) returns string {
    return "ctx-tool saw: " + item;
}

isolated client class ContextToolMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "contextualLookup" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Ctx result: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "contextualLookup", arguments: {"item": "laptop"}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ContextToolMockModelProvider contextToolAgentModel = new;

function contextToolAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerAgentTool(contextualLookup);
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Use your tools."},
            model = contextToolAgentModel);
}

// ── BaseToolKit ──────────────────────────────────────────────────────────────

isolated class TestToolKit {
    *ai:BaseToolKit;

    public isolated function getTools() returns ai:ToolConfig[] {
        return ai:getToolConfigs([lookupPrice]);
    }
}

function toolkitAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerAgentTool(new TestToolKit());
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "You are a pricing assistant."},
            model = aiToolAgentModel);
}

// ── Human task timeout ───────────────────────────────────────────────────────

isolated client class SlowApprovalMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "slowApproval" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Task outcome: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "slowApproval", arguments: {"orderId": "ORD-1"}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final SlowApprovalMockModelProvider slowApprovalAgentModel = new;

function humanTaskTimeoutAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerHumanTask("slowApproval", "APPROVER", ApprovalResult, timeout = {seconds: 2});
    check ctx.buildAndRun(input.request,
            systemPrompt = {role: "", instructions: "Get approval."},
            model = slowApprovalAgentModel);
}

// ── Framework-owned conversation continuity (auto-continue) ─────────────────

// Answers content-only each turn (never calls the awaitEvent_chat wait-tool):
// the MULTI_EVENT loop itself must keep the conversation open. Ends explicitly
// via the built-in endConversation tool when the user says bye.
isolated client class AutoChatMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        string lastUser = "";
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatUserMessage {
                    string|ai:Prompt content = message.content;
                    if content is string {
                        lastUser = content;
                    }
                }
            }
        }
        if lastUser.includes("bye") {
            return {
                role: ai:ASSISTANT,
                toolCalls: [{name: "endConversation", arguments: {"farewell": "Ended by request"}}]
            };
        }
        return {role: ai:ASSISTANT, content: "Auto: " + lastUser};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final AutoChatMockModelProvider autoChatModel = new;

function autoConversationAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(systemPrompt = {role: "", instructions: "Answer briefly."}, model = autoChatModel, interaction = MULTI_EVENT, eventTimeout = {seconds: 60});
}

// Same behaviour with a short timeout: with no follow-up message the
// conversation must end gracefully on its own.
function shortTimeoutConversationAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(systemPrompt = {role: "", instructions: "Answer briefly."}, model = autoChatModel, interaction = MULTI_EVENT, eventTimeout = {seconds: 2});
}

// ── Update drain on completion ───────────────────────────────────────────────

// Answers the first consumed chat with a final response and explicitly ends the
// conversation, slowly — so an update queued mid-turn is never consumed and
// must be drained at completion.
isolated client class EndAfterFirstChatMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        runtime:sleep(3);
        return {role: ai:ASSISTANT, content: "done", toolCalls: [{name: "endConversation", arguments: {}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final EndAfterFirstChatMockModelProvider endAfterFirstChatModel = new;

function endingAgent(AgentContext ctx, AgentOrderInput input) returns error? {
    check ctx.registerUpdateEvents("chat", string);
    check ctx.buildAndRun(systemPrompt = {role: "", instructions: "End after the first reply."},
            model = endAfterFirstChatModel, interaction = MULTI_EVENT, eventTimeout = {seconds: 60});
}

// Plain (non-agent) workflow parked on an event — used to verify that
// updateAgent rejects non-agent workflows.
@Workflow
function parkedPlainWorkflow(Context ctx, AgentOrderInput input,
        record {| future<string> go; |} events) returns string|error {
    string signal = check wait events.go;
    return signal;
}

// ── Setup ────────────────────────────────────────────────────────────────────

@test:BeforeSuite
function setupAgentTests() returns error? {
    // Mirrors the init code the compiler plugin generates: tools discovered from
    // ctx.registerActivity plus the built-in llmChat/generate/executeAgentTool.
    map<function> agentActivities = {
        "checkStock": checkStock,
        "llmChat": llmChat,
        "generate": generate,
        "executeAgentTool": executeAgentTool
    };
    _ = check wfInternal:registerWorkflow(stockAgent, "stock-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(chatStockAgent, "chat-stock-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(loopingAgent, "looping-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(unknownToolAgent, "unknown-tool-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(priceAgent, "price-agent", agentActivities);
    _ = check wfInternal:registerAgentTool("price-agent", lookupPrice);
    _ = check wfInternal:registerWorkflow(approvalAgent, "approval-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(eventWaitingAgent, "event-waiting-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(conversationAgent, "conversation-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(unsafeConversationAgent, "unsafe-conversation-agent",
            agentActivities);
    _ = check wfInternal:registerWorkflow(cappedConversationAgent, "capped-conversation-agent",
            agentActivities);
    _ = check wfInternal:registerWorkflow(timeoutAgent, "timeout-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(contextToolAgent, "context-tool-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(toolkitAgent, "toolkit-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(humanTaskTimeoutAgent, "humantask-timeout-agent",
            agentActivities);
    _ = check wfInternal:registerWorkflow(parkedPlainWorkflow, "parked-plain-workflow");
    _ = check wfInternal:registerWorkflow(endingAgent, "ending-agent", agentActivities);
    _ = check wfInternal:registerWorkflow(autoConversationAgent, "auto-conversation-agent",
            agentActivities);
    _ = check wfInternal:registerWorkflow(shortTimeoutConversationAgent, "short-timeout-agent",
            agentActivities);
}

// Polls until the agent's latest recorded response equals `expected`.
function waitForAgentResponse(string workflowId, string expected) returns boolean {
    foreach int i in 0 ..< 40 {
        string?|error response = management:getAgentResponse(workflowId);
        if response is string && response == expected {
            return true;
        }
        runtime:sleep(0.5);
    }
    return false;
}

// ── Tests ────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testAgentToolRoundTrip() returns error? {
    map<anydata> input = {id: "agent-roundtrip-001", request: "Is the laptop in stock?"};
    string|error runResult = run(stockAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 30);
    test:assertEquals(getAgentFinalResponse(runResult), "Stock check result: laptop is in stock",
            "Agent should complete a full LLM -> tool -> LLM round trip");
}

@test:Config {groups: ["unit"]}
function testAgentToolErrorFedBackToModel() returns error? {
    map<anydata> input = {id: "agent-tool-error-001", request: "This one should fail"};
    string|error runResult = run(stockAgent, input);
    if runResult is error {
        return;
    }
    _ = check getWorkflowResult(runResult, 30);
    string? response = getAgentFinalResponse(runResult);
    test:assertTrue(response is string && response.includes("Inventory service unavailable"),
            "Tool errors should be fed back to the model as text, got: " + (response ?: "()"));
}

@test:Config {groups: ["unit"]}
function testAgentChatEventSeedsConversation() returns error? {
    map<anydata> input = {id: "agent-chat-001", request: "unused"};
    string|error runResult = run(chatStockAgent, input);
    if runResult is error {
        return;
    }
    // The agent has no initial prompt, so it durably waits for the chat event.
    check sendData(chatStockAgent, runResult, "chat", "Check availability of laptop");
    _ = check getWorkflowResult(runResult, 30);
    test:assertEquals(getAgentFinalResponse(runResult), "Stock check result: laptop is in stock",
            "Chat event should seed the agent conversation");
}

@test:Config {groups: ["unit"]}
function testAgentMaxIterationsExceeded() returns error? {
    map<anydata> input = {id: "agent-maxiter-001", request: "loop forever"};
    string|error runResult = run(loopingAgent, input);
    if runResult is error {
        return;
    }
    anydata|error result = getWorkflowResult(runResult, 30);
    test:assertTrue(result is error, "Looping agent should fail after maxIterations");
    if result is error {
        test:assertTrue(result.message().includes("maximum number of iterations"),
                "Error should mention the iteration limit: " + result.message());
    }
}

@test:Config {groups: ["unit"]}
function testAgentUnknownToolFedBackToModel() returns error? {
    map<anydata> input = {id: "agent-unknown-tool-001", request: "use a bad tool"};
    string|error runResult = run(unknownToolAgent, input);
    if runResult is error {
        return;
    }
    _ = check getWorkflowResult(runResult, 30);
    string? response = getAgentFinalResponse(runResult);
    test:assertTrue(response is string && response.includes("unknown tool 'noSuchTool'"),
            "Unknown tool errors should be fed back to the model as text, got: " + (response ?: "()"));
}

@test:Config {groups: ["unit"]}
function testAgentAiToolThroughWrapper() returns error? {
    // ai @ai:AgentTool functions registered via ctx.registerTools run through
    // the built-in executeAgentTool activity wrapper.
    map<anydata> input = {id: "agent-aitool-001", request: "How much is the laptop?"};
    string|error runResult = run(priceAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 30);
    test:assertEquals(getAgentFinalResponse(runResult), "Price info: laptop costs $999",
            "AI tool should execute durably through the executeAgentTool wrapper");
}

@test:Config {groups: ["unit"]}
function testAgentHumanTaskTool() returns error? {
    // When the agent invokes a registered human task, a human-task sub-workflow
    // starts and the agent suspends until a person completes it.
    map<anydata> input = {id: "agent-humantask-001", request: "Approve order ORD-9"};
    string|error runResult = run(approvalAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(2);

    string? taskId = ();
    management:HumanTaskGroup[]|error groups = management:listPendingHumanTasks(workflowId);
    if groups is management:HumanTaskGroup[] {
        foreach management:HumanTaskGroup g in groups {
            if g.taskIds.length() > 0 {
                taskId = g.taskIds[0];
                break;
            }
        }
    }
    if taskId is () {
        return; // Task not visible — skip.
    }

    ApprovalResult decision = {approved: true, comment: "Looks good"};
    check management:completeHumanTask(taskId, decision, ["APPROVER"]);

    _ = check getWorkflowResult(workflowId, 30);
    string? response = getAgentFinalResponse(workflowId);
    test:assertTrue(response is string && response.includes("\"approved\":true"),
            "Human task completion should flow back to the agent, got: " + (response ?: "()"));
}

@test:Config {groups: ["unit"]}
function testAgentEventWaitTool() returns error? {
    // Events declared in the agent signature are advertised as wait-tools; the
    // agent suspends durably until the event arrives.
    map<anydata> input = {id: "agent-event-tool-001", request: "Wait for the approval event"};
    string|error runResult = run(eventWaitingAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(2);

    check sendData(eventWaitingAgent, workflowId, "approval", "approved-by-manager");

    _ = check getWorkflowResult(workflowId, 30);
    test:assertEquals(getAgentFinalResponse(workflowId), "Event outcome: approved-by-manager",
            "Event data should flow back to the agent as the wait-tool result");
}

@test:Config {groups: ["unit"]}
function testAgentMultiTurnConversation() returns error? {
    // MULTI_EVENT: the model answers and re-arms the chat wait each turn until
    // the user says bye. Each turn's answer is observable via getAgentResponse
    // while the agent is still running.
    map<anydata> input = {id: "agent-conversation-001", request: "hello"};
    string|error runResult = run(conversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string workflowId = runResult;

    test:assertTrue(waitForAgentResponse(workflowId, "Turn 1 answer"),
            "Turn 1 answer should be recorded while the agent waits for chat");

    check sendData(conversationAgent, workflowId, "chat", "how are you");
    test:assertTrue(waitForAgentResponse(workflowId, "Echo: how are you"),
            "Turn 2 should consume the next chat message (FIFO re-armed event)");

    check sendData(conversationAgent, workflowId, "chat", "ok bye");
    _ = check getWorkflowResult(workflowId, 30);
    test:assertEquals(getAgentFinalResponse(workflowId), "Conversation ended",
            "The model ends the conversation by answering without waiting");
}

@test:Config {groups: ["unit"]}
function testMultiEventRequiresTimeout() returns error? {
    // MULTI_EVENT without an eventTimeout must fail at registration (safety).
    map<anydata> input = {id: "agent-unsafe-conv-001", request: "hello"};
    string|error runResult = run(unsafeConversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    anydata|error result = getWorkflowResult(runResult, 30);
    test:assertTrue(result is error, "MULTI_EVENT without eventTimeout should fail the agent");
    if result is error {
        test:assertTrue(result.message().includes("eventTimeout"),
                "Error should mention the missing eventTimeout: " + result.message());
    }
}

@test:Config {groups: ["unit"]}
function testAgentMaxEventWaitsCap() returns error? {
    // The model waits forever; the maxEventWaits cap must end the agent.
    map<anydata> input = {id: "agent-capped-conv-001", request: "hello"};
    string|error runResult = run(cappedConversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(2);
    check sendData(cappedConversationAgent, workflowId, "chat", "turn two");
    runtime:sleep(1);
    check sendData(cappedConversationAgent, workflowId, "chat", "turn three");

    anydata|error result = getWorkflowResult(workflowId, 30);
    test:assertTrue(result is error, "Exceeding maxEventWaits should fail the agent");
    if result is error {
        test:assertTrue(result.message().includes("event waits"),
                "Error should mention the event-wait limit: " + result.message());
    }
}

@test:Config {groups: ["unit"]}
function testAgentEventWaitTimeout() returns error? {
    // No approval event is ever sent; the wait times out and the timeout text is
    // fed back to the model, which wraps up gracefully.
    map<anydata> input = {id: "agent-event-timeout-001", request: "Wait for approval"};
    string|error runResult = run(timeoutAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 30);
    string? response = getAgentFinalResponse(runResult);
    test:assertTrue(response is string && response.includes("Timed out waiting for event 'approval'"),
            "Timeout text should be fed back to the model, got: " + (response ?: "()"));
}

@test:Config {groups: ["unit"]}
function testAgentContextTakingTool() returns error? {
    // Tools with an ai:Context first parameter execute via ai:executeTool, which
    // injects the context automatically.
    map<anydata> input = {id: "agent-ctx-tool-001", request: "Look up the laptop"};
    string|error runResult = run(contextToolAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 30);
    test:assertEquals(getAgentFinalResponse(runResult), "Ctx result: ctx-tool saw: laptop",
            "ai:Context-taking tools should execute through ai:executeTool");
}

@test:Config {groups: ["unit"]}
function testAgentToolkitTools() returns error? {
    // BaseToolKit implementations expand into their ToolConfigs.
    map<anydata> input = {id: "agent-toolkit-001", request: "How much is the laptop?"};
    string|error runResult = run(toolkitAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 30);
    test:assertEquals(getAgentFinalResponse(runResult), "Price info: laptop costs $999",
            "Toolkit tools should register and execute like other AI tools");
}

@test:Config {groups: ["unit"]}
function testAgentUpdateConversation() returns error? {
    // updateAgent is the request-response counterpart of sendData: each call
    // delivers the message and returns the answer of the turn that consumed it —
    // no polling required.
    map<anydata> input = {id: "agent-update-conv-001", request: "hello"};
    string|error runResult = run(conversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string agentId = runResult;

    string reply1 = check updateAgentTurn(agentId, "chat", "how are you");
    test:assertEquals(reply1, "Echo: how are you",
            "updateAgent should return the answer of the turn that consumed the request");

    string reply2 = check updateAgentTurn(agentId, "chat", "ok bye");
    test:assertEquals(reply2, "Conversation ended",
            "The final answer should complete the last update");

    _ = check getWorkflowResult(agentId, 30);
}

type UpdateStatus record {|
    string status;
    int count;
|};

@test:Config {groups: ["unit"]}
function testAgentUpdateStructuredResponse() returns error? {
    // With a record-typed T, the agent's textual answer is parsed as JSON and
    // coerced via updateAgent's dependently-typed return.
    map<anydata> input = {id: "agent-update-struct-001", request: "hello"};
    string|error runResult = run(conversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string agentId = runResult;

    string statusJson = check updateAgentTurn(agentId, "chat", "give me json");
    UpdateStatus status = check statusJson.fromJsonStringWithType();
    test:assertEquals(status, <UpdateStatus>{status: "ok", count: 2},
            "Structured T should parse the agent's JSON answer");

    string finalReply = check updateAgentTurn(agentId, "chat", "ok bye");
    test:assertEquals(finalReply, "Conversation ended");
    _ = check getWorkflowResult(agentId, 30);
}

isolated function updateEndingAgent(string agentId, string message) returns string|error {
    return updateAgentTurn(agentId, "chat", message);
}

@test:Config {groups: ["unit"]}
function testAgentAutoContinuesConversation() returns error? {
    // The model never calls the awaitEvent_chat wait-tool — under MULTI_EVENT the
    // loop itself keeps the conversation open after each answer. Ending happens
    // explicitly via the built-in endConversation tool.
    map<anydata> input = {id: "agent-auto-conv-001", request: "unused"};
    string|error runResult = run(autoConversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string agentId = runResult;

    string reply1 = check updateAgentTurn(agentId, "chat", "first");
    test:assertEquals(reply1, "Auto: first",
            "The loop should answer turn 1 without a model wait-tool call");

    string reply2 = check updateAgentTurn(agentId, "chat", "second");
    test:assertEquals(reply2, "Auto: second",
            "The loop should auto-continue and answer turn 2");

    string reply3 = check updateAgentTurn(agentId, "chat", "ok bye");
    test:assertEquals(reply3, "Ended by request",
            "endConversation's farewell should become the final response");

    _ = check getWorkflowResult(agentId, 30);
}

@test:Config {groups: ["unit"]}
function testAgentConversationEndsOnTimeout() returns error? {
    // No follow-up message after turn 1: the event timeout ends the conversation
    // gracefully (workflow completes without error, keeping the last answer).
    map<anydata> input = {id: "agent-timeout-conv-001", request: "unused"};
    string|error runResult = run(shortTimeoutConversationAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string agentId = runResult;

    string reply = check updateAgentTurn(agentId, "chat", "hello");
    test:assertEquals(reply, "Auto: hello");

    // The 2s wait for the next message elapses — the agent must complete cleanly.
    _ = check getWorkflowResult(agentId, 30);
    test:assertEquals(getAgentFinalResponse(agentId), "Auto: hello",
            "The last turn's answer remains the final response after a timeout end");
}

@test:Config {groups: ["unit"]}
function testAgentDrainsPendingUpdatesOnCompletion() returns error? {
    // The agent answers the first update and finishes WITHOUT re-arming the chat
    // wait, while a second update is still queued. Instead of failing with
    // "workflow completed before the update completed", the unconsumed update is
    // drained with the agent's final response.
    map<anydata> input = {id: "agent-drain-001", request: "unused"};
    string|error runResult = run(endingAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string agentId = runResult;
    runtime:sleep(1);

    future<string|error> first = start updateEndingAgent(agentId, "first message");
    runtime:sleep(1);
    future<string|error> second = start updateEndingAgent(agentId, "second message");

    string|error reply1 = wait first;
    string|error reply2 = wait second;
    test:assertEquals(check reply1, "done", "The consumed update should get the turn's answer");
    test:assertEquals(check reply2, "done",
            "Unconsumed updates should be drained with the agent's final response");
    _ = check getWorkflowResult(agentId, 30);
}

@test:Config {groups: ["unit"]}
function testUpdateAgentRejectsPlainWorkflow() returns error? {
    // updateAgent only works for durable agents; plain workflows bind data
    // imperatively, so there is no framework-owned response to correlate.
    map<anydata> input = {id: "update-plain-wf-001", request: "unused"};
    string|error runResult = run(parkedPlainWorkflow, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    string workflowId = runResult;
    runtime:sleep(1);

    string|error result = updateAgentTurn(workflowId, "go", "ping");
    test:assertTrue(result is error, "updateAgent on a plain workflow should fail");
    if result is error {
        test:assertTrue(result.message().includes("DurableAgent"),
                "Error should mention agents-only support: " + result.message());
    }

    // Release the parked workflow.
    check sendData(parkedPlainWorkflow, workflowId, "go", "done");
    _ = check getWorkflowResult(workflowId, 30);
}

@test:Config {groups: ["unit"]}
function testAgentHumanTaskTimeout() returns error? {
    // Nobody completes the task; the timeout error is fed back to the model.
    map<anydata> input = {id: "agent-ht-timeout-001", request: "Get approval for ORD-1"};
    string|error runResult = run(humanTaskTimeoutAgent, input);
    if runResult is error {
        return; // No workflow server available — skip.
    }
    _ = check getWorkflowResult(runResult, 60);
    string? response = getAgentFinalResponse(runResult);
    test:assertTrue(response is string && response.includes("timed out"),
            "Human-task timeout should be fed back to the model, got: " + (response ?: "()"));
}
