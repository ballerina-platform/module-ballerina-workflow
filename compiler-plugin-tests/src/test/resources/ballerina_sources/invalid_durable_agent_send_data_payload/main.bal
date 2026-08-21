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
import ballerina/workflow;

final ai:Wso2ModelProvider chatModel = check new ("http://localhost:9099", "test-token");

type OrderUpdate record {|
    string id;
    int qty;
    string note = "none";
|};

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    events: {
        chat: {request: string, response: string},
        orders: {request: OrderUpdate, response: string}
    }
});

public function main() returns error? {
    string agentId = check orderAgent.run("Start");

    // ERROR 1 (WORKFLOW_158): an int is not a subtype of the channel's string request.
    int notText = 42;
    string a = check orderAgent.sendData(agentId, "chat", notText);

    // ERROR 2 (WORKFLOW_158): a string where the channel declares a record request.
    string plainText = "not an order";
    string b = check orderAgent.sendData(agentId, "orders", plainText);

    // ERROR 3 (WORKFLOW_158): 'quantity' is not a field of OrderUpdate.
    string c = check orderAgent.sendData(agentId, "orders", {id: "ORD-1", qty: 1, quantity: 2});

    // ERROR 4 (WORKFLOW_158): the required field 'qty' is never set ('note' has a default).
    string d = check orderAgent.sendData(agentId, "orders", {id: "ORD-2"});

    // ERROR 5 (WORKFLOW_158): 'qty' is declared int, but a string is given.
    string e = check orderAgent.sendData(agentId, "orders", {id: "ORD-3", qty: "two"});

    // ERROR 6 (WORKFLOW_158): the named-argument form is validated too.
    string f = check orderAgent.sendData(agentId, eventName = "orders", data = plainText);

    _ = [a, b, c, d, e, f];
}
