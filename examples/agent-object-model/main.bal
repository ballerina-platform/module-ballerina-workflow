// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Object-model durable agent sample: the agent is declared once as a module-level
// `final workflow:DurableAgent` whose constructor config carries every capability.
// The compiler plugin generates the module-init registration from the config, and
// `run()` starts the agent's own workflow type on the shared object-model runner.
// The model is a scripted mock ai:ModelProvider so no credentials are needed.

import ballerina/ai;
import ballerina/io;
import ballerina/jballerina.java;
import ballerina/workflow;

// Scripted mock: the first turn requests a checkInventory tool call; once the tool
// result is visible in the conversation, the model answers and the run completes.
isolated client class ScriptedModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages !is ai:ChatMessage[] {
            return {role: ai:ASSISTANT, content: "unexpected single message"};
        }
        foreach ai:ChatMessage message in messages {
            if message is ai:ChatFunctionMessage && message.name == "checkInventory" {
                string? content = message.content;
                return {
                    role: ai:ASSISTANT,
                    content: "Inventory check finished: " + (content ?: "no result")
                };
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [{name: "checkInventory", arguments: {"item": "laptop"}, id: "call-1"}]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ScriptedModelProvider orderModel = new;

// Echo mock for the conversational agent: always answers the latest user turn.
isolated client class EchoModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages !is ai:ChatMessage[] {
            return {role: ai:ASSISTANT, content: "unexpected single message"};
        }
        string lastUserTurn = "";
        foreach ai:ChatMessage message in messages {
            if message is ai:ChatUserMessage {
                string|ai:Prompt content = message.content;
                if content is string {
                    lastUserTurn = content;
                }
            }
        }
        return {role: ai:ASSISTANT, content: "Echo: " + lastUserTurn};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final EchoModelProvider chatModel = new;

// A conversational agent: the MULTI_EVENT chat channel keeps the instance alive
// between turns; each sendEvent("chat", ...) is one turn answered via its token.
final workflow:DurableAgent chatAgent = check new ({
    systemPrompt: {role: "Chat assistant", instructions: "Echo the user's messages."},
    model: chatModel,
    events: [
        {name: "chat", request: string, response: string, cardinality: workflow:MULTI_EVENT}
    ],
    maxIter: 4
});

type EscalationReq record {|
    string reason;
|};

# Checks whether an item is in stock.
#
# + item - The item name
# + return - Whether the item is available, or an error
@workflow:Activity
function checkInventory(string item) returns boolean|error {
    return item.length() > 0;
}

# Reserves stock for an order.
#
# + item - The item name
# + quantity - Units to reserve
# + return - A reservation reference, or an error
@workflow:Activity
function reserveStock(string item, int quantity) returns string|error {
    return string `RES-${item}-${quantity}`;
}

# Looks up the unit price of an item.
#
# + item - The item name
# + return - The unit price, or an error
@ai:AgentTool
isolated function priceLookup(string item) returns decimal|error {
    return 10.5d;
}

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user place and track orders."},
    model: orderModel,
    activities: [
        checkInventory,
        {activity: reserveStock, name: "reserve", requiresApproval: true}
    ],
    tools: [priceLookup],
    events: [
        {name: "escalate", request: EscalationReq}
    ],
    humanTasks: [
        {name: "approval", roles: "manager", title: "Approve the order"}
    ],
    maxIter: 8
});

