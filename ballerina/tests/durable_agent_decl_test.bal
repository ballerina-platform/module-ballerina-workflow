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
// Object-model durable agent declaration — unit tests
// ================================================================================
// The compiler plugin does not run on the workflow package itself, so these tests
// exercise the declaration surface (object construction, driver method stubs) and
// the wfInternal registration natives directly — the same calls the plugin
// generates at module init for user packages.

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

final MockModelProvider declTestModel = new;

@Activity
function declTestActivity(string item) returns boolean|error {
    return item.length() > 0;
}

final DurableAgent declTestAgent = check new ({
    systemPrompt: {role: "Test assistant", instructions: "Assist with tests."},
    model: declTestModel,
    activities: [declTestActivity],
    events: [
        {name: "chat", request: string, response: string, cardinality: MULTI_EVENT}
    ],
    humanTasks: [
        {name: "signoff", roles: "manager"}
    ]
});

@test:Config {}
function testDurableAgentDeclRegistration() returns error? {
    // Mirror the plugin-generated module-init registration.
    _ = check wfInternal:registerDurableAgentDecl("declTestAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    _ = check wfInternal:registerDurableAgentActivity("declTestAgent", "declTestActivity",
        declTestActivity, {requiresApproval: false});
    _ = check wfInternal:registerDurableAgentEvent("declTestAgent", "chat", string, string,
        "MULTI_EVENT");
    _ = check wfInternal:registerDurableAgentHumanTask("declTestAgent", "signoff",
        {roles: "manager", timeout: {minutes: 5}});

    // Registering the same agent name twice is an error.
    boolean|error duplicate = wfInternal:registerDurableAgentDecl("declTestAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    test:assertTrue(duplicate is error, "Duplicate agent registration should fail");

    // Capability registration against an unknown agent is an error.
    boolean|error unknown = wfInternal:registerDurableAgentActivity("noSuchAgent", "x",
        declTestActivity);
    test:assertTrue(unknown is error, "Registration for an unknown agent should fail");
}

@test:Config {}
function testDurableAgentBuiltinActivityNameCollision() returns error? {
    // A declared activity must not shadow a built-in agent activity: the runner
    // registration rejects the collision instead of silently overwriting the
    // built-in entry (which would make the loop's llmChat calls invoke the user
    // function).
    _ = check wfInternal:registerDurableAgentDecl("builtinCollisionAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    _ = check wfInternal:registerDurableAgentActivity("builtinCollisionAgent", "llmChat",
        declTestActivity);
    boolean|error collision = wfInternal:registerDurableAgentRunner("builtinCollisionAgent");
    test:assertTrue(collision is error,
        "A declared activity named after a built-in agent activity should be rejected");
    if collision is error {
        test:assertTrue(collision.message().includes("built-in"),
            "The collision error should name the built-in conflict: " + collision.message());
    }
}

final DurableAgent runnerCoverageAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
    activities: [checkStock],
    events: [
        {name: "status", request: string, response: string, cardinality: SINGLE_EVENT}
    ],
    humanTasks: [
        {name: "signoffCoverage", roles: "manager", timeout: {minutes: 5}}
    ]
});

@test:Config {groups: ["unit"]}
function testObjectModelRunnerEndToEnd() returns error? {
    // Mirror the plugin-generated module-init registration for a runnable agent, then
    // drive the whole object-model path: DurableAgent.run resolves the declaration,
    // the shared runner registers every declared capability on the native context, and
    // the ReAct loop completes with the mock model's answer as the workflow result.
    _ = check wfInternal:registerDurableAgentDecl("runnerCoverageAgent", declTestModel,
        {role: "", instructions: "You are an inventory assistant."}, 16);
    _ = check wfInternal:registerDurableAgentActivity("runnerCoverageAgent", "checkStock",
        checkStock, {description: "Checks the stock of an item", requiresApproval: false});
    _ = check wfInternal:registerDurableAgentEvent("runnerCoverageAgent", "status", string, string,
        "SINGLE_EVENT");
    _ = check wfInternal:registerDurableAgentHumanTask("runnerCoverageAgent", "signoffCoverage",
        {roles: "manager", title: "Sign off", description: "Sign off the order",
            timeout: {minutes: 5}});
    _ = check wfInternal:registerDurableAgentPeer("runnerCoverageAgent", "askDriver",
        "agentTurnDriver", {description: "Delegates to the turn driver", "wait": true});
    _ = check wfInternal:registerDurableAgentRunner("runnerCoverageAgent");
    runnerCoverageAgent.bindAgentName("runnerCoverageAgent");

    string agentId = check runnerCoverageAgent.run("Is the laptop in stock?");
    string result = check runnerCoverageAgent.waitForResult(agentId);
    test:assertEquals(result, "Stock check result: laptop is in stock",
        "The object-model runner should resolve the declaration and drive the ReAct loop");
}

