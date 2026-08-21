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

type Address record {|
    string city;
    string zip?;
|};

type OrderInput record {|
    string id;
    int qty;
    Address shipTo;
    string note = "none";
|};

type LineItem record {|
    string sku;
    int count;
|};

// An array/map type descriptor cannot be written inline in value position, so the
// input types that are not records travel through a named type.
type LineItems LineItem[];

type Counts map<int>;

type ReadonlyOrder readonly & OrderInput;

type Trio [int, int, int];

final workflow:DurableAgent typedAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: OrderInput,
    activities: [checkInventory]
});

// No inputType: the open `json` default accepts any payload shape.
final workflow:DurableAgent openAgent = check new ({
    systemPrompt: {role: "Query assistant", instructions: "Help the user."},
    model: chatModel,
    activities: [checkInventory]
});

final workflow:DurableAgent listAgent = check new ({
    systemPrompt: {role: "Batch assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: LineItems,
    activities: [checkInventory]
});

final workflow:DurableAgent mapAgent = check new ({
    systemPrompt: {role: "Lookup assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: Counts,
    activities: [checkInventory]
});

// A builtin inputType: carrying the pre-0.9 default forward now declares a string payload,
// and the query keeps its own argument.
final workflow:DurableAgent stringInputAgent = check new ({
    systemPrompt: {role: "Legacy assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: string,
    activities: [checkInventory]
});

final workflow:DurableAgent readonlyOrderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: ReadonlyOrder,
    activities: [checkInventory]
});

final workflow:DurableAgent trioAgent = check new ({
    systemPrompt: {role: "Trio assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: Trio,
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
    // Query-only runs are always valid, whatever the declared inputType.
    string a = check typedAgent.run("Order two laptops");
    string b = check openAgent.run("Is the laptop in stock?");
    string c = check noInputAgent.run("");
    string d = check typedAgent.run("Query only", ());

    // A typed payload matching the declared inputType, positionally and by name.
    OrderInput firstOrder = {id: "ORD-1", qty: 2, shipTo: {city: "Colombo"}};
    string e = check typedAgent.run("Place this order", firstOrder);
    string f = check typedAgent.run("Place this order", input = firstOrder);

    // Inline constructors are matched field by field: every required field is set,
    // the nested record is complete, the defaulted field may be omitted or given,
    // and the optional nested field may be present.
    string g = check typedAgent.run("Place this order",
        {id: "ORD-2", qty: 1, shipTo: {city: "Kandy"}});
    string h = check typedAgent.run("Place this order",
        {id: "ORD-3", qty: 4, shipTo: {city: "Galle", zip: "80000"}, note: "gift"});

    // A shorthand field carries the value of the same-named variable.
    string id = "ORD-4";
    string i = check typedAgent.run("Place this order", {id, qty: 1, shipTo: {city: "Jaffna"}});

    // The open `json` default takes any payload: object, list, or scalar.
    string j = check openAgent.run("Anything goes", {"whatever": [1, 2, 3]});
    string k = check openAgent.run("Anything goes", [1, "two", true]);
    string l = check openAgent.run("Anything goes", "a bare string payload");

    // List and map input types match their members.
    string m = check listAgent.run("Process these", [{sku: "A", count: 1}, {sku: "B", count: 2}]);
    string n = check mapAgent.run("Look these up", {"a": 1, "b": 2});

    // A builtin inputType takes a matching payload, and the query stays its own argument.
    string o = check stringInputAgent.run("Look this up", "a string payload");

    // A readonly intersection is still matched field by field, so a complete payload passes.
    string p = check readonlyOrderAgent.run("Place this order",
        {id: "ORD-5", qty: 1, shipTo: {city: "Matara"}});

    // A fixed-length array takes exactly its declared number of members.
    string q = check trioAgent.run("Rank these", [1, 2, 3]);

    // A spread supplies fields this pass cannot name, so the payload is not called incomplete.
    OrderInput template = {id: "ORD-6", qty: 1, shipTo: {city: "Colombo"}};
    string r = check typedAgent.run("Place this order", {...template});

    _ = [a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r];
}
