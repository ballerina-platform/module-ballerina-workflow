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

import ballerina/ai;
import ballerina/io;
import ballerina/workflow;

type OrderRequest record {|
    string orderId;
    string userPrompt;
|};

// The WSO2 default model provider, configured via `ballerina.ai.wso2ProviderConfig`
// in Config.toml (see README.md — the Ballerina VS Code extension can generate it).
final ai:Wso2ModelProvider orderModel = check ai:getDefaultModelProvider();

// A tool the agent can invoke. Every tool call runs as a durable Temporal
// activity, so it is retried and never re-executed on replay.
@workflow:Activity
function checkInventory(string item) returns string|error {
    io:println(string `[activity] checkInventory(${item})`);
    return item + " is in stock";
}

// A conversational durable AI agent. The body configures the agent imperatively
// and hands control to the durable ReAct loop. With the MULTI_EVENT interaction
// pattern the model answers each turn and then durably re-arms the chat wait —
// suspending for hours or days without holding a thread — until the user says
// goodbye (or the safety timeout/wait-cap kicks in).
@workflow:DurableAgentFunction
function orderAgent(workflow:AgentContext durableAgentContext, OrderRequest req) returns error? {
    check durableAgentContext.registerActivity(checkInventory);
    check durableAgentContext.registerUpdateEvents("chat", string);
    check durableAgentContext.buildAndRun(req.userPrompt,
            systemPrompt = {
                role: string `You are the assistant for order ${req.orderId}.`,
                instructions: string `Use the checkInventory tool to answer product availability questions.
                        The conversation stays open automatically after each answer.
                        When the user says goodbye or asks to end the conversation, call the
                        endConversation tool with a short farewell.`
            },
            model = orderModel, interaction = workflow:MULTI_EVENT, eventTimeout = {minutes: 5});
}

public function main() returns error? {
    // Start the agent with no initial prompt: it suspends durably until the
    // first chat message arrives. (An initial prompt would start a one-way
    // turn whose answer no updateAgent call is waiting for — drive every turn
    // you want a reply from via updateAgent instead.)
    string agentId = check workflow:runDurableAgent(orderAgent, {orderId: "ORD-001", userPrompt: ""});
    io:println("Agent started with ID: " + agentId);

    // Each turn is a synchronous request-response (a Temporal Update under the
    // hood): the message and the agent's answer for that turn travel together.
    string reply1 = check workflow:updateAgent(orderAgent, agentId, "chat", "Is the laptop available?");
    io:println("Turn 1: " + reply1);

    string reply2 = check workflow:updateAgent(orderAgent, agentId, "chat", "Please expedite the shipping");
    io:println("Turn 2: " + reply2);

    // The model ends the conversation when the user says goodbye.
    string reply3 = check workflow:updateAgent(orderAgent, agentId, "chat", "That's all, goodbye!");
    io:println("Final: " + reply3);

    _ = check workflow:getWorkflowResult(agentId);
}
