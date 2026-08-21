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

// An array/tuple type descriptor cannot be written inline in value position, so the
// input types that are not records travel through a named type.
type LineItems LineItem[];

type Pair [string, int];

type ReadonlyOrder readonly & OrderInput;

type Trio [int, int, int];

// Carries some of OrderInput's fields, for the spread cases: a spread that already supplied
// 'qty' would make an explicit one a duplicate key, which the type checker rejects first.
type OrderHead record {|
    string id;
    Address shipTo;
|};

final workflow:DurableAgent typedAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: OrderInput,
    activities: [checkInventory]
});

final workflow:DurableAgent listAgent = check new ({
    systemPrompt: {role: "Batch assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: LineItems,
    activities: [checkInventory]
});

final workflow:DurableAgent pairAgent = check new ({
    systemPrompt: {role: "Pair assistant", instructions: "Help the user."},
    model: chatModel,
    inputType: Pair,
    activities: [checkInventory]
});

// A builtin inputType: carrying the pre-0.9 default forward now declares a string payload.
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
    // ERROR 1 (WORKFLOW_154): the agent takes no payload at all.
    string a = check noInputAgent.run("", "payload");

    // ERROR 2 (WORKFLOW_154): a string is not a subtype of the declared OrderInput.
    string wrongPayload = "not an order";
    string b = check typedAgent.run("Place this order", wrongPayload);

    // ERROR 3 (WORKFLOW_154): the named-argument form is validated too.
    int quantity = 5;
    string c = check typedAgent.run("Place this order", input = quantity);

    // ERROR 4 (WORKFLOW_154): a list where the declared inputType is a record.
    string d = check typedAgent.run("Place this order", [1, 2]);

    // ERROR 5 (WORKFLOW_154): 'quantity' is not a field of OrderInput.
    string e = check typedAgent.run("Place this order",
        {id: "ORD-1", qty: 1, quantity: 2, shipTo: {city: "Colombo"}});

    // ERROR 6 (WORKFLOW_154): the required fields 'qty' and 'shipTo' are never set.
    string f = check typedAgent.run("Place this order", {id: "ORD-2"});

    // ERROR 7 (WORKFLOW_154): 'qty' is declared int, but a string is given.
    string g = check typedAgent.run("Place this order",
        {id: "ORD-3", qty: "two", shipTo: {city: "Colombo"}});

    // ERROR 8 (WORKFLOW_154): the nested record has no 'country' field.
    string h = check typedAgent.run("Place this order",
        {id: "ORD-4", qty: 1, shipTo: {city: "Colombo", country: "LK"}});

    // ERROR 9 (WORKFLOW_154): the nested record's required 'city' is missing.
    string i = check typedAgent.run("Place this order",
        {id: "ORD-5", qty: 1, shipTo: {zip: "10100"}});

    // ERROR 10 (WORKFLOW_154): an array member that is not a LineItem.
    string j = check listAgent.run("Process these", [{sku: "A", count: 1}, {sku: "B"}]);

    // ERROR 11 (WORKFLOW_154): the tuple inputType takes exactly two members.
    string k = check pairAgent.run("Pair up", ["a", 1, true]);

    // ERROR 12 (WORKFLOW_154): the second tuple member is declared int.
    string l = check pairAgent.run("Pair up", ["a", "b"]);

    // ERROR 13 (WORKFLOW_154): a builtin inputType is checked like any other.
    int notAString = 42;
    string m = check stringInputAgent.run("Look this up", notAString);

    // ERROR 14 (WORKFLOW_154): a readonly intersection is matched field by field too.
    string n = check readonlyOrderAgent.run("Place this order",
        {id: "ORD-7", qty: 1, shipTo: {city: "Matara", country: "LK"}});

    // ERROR 15 (WORKFLOW_154): a fixed-length array constrains arity like a tuple.
    string o = check trioAgent.run("Rank these", [1, 2, 3, 4]);

    // ERROR 16 (WORKFLOW_154): a spread leaves the fields the payload does name just as
    // checkable, whichever side of the spread they sit on — this one is not a field at all.
    // (The spread carries no 'qty', so the explicit ones below are not duplicate keys.)
    OrderHead head = {id: "ORD-8", shipTo: {city: "Colombo"}};
    string p = check typedAgent.run("Place this order", {...head, quantity: 2});

    // ERROR 17 (WORKFLOW_154): and a mistyped field after a spread is still mistyped.
    string q = check typedAgent.run("Place this order", {...head, qty: "two"});

    _ = [a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q];
}
