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
// The compiler plugin generates the module-init registration from the config; the
// driver methods come online with the object-model runner (next phase).

import ballerina/ai;
import ballerina/io;
import ballerina/workflow;
import ballerina/workflow.internal as wfInternal;

// A placeholder provider: the constructor does not connect, and no LLM call is made
// in this phase. Swap for `ai:getDefaultModelProvider()` + Config.toml when running
// the agent for real.
final ai:Wso2ModelProvider orderModel = check new ("http://localhost:9099", "test-token");

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
        {name: "chat", request: string, response: string, cardinality: workflow:MULTI_EVENT},
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

    // Phase-1 state: the declaration surface is live; the runner methods report
    // themselves as not yet supported instead of failing silently.
    string|error runResult = orderAgent.run("Where is order ORD-1?");
    if runResult is error {
        io:println("orderAgent.run() -> " + runResult.message());
    } else {
        io:println("orderAgent.run() unexpectedly succeeded: " + runResult);
    }

    string|error sendResult = orderAgent.sendEvent("wf-1", "chat", "cancel it");
    if sendResult is error {
        io:println("orderAgent.sendEvent() -> " + sendResult.message());
    }

    // Prove the generated module-init registration ran: a second registration under the
    // same agent name must be rejected as a duplicate.
    boolean|error dup = wfInternal:registerDurableAgentDecl("orderAgent", orderModel,
        {role: "x", instructions: "y"}, 8);
    io:println(dup is error
        ? "decl registry check -> 'orderAgent' was already registered by the generated module-init code"
        : "decl registry check -> MISSING: the generated registration did not run!");
}
