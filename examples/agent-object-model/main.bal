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
}
