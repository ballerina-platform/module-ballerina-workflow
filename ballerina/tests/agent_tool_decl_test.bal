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

// ================================================================================
// Durable agent AI tools — declaration shapes and ToolDecl gating
// ================================================================================
// Covers the `tools` capability of the object-model declaration: every accepted
// shape (`@ai:AgentTool` function, `ai:ToolConfig`, `ai:BaseToolKit`) and the
// `ToolDecl` wrapper's gating fields (`requiresApproval`/`userRoles`), which the
// compiler plugin forwards as named arguments to `registerDurableAgentTool`.

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;

// Deliberately NOT annotated with @ai:AgentTool: an `ai:ToolConfig` literal carries its
// own name/description, so its caller needs no annotation — the declaration path must
// preserve that (re-deriving the config from the caller would fail).
isolated function plainQuote(string item) returns string {
    return item + " costs $500";
}

final ai:ToolConfig quoteToolConfig = {
    name: "quoteTool",
    description: "Quotes the price of an item",
    caller: plainQuote
};

isolated client class ToolDeclMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "quoteTool" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Quote: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "quoteTool", arguments: {"item": "laptop"}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ToolDeclMockModelProvider toolDeclModel = new;

// The declaration anchor mirrors what a user package writes; registration below mirrors
// the plugin-generated module-init calls (the plugin does not run on this package).
final DurableAgent toolDeclRunAgent = check new ({
    systemPrompt: {role: "", instructions: "You are a pricing assistant."},
    model: toolDeclModel,
    tools: [{tool: quoteToolConfig, requiresApproval: false}]
});

@test:Config {groups: ["unit"]}
function testRegisterDurableAgentToolShapes() returns error? {
    _ = check wfInternal:registerDurableAgentDecl("toolShapesAgent", toolDeclModel,
        {role: "", instructions: "Tool shapes."}, 8);

    // Shape 1: a bare `@ai:AgentTool` function (name comes from the annotation plumbing).
    _ = check wfInternal:registerDurableAgentTool("toolShapesAgent", lookupPrice);

    // Shape 2: an `ai:ToolConfig` literal — the caller is NOT `@ai:AgentTool` annotated.
    _ = check wfInternal:registerDurableAgentTool("toolShapesAgent", quoteToolConfig);

    // Shape 3: an `ai:BaseToolKit` — expanded via getTools().
    _ = check wfInternal:registerDurableAgentTool("toolShapesAgent", new TestToolKit());

    // ToolDecl gating fields pass through as named arguments (what the plugin emits for
    // `{tool: x, requiresApproval: true, userRoles: "finance"}`).
    _ = check wfInternal:registerDurableAgentTool("toolShapesAgent",
        {name: "gatedQuote", description: "Gated quote", caller: plainQuote},
        requiresApproval = true, userRoles = "finance");
    _ = check wfInternal:registerDurableAgentTool("toolShapesAgent",
        {name: "multiRoleQuote", description: "Multi-role quote", caller: plainQuote},
        requiresApproval = true, userRoles = ["finance", "manager"]);

    // A bare function without @ai:AgentTool cannot self-describe.
    boolean|error unannotated = wfInternal:registerDurableAgentTool("toolShapesAgent", plainQuote);
    test:assertTrue(unannotated is error, "A bare function without @ai:AgentTool should be rejected");

    // Registration against an unknown agent is an error.
    boolean|error unknown = wfInternal:registerDurableAgentTool("noSuchToolAgent", lookupPrice);
    test:assertTrue(unknown is error, "Tool registration for an unknown agent should fail");
}

@test:Config {groups: ["unit"]}
function testToolConfigDeclEndToEnd() returns error? {
    // Mirror the plugin-generated registration for `toolDeclRunAgent`.
    _ = check wfInternal:registerDurableAgentDecl("toolDeclRunAgent", toolDeclModel,
        {role: "", instructions: "You are a pricing assistant."}, 16);
    _ = check wfInternal:registerDurableAgentTool("toolDeclRunAgent", quoteToolConfig,
        requiresApproval = false);
    _ = check wfInternal:registerDurableAgentRunner("toolDeclRunAgent");
    toolDeclRunAgent.bindAgentName("toolDeclRunAgent");

    string|error runResult = toolDeclRunAgent.run("How much is the laptop?");
    if runResult is error {
        return; // no workflow server in this environment
    }
    string result = check toolDeclRunAgent.waitForResult(runResult);
    test:assertEquals(result, "Quote: laptop costs $500",
        "The runner should register the declared ai:ToolConfig tool and execute it durably");
}
