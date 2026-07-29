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

type OrderInput record {|
    string id;
    int qty;
|};

final workflow:DurableAgent typedAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: OrderInput,
    activities: [checkInventory]
});

final workflow:DurableAgent queryAgent = check new ({
    systemPrompt: {role: "Query assistant", instructions: "Help the user."},
    model: chatModel,
    activities: [checkInventory]
});

final workflow:DurableAgent noInputAgent = check new ({
    systemPrompt: {role: "Reactive assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: (),
    activities: [checkInventory],
    events: [
        {name: "chat", request: string, response: string}
    ]
});

public function main() returns error? {
    // ERROR (WORKFLOW_154): inputType string (default) — the query text is the input.
    string a = check queryAgent.run("Is the laptop in stock?", "extra payload");

    // ERROR (WORKFLOW_154): the agent declares no input at all.
    string b = check noInputAgent.run("", "payload");

    // ERROR (WORKFLOW_154): a string is not a subtype of the declared OrderInput.
    string wrongPayload = "not an order";
    string c = check typedAgent.run("Place this order", wrongPayload);

    // ERROR (WORKFLOW_154): the named-argument form is validated too.
    int quantity = 5;
    string d = check typedAgent.run("Place this order", input = quantity);

    _ = [a, b, c, d];
}
