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

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    return item.length() > 0;
}

type OrderUpdate record {|
    string id;
    int qty;
    string note = "none";
|};

// The mapping form is the primary declaration style: the key IS the name, constant by
// construction, so neither channels nor tasks need a validated `name` field.
final workflow:DurableAgent mapAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    // A comment directly above a config field is leading trivia of the field name's
    // token: reading names via toSourceCode() folded it into the "name" and the field
    // silently stopped matching (sendData then reported the channel as undeclared).
    activities: [checkInventory],
    // The channels — this comment, too, must not hide the events field.
    events: {
        chat: {request: string, response: string, cardinality: workflow:MULTI_EVENT},
        // A quoted key declares a name that is not an identifier.
        "order-updates": {request: OrderUpdate, response: string},
        // One-way channel: data flows in, no result is read back.
        audit: {request: OrderUpdate}
    },
    humanTasks: {
        signoff: {userRoles: "manager", title: "Sign off", timeout: {minutes: 5}},
        review: {userRoles: ["finance", "ops"], resultType: OrderUpdate}
    },
    peers: [
        {agent: helperAgent, name: "askHelper", description: "Delegates to the helper.",
            'wait: false, callbackChannel: "chat"}
    ]
});

final workflow:DurableAgent helperAgent = check new ({
    systemPrompt: {role: "Helper", instructions: "Help."},
    model: chatModel
});

public function main() returns error? {
    string agentId = check mapAgent.run("Start helping");

    // Channels declared through the mapping form drive sendData validation: declared
    // channels pass, with the payload checked against the declared request type.
    string turn = check mapAgent.sendData(agentId, "chat", "How are we doing?");
    string reply = check mapAgent.waitForDataResult(agentId, turn);

    OrderUpdate update = {id: "ORD-1", qty: 2};
    string orderTurn = check mapAgent.sendData(agentId, "order-updates", update);

    // Inline payloads are matched structurally; the defaulted field may be omitted.
    string inlineTurn = check mapAgent.sendData(agentId, "order-updates", {id: "ORD-2", qty: 1});

    // One-way channel: the token must be discarded (WORKFLOW_153 otherwise).
    _ = check mapAgent.sendData(agentId, "audit", {id: "ORD-3", qty: 1, note: "logged"});

    _ = [reply, orderTurn, inlineTurn];
}