type CoverageOrder record {|
    string id;
    int qty;
|};

final DurableAgent typedInputAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
    inputType: CoverageOrder,
    activities: [checkStock]
});

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testManagementStartAndListUnified() returns error? {
    // Mirror the plugin-generated registration for an agent with a typed input.
    _ = check wfInternal:registerDurableAgentDecl("typedInputAgent", declTestModel,
        {role: "", instructions: "You are an inventory assistant."}, 16, CoverageOrder);
    _ = check wfInternal:registerDurableAgentActivity("typedInputAgent", "checkStock", checkStock);
    _ = check wfInternal:registerDurableAgentRunner("typedInputAgent");
    typedInputAgent.bindAgentName("typedInputAgent");

    // Workflows and agents list as one set of definitions: the agent entries carry
    // kind AGENT and a schema derived from the declared inputType.
    management:WorkflowDefinition[] defs = check management:listWorkflowDefinitions();
    management:WorkflowDefinition? queryAgentDef = ();
    management:WorkflowDefinition? typedAgentDef = ();
    foreach management:WorkflowDefinition def in defs {
        if def.workflowType == "runnerCoverageAgent" {
            queryAgentDef = def;
        }
        if def.workflowType == "typedInputAgent" {
            typedAgentDef = def;
        }
    }
    test:assertTrue(queryAgentDef is management:WorkflowDefinition
            && typedAgentDef is management:WorkflowDefinition,
        "Both agents should appear in the unified definitions list");
    if queryAgentDef is management:WorkflowDefinition {
        test:assertEquals(queryAgentDef.kind, "AGENT");
        string? schema = queryAgentDef.inputSchema;
        test:assertTrue(schema is string && schema.includes("string"),
            "The default string inputType should produce a string schema");
    }
    if typedAgentDef is management:WorkflowDefinition {
        string? schema = typedAgentDef.inputSchema;
        test:assertTrue(schema is string && schema.includes("qty"),
            "A typed inputType should produce its record schema: " + (schema ?: "()"));
    }

    // Starting through the same management API as workflows: the string inputType makes
    // the posted input the query; a typed inputType validates and passes the payload.
    management:WorkflowHandle stringStart = check management:startWorkflowByType(
        "runnerCoverageAgent", "Is the laptop in stock?");
    string stringResult = check runnerCoverageAgent.waitForResult(stringStart.workflowId);
    test:assertEquals(stringResult, "Stock check result: laptop is in stock",
        "A management-started string-input agent should treat the input as the query");

    management:WorkflowHandle typedStart = check management:startWorkflowByType(
        "typedInputAgent", {id: "ORD-9", qty: 2});
    string typedResult = check typedInputAgent.waitForResult(typedStart.workflowId);
    test:assertEquals(typedResult, "Stock check result: laptop is in stock",
        "A management-started typed-input agent should receive the validated payload");

    // A payload that does not match the declared inputType is rejected at start.
    management:WorkflowHandle|error mismatch = management:startWorkflowByType(
        "typedInputAgent", "not an order");
    test:assertTrue(mismatch is error, "A mismatched input must be rejected at start");

    // agent.run validates dynamically too: a typed agent rejects a wrong payload.
    anydata wrongPayload = "still not an order";
    string|error runMismatch = typedInputAgent.run("Place this order", wrongPayload);
    test:assertTrue(runMismatch is error, "run must reject a payload that violates inputType");
}

@test:Config {}
function testDurableAgentDriverStubs() {
    // run() requires the plugin-generated name binding, which does not run for the
    // module's own tests — an unbound object reports a descriptive error.
    string|error runResult = declTestAgent.run("hello");
    test:assertTrue(runResult is error, "run on an unbound agent should fail");
    if runResult is error {
        test:assertTrue(runResult.message().includes("no agent name is bound"));
    }

    // The event-turn methods are live but need a running instance: without one (or
    // without a workflow client in this unit-test context) each reports the failing
    // instance/turn in its error.
    string|error sendResult = declTestAgent.sendData("wf-1", "chat", "hi");
    test:assertTrue(sendResult is error, "sendData to a missing instance should fail");
    if sendResult is error {
        test:assertTrue(sendResult.message().includes("agent instance 'wf-1'")
                || sendResult.message().includes("Workflow client not initialized"),
            "sendData error should name the missing instance: " + sendResult.message());
    }

    string|error eventResult = declTestAgent.getDataResult("wf-1", "token-1");
    test:assertTrue(eventResult is error, "getDataResult for a missing instance should fail");
    if eventResult is error {
        test:assertTrue(eventResult.message().includes("agent instance 'wf-1'")
                || eventResult.message().includes("Workflow client not initialized"),
            "getDataResult error should name the missing instance: " + eventResult.message());
    }

    string|error waitEventResult = declTestAgent.waitForDataResult("wf-1", "token-1");
    test:assertTrue(waitEventResult is error, "waitForDataResult for a missing instance should fail");
    if waitEventResult is error {
        test:assertTrue(waitEventResult.message().includes("agent instance 'wf-1'")
                || waitEventResult.message().includes("Workflow client not initialized"),
            "waitForDataResult error should name the missing instance: " + waitEventResult.message());
    }
}

