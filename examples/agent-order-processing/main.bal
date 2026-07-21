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

// A conversational durable AI agent, declared once as a module-level object whose
// constructor config carries every capability. The MULTI_EVENT chat channel keeps
// the conversation open after each answer — the agent suspends durably for hours
// or days without holding a thread — until the user says goodbye (or the safety
// timeout/wait-cap kicks in).
final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {
        role: "You are the assistant for order ORD-001.",
        instructions: string `Use the checkInventory tool to answer product availability questions.
                The conversation stays open automatically after each answer.
                When the user says goodbye or asks to end the conversation, call the
                endConversation tool with a short farewell.`
    },
    model: orderModel,
    activities: [checkInventory],
    events: [
        {name: "chat", request: string, response: string, cardinality: workflow:MULTI_EVENT}
    ]
});

public function main() returns error? {
    // Start the agent with no initial query: it suspends durably until the first
    // chat message arrives. Every turn you want a reply from is driven via
    // sendEvent, whose token correlates that turn's answer.
    string agentId = check orderAgent.run("");
    io:println("Agent started with ID: " + agentId);

    string turn1 = check orderAgent.sendEvent(agentId, "chat", "Is the laptop available?");
    io:println("Turn 1: " + check orderAgent.waitForEventResult(agentId, turn1));

    string turn2 = check orderAgent.sendEvent(agentId, "chat", "Please expedite the shipping");
    io:println("Turn 2: " + check orderAgent.waitForEventResult(agentId, turn2));

    // The model ends the conversation when the user says goodbye.
    string turn3 = check orderAgent.sendEvent(agentId, "chat", "That's all, goodbye!");
    io:println("Final: " + check orderAgent.waitForEventResult(agentId, turn3));

    _ = check orderAgent.waitForResult(agentId);
}
