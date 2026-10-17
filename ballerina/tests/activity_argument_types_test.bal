// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

// ============================================================================
// Activity arguments arrive as their declared parameter types (ballerina-library#9320)
// ============================================================================
// Over the wire an argument's inherent type is the broad one (anydata[], map<anydata>).
// The activity declares the narrow one, so the adapter converts each argument before the
// call: the same path serves a workflow's callActivity and a durable agent's tool call.

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;

type TypedOrder record {|
    string id;
    int qty;
|};

@Activity
function storeTypedItems(string[] items, map<int> counts, TypedOrder 'order) returns string|error {
    // Each insertion checks the inherent type of the value: anydata[] into map<string[]> panics.
    map<string[]> itemStore = {};
    itemStore["items"] = items;
    map<map<int>> countStore = {};
    countStore["counts"] = counts;
    TypedOrder[] orders = ['order];
    return string `${items.length()}/${counts.length()}/${orders[0].id}`;
}

@Workflow
function typedArgumentsWorkflow(Context ctx, string input) returns string|error {
    return ctx->callActivity(storeTypedItems,
        {items: ["a", "b"], counts: {a: 1}, 'order: {id: input, qty: 2}});
}

@Workflow
function mismatchedArgumentsWorkflow(Context ctx, string input) returns string|error {
    return ctx->callActivity(storeTypedItems, {items: input, counts: {}, 'order: {id: "x", qty: 1}});
}

@test:BeforeSuite
function setupActivityArgumentTypeTests() returns error? {
    map<function> activities = {"storeTypedItems": storeTypedItems};
    _ = check registerWorkflowForTest(typedArgumentsWorkflow, "typedArgumentsWorkflow", activities);
    _ = check registerWorkflowForTest(mismatchedArgumentsWorkflow, "mismatchedArgumentsWorkflow", activities);
}

@test:Config {groups: ["unit"]}
function testCallActivityArgumentsArriveAsDeclaredTypes() returns error? {
    string id = check run(typedArgumentsWorkflow, "ORD-1");
    string summary = check waitForResult(id, timeout = {seconds: 30});
    test:assertEquals(summary, "2/1/ORD-1", "The activity holds string[], map<int> and the record it declared");
}

@test:Config {groups: ["unit"]}
function testCallActivityArgumentOfTheWrongTypeFailsClearly() returns error? {
    string id = check run(mismatchedArgumentsWorkflow, "not-a-list");
    string|error result = waitForResult(id, timeout = {seconds: 30});
    test:assertTrue(result is error, "A string where string[] is declared fails the call");
    if result is error {
        test:assertTrue(result.message().includes("parameter 'items'") && result.message().includes("string[]"),
            "The failure names the parameter and its type: " + result.message());
    }
}

// The model's tool arguments take the same adapter path as a workflow's callActivity.
isolated client class TypedArgsMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "storeTypedItems" {
                    return {role: ai:ASSISTANT, content: "stored " + (message.content ?: "")};
                }
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [{name: "storeTypedItems", id: "c1",
                arguments: {"items": ["a", "b", "c"], "counts": {"a": 1, "b": 2}, "order": {"id": "AG-1", "qty": 1}}}]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final TypedArgsMockModelProvider typedArgsModel = new;

final DurableAgent typedArgsAgent = check new ({
    systemPrompt: {role: "", instructions: "Store the items."},
    model: typedArgsModel,
    activities: [storeTypedItems]
});

@test:Config {groups: ["unit"]}
function testAgentActivityArgumentsArriveAsDeclaredTypes() returns error? {
    _ = check wfInternal:registerDurableAgentDecl("typedArgsAgent", typedArgsModel,
        {role: "", instructions: "Store the items."}, 8);
    _ = check wfInternal:registerDurableAgentActivity("typedArgsAgent", "storeTypedItems", storeTypedItems, ());
    _ = check wfInternal:registerDurableAgentRunner("typedArgsAgent");
    typedArgsAgent.bindAgentName("typedArgsAgent");

    string agentId = check typedArgsAgent.run("Store these.");
    string answer = check typedArgsAgent.waitForResult(agentId, timeout = {seconds: 30});
    test:assertEquals(answer, "stored 3/2/AG-1", "The model's JSON arguments arrive as the declared types");
}