// Planner mock: first turn delegates to the inventory peer; once the peer's
// answer is visible as a tool result, the planner summarizes it.
isolated client class PlannerModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages !is ai:ChatMessage[] {
            return {role: ai:ASSISTANT, content: "unexpected single message"};
        }
        foreach ai:ChatMessage message in messages {
            if message is ai:ChatFunctionMessage && message.name == "askInventory" {
                string? content = message.content;
                return {
                    role: ai:ASSISTANT,
                    content: "Planner summary -> peer said: " + (content ?: "no reply")
                };
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [{name: "askInventory", arguments: {"query": "Are laptops in stock?"}, id: "call-1"}]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final PlannerModelProvider plannerModel = new;

// Model-driven agent-to-agent delegation: the peer is advertised to the planner's
// model as a tool; each delegation runs the peer agent as a true child workflow.
final workflow:DurableAgent plannerAgent = check new ({
    systemPrompt: {role: "Planner", instructions: "Plan orders by consulting peer agents."},
    model: plannerModel,
    peers: [
        {agent: orderAgent, name: "askInventory", description: "Asks the inventory agent about stock."}
    ],
    maxIter: 6
});

type OrchestratorInput record {|
    string id;
|};

# A plain workflow that composes durable agents: the sub-agents run as true
# Temporal child workflows (lifecycle tied to this parent), results are read with
# durable suspends, and conversational turns ride the reply-signal path — the
# only deterministic way to correlate an event turn from inside a workflow.
#
# + ctx - The workflow context
# + input - The orchestration input
# + return - The combined sub-agent results, or an error
@workflow:Workflow
function orchestratorWorkflow(workflow:Context ctx, OrchestratorInput input) returns string|error {
    // Imperative sub-agent: run() inside a workflow starts a child agent.
    string subAgentId = check orderAgent.run("Check whether laptops are in stock.");
    string subResult = check orderAgent.waitForResult(subAgentId);

    // Conversational sub-agent driven turn-by-turn via reply signals.
    string chatChildId = check chatAgent.run("Hello from the orchestrator");
    string turnToken = check chatAgent.sendEvent(chatChildId, "chat", "One turn from a workflow");
    string turnReply = check chatAgent.waitForEventResult(chatChildId, turnToken);

    return subResult + " | " + turnReply;
}

public function main() returns error? {
    io:println("agent-object-model: module init completed — the durable agent declaration "
            + "was registered with the workflow runtime.");

    // Start the agent durably: run() always returns the new instance id, never the
    // result — the agent may suspend for days on a human task without holding a thread.
    string instanceId = check orderAgent.run("Check whether laptops are in stock.");
    io:println("orderAgent.run() -> instance " + instanceId);

    // Non-blocking read: while the agent is still reasoning this returns AgentBusyError.
    string|error early = orderAgent.getResult(instanceId);
    if early is workflow:AgentBusyError {
        io:println("orderAgent.getResult() -> AgentBusyError (still working — as expected)");
    } else if early is error {
        io:println("orderAgent.getResult() -> error: " + early.message());
    } else {
        io:println("orderAgent.getResult() -> " + early);
    }

    // Blocking, crash-resumable read: waits until the agent finishes.
    string result = check orderAgent.waitForResult(instanceId);
    io:println("orderAgent.waitForResult() -> " + result);

    // --- Multi-turn conversation over a declared MULTI_EVENT channel ---

    string chatId = check chatAgent.run("Hello there!");
    io:println("chatAgent.run() -> instance " + chatId);

    // Each sendEvent is one turn: the token correlates that turn's reply.
    string turn = check chatAgent.sendEvent(chatId, "chat", "How are you today?");
    io:println("chatAgent.sendEvent() -> token " + turn);

    string reply = check chatAgent.waitForEventResult(chatId, turn);
    io:println("chatAgent.waitForEventResult() -> " + reply);

    string secondTurn = check chatAgent.sendEvent(chatId, "chat", "Tell me a joke.");
    string secondReply = check chatAgent.waitForEventResult(chatId, secondTurn);
    io:println("chatAgent.waitForEventResult() [turn 2] -> " + secondReply);

    // --- Agent-to-agent: a workflow orchestrating sub-agents as true children ---

    string orchestrationId = check workflow:run(orchestratorWorkflow, {id: "orch-1"});
    io:println("orchestratorWorkflow started -> " + orchestrationId);
    anydata orchestrated = check workflow:getWorkflowResult(orchestrationId, 120);
    io:println("orchestratorWorkflow result -> " + orchestrated.toString());

    // --- Model-driven peer delegation: the planner's model calls the peer tool ---

    string plannerId = check plannerAgent.run("Plan a laptop order.");
    io:println("plannerAgent.run() -> instance " + plannerId);
    string plannerResult = check plannerAgent.waitForResult(plannerId);
    io:println("plannerAgent.waitForResult() -> " + plannerResult);
}