// ── Declared result type: the loop exit produces a typed final result ───────

type CoverageSummary record {|
    string summary;
    int score;
|};

final DurableAgent typedResultAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
    resultType: CoverageSummary,
    activities: [checkStock]
});

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testDeclaredResultTypeEndToEnd() returns error? {
    // Mirror the plugin-generated registration for an agent with a declared result type.
    _ = check wfInternal:registerDurableAgentDecl("typedResultAgent", declTestModel,
        {role: "", instructions: "You are an inventory assistant."}, 16, string, CoverageSummary);
    _ = check wfInternal:registerDurableAgentActivity("typedResultAgent", "checkStock", checkStock);
    _ = check wfInternal:registerDurableAgentRunner("typedResultAgent");
    typedResultAgent.bindAgentName("typedResultAgent");

    string|error runResult = typedResultAgent.run("Is the laptop in stock?");
    if runResult is error {
        // Skip only when the embedded workflow server is unavailable in this environment;
        // any other failure means the typed-result path broke and must fail the test.
        if runResult.message().includes("Workflow client not initialized") {
            return;
        }
        return runResult;
    }
    // The mock model provider's generate returns {summary: "generated summary", score: 7};
    // the runner's loop-exit generateResult call must convert it to the declared type.
    CoverageSummary result = check typedResultAgent.waitForResult(runResult);
    test:assertEquals(result.summary, "generated summary",
        "The declared result type should be produced by the loop-exit generate call");
    test:assertEquals(result.score, 7, "The typed result fields should convert");

    // The non-blocking read returns the same typed result once the run has completed.
    // The server's execution-status visibility can lag the result future by a beat,
    // so poll through transient AgentBusyError reads.
    CoverageSummary? polled = ();
    foreach int _ in 0 ..< 20 {
        CoverageSummary|error read = typedResultAgent.getResult(runResult);
        if read is CoverageSummary {
            polled = read;
            break;
        }
        if !(read is AgentBusyError) {
            return read;
        }
        runtime:sleep(0.25);
    }
    if polled is () {
        test:assertFail("getResult never returned the completed typed result");
    } else {
        test:assertEquals(polled.summary, "generated summary",
            "getResult should return the declared typed result after completion");
        test:assertEquals(polled.score, 7, "getResult should convert the typed result fields");
    }
}

// ── Built-in durable sleep tool ──────────────────────────────────────────────

isolated client class SleepMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage && message.name == "sleep" {
                    string? content = message.content;
                    return {role: ai:ASSISTANT, content: "Awake: " + (content ?: "")};
                }
            }
        }
        return {role: ai:ASSISTANT, toolCalls: [{name: "sleep", arguments: {"seconds": 1}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final SleepMockModelProvider sleepMockModel = new;

final DurableAgent sleepingAgent = check new ({
    systemPrompt: {role: "", instructions: "You can pause with the sleep tool."},
    model: sleepMockModel
});

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testBuiltinDurableSleepTool() returns error? {
    _ = check wfInternal:registerDurableAgentDecl("sleepingAgent", sleepMockModel,
        {role: "", instructions: "You can pause with the sleep tool."}, 8);
    _ = check wfInternal:registerDurableAgentRunner("sleepingAgent");
    sleepingAgent.bindAgentName("sleepingAgent");

    string|error runResult = sleepingAgent.run("Wait a moment, then confirm.");
    if runResult is error {
        // Skip only when the embedded workflow server is unavailable; any other error
        // means the sleep/timer path broke and must fail the test.
        if runResult.message().includes("Workflow client not initialized") {
            return;
        }
        return runResult;
    }
    // The model calls the always-available sleep builtin; the loop runs a durable
    // one-second timer on the workflow thread and feeds the outcome back.
    string result = check sleepingAgent.waitForResult(runResult);
    test:assertEquals(result, "Awake: Slept for 1 seconds.",
        "The built-in sleep tool should run a durable timer and feed its outcome to the model");
}
