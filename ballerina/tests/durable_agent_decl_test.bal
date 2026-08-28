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
import ballerina/time;
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
    events: {
        chat: {request: string, response: string, cardinality: MULTI_EVENT}
    },
    humanTasks: {
        signoff: {roles: "manager"}
    }
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

@test:Config {}
function testDurableAgentDuplicateCapabilityNames() returns error? {
    // A capability name is the agent's identity for that capability — the tool the model
    // calls, and for a human task the Temporal workflow type of the task. Registering a
    // name twice would replace the earlier declaration silently, so the registration
    // (which runs at module init) fails and the program does not start. The compiler
    // plugin reports the same conflict as WORKFLOW_150, but it cannot see every
    // declaration, so this check always runs.
    _ = check wfInternal:registerDurableAgentDecl("duplicateNameAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    _ = check wfInternal:registerDurableAgentHumanTask("duplicateNameAgent", "approve",
        {roles: "manager"});

    boolean|error duplicateTask = wfInternal:registerDurableAgentHumanTask("duplicateNameAgent",
        "approve", {roles: "finance"});
    test:assertTrue(duplicateTask is error, "A second human task named 'approve' should be rejected");
    if duplicateTask is error {
        test:assertTrue(duplicateTask.message().includes("Duplicate capability name 'approve'"),
            "The error should name the duplicate: " + duplicateTask.message());
    }

    // The namespace is flat: an activity cannot reuse a human task's name either.
    boolean|error crossKind = wfInternal:registerDurableAgentActivity("duplicateNameAgent",
        "approve", declTestActivity);
    test:assertTrue(crossKind is error,
        "An activity reusing the human task's name should be rejected");

    // A different name on the same agent still registers.
    _ = check wfInternal:registerDurableAgentHumanTask("duplicateNameAgent", "reject",
        {roles: "manager"});
}

final DurableAgent runnerCoverageAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
    activities: [checkStock],
    events: {
        status: {request: string, response: string, cardinality: SINGLE_EVENT}
    },
    humanTasks: {
        signoffCoverage: {roles: "manager", timeout: {minutes: 5}}
    }
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

// inputType: () — the query is this agent's only input.
final DurableAgent queryOnlyAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
    inputType: (),
    activities: [checkStock]
});

// No inputType at all: the `json` default takes any payload, unvalidated.
final DurableAgent openInputAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an inventory assistant."},
    model: declTestModel,
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

    _ = check wfInternal:registerDurableAgentDecl("queryOnlyAgent", declTestModel,
        {role: "", instructions: "You are an inventory assistant."}, 16, ());
    _ = check wfInternal:registerDurableAgentActivity("queryOnlyAgent", "checkStock", checkStock);
    _ = check wfInternal:registerDurableAgentRunner("queryOnlyAgent");
    queryOnlyAgent.bindAgentName("queryOnlyAgent");

    _ = check wfInternal:registerDurableAgentDecl("openInputAgent", declTestModel,
        {role: "", instructions: "You are an inventory assistant."}, 16);
    _ = check wfInternal:registerDurableAgentActivity("openInputAgent", "checkStock", checkStock);
    _ = check wfInternal:registerDurableAgentRunner("openInputAgent");
    openInputAgent.bindAgentName("openInputAgent");

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
    // Every agent is started through the same `{query, input}` envelope, so every agent's
    // schema carries a required `query`; `input` appears only when a payload is accepted.
    if queryAgentDef is management:WorkflowDefinition {
        test:assertEquals(queryAgentDef.kind, "AGENT");
        string schema = queryAgentDef.inputSchema ?: "";
        test:assertTrue(schema.includes("\"query\""),
            "The start envelope should always declare a query field: " + schema);
        test:assertTrue(schema.includes("\"input\""),
            "The open json default should still accept a payload: " + schema);
    }
    if typedAgentDef is management:WorkflowDefinition {
        string schema = typedAgentDef.inputSchema ?: "";
        test:assertTrue(schema.includes("\"query\"") && schema.includes("qty"),
            "A typed inputType should nest its record schema under input: " + schema);
    }

    // Starting through the same management API as workflows: the query is the user turn
    // and the input is the payload, validated against the declared inputType.
    management:WorkflowHandle queryStart = check management:startWorkflowByType(
        "runnerCoverageAgent", {query: "Is the laptop in stock?"});
    string queryResult = check runnerCoverageAgent.waitForResult(queryStart.workflowId);
    test:assertEquals(queryResult, "Stock check result: laptop is in stock",
        "A management-started agent should reason over the envelope's query");

    management:WorkflowHandle typedStart = check management:startWorkflowByType(
        "typedInputAgent", {query: "Place this order", input: {id: "ORD-9", qty: 2}});
    string typedResult = check typedInputAgent.waitForResult(typedStart.workflowId);
    test:assertEquals(typedResult, "Stock check result: laptop is in stock",
        "A management-started typed-input agent should receive the validated payload");

    // A payload that does not match the declared inputType is rejected at start.
    management:WorkflowHandle|error mismatch = management:startWorkflowByType(
        "typedInputAgent", {query: "Place this order", input: "not an order"});
    test:assertTrue(mismatch is error, "A mismatched input must be rejected at start");
    if mismatch is error {
        test:assertTrue(mismatch.message().includes("'input' field"),
            "The rejection should name the offending envelope field: " + mismatch.message());
    }

    // The envelope is the only accepted shape: a bare payload has no query to carry.
    management:WorkflowHandle|error bare = management:startWorkflowByType(
        "typedInputAgent", "Place this order");
    test:assertTrue(bare is error, "A non-envelope start input must be rejected");

    // A query-only agent rejects a payload it cannot pass anywhere.
    management:WorkflowHandle|error unexpected = management:startWorkflowByType(
        "queryOnlyAgent", {query: "hello", input: {id: "ORD-9"}});
    test:assertTrue(unexpected is error, "A query-only agent must reject a payload");

    // agent.run validates dynamically too: a typed agent rejects a wrong payload.
    json wrongPayload = "still not an order";
    string|error runMismatch = typedInputAgent.run("Place this order", wrongPayload);
    test:assertTrue(runMismatch is error, "run must reject a payload that violates inputType");
    if runMismatch is error {
        test:assertTrue(runMismatch.message().includes("declared inputType"),
            "The run rejection should name the declared inputType: " + runMismatch.message());
    }

    // A query-only agent rejects a payload at run() too, and says why.
    string|error noInputRun = queryOnlyAgent.run("hello", {id: "ORD-9"});
    test:assertTrue(noInputRun is error, "A query-only agent must reject a run payload");
    if noInputRun is error {
        test:assertTrue(noInputRun.message().includes("takes no input payload"),
            "The rejection should explain that no payload is accepted: " + noInputRun.message());
    }

    // The open json default accepts any shape, and the payload reaches the model turn.
    string openId = check openInputAgent.run("Is the laptop in stock?",
        {"note": "urgent", "tags": ["a", "b"]});
    string openResult = check openInputAgent.waitForResult(openId);
    test:assertEquals(openResult, "Stock check result: laptop is in stock",
        "The open json default should accept an arbitrary payload");
}

@test:Config {groups: ["unit"], dependsOn: [testObjectModelRunnerEndToEnd]}
function testSendDataValidatesAgainstTheTargetsDeclaration() returns error? {
    // sendData is validated against the TARGET instance's declaration before anything is
    // sent. An undeclared channel used to be a black hole: the update parked under a name
    // nobody waits on and waitForDataResult hung — now it is an immediate error that names
    // the declared channels.
    string agentId = check runnerCoverageAgent.run("Is the laptop in stock?");
    string _ = check runnerCoverageAgent.waitForResult(agentId);

    string|error unknownChannel = runnerCoverageAgent.sendData(agentId, "nope", "hello");
    test:assertTrue(unknownChannel is error, "An undeclared channel must be rejected at send");
    if unknownChannel is error {
        test:assertTrue(unknownChannel.message().includes("declares no data-event channel named 'nope'")
                && unknownChannel.message().includes("status"),
            "The rejection should name the channel and list the declared ones: "
                + unknownChannel.message());
    }

    // A payload that does not fit the channel's declared request type is rejected the same
    // way — before delivery, naming the declared type.
    string|error wrongPayload = runnerCoverageAgent.sendData(agentId, "status", {bad: "shape"});
    test:assertTrue(wrongPayload is error, "A mistyped payload must be rejected at send");
    if wrongPayload is error {
        test:assertTrue(wrongPayload.message().includes("declared request type"),
            "The rejection should name the declared request type: " + wrongPayload.message());
    }
}

@test:Config {}
function testPeerCallbackChannelValidatedAtRegistration() returns error? {
    // An async peer's reply self-injects into its callbackChannel, so the channel must be
    // declared — otherwise the reply is swallowed silently. Both misdeclarations fail at
    // module-init registration, before any instance runs.
    _ = check wfInternal:registerDurableAgentDecl("cbAgent", declTestModel,
        {role: "", instructions: "Assist."}, 8);
    _ = check wfInternal:registerDurableAgentEvent("cbAgent", "replies", string, string,
        "MULTI_EVENT");

    boolean|error undeclared = wfInternal:registerDurableAgentPeer("cbAgent", "askOther",
        "declTestAgent", {"wait": false, "callbackChannel": "answers"});
    test:assertTrue(undeclared is error, "An undeclared callbackChannel must be rejected");
    if undeclared is error {
        test:assertTrue(undeclared.message().includes("no data-event channel named 'answers'")
                && undeclared.message().includes("replies"),
            "The rejection should name the channel and list the declared ones: "
                + undeclared.message());
    }

    boolean|error noChannel = wfInternal:registerDurableAgentPeer("cbAgent", "askAnother",
        "declTestAgent", {"wait": false});
    test:assertTrue(noChannel is error, "wait = false without a callbackChannel must be rejected");
    if noChannel is error {
        test:assertTrue(noChannel.message().includes("no callbackChannel"),
            "The rejection should explain what is missing: " + noChannel.message());
    }

    // A declared channel registers cleanly.
    _ = check wfInternal:registerDurableAgentPeer("cbAgent", "askDeclared",
        "declTestAgent", {"wait": false, "callbackChannel": "replies"});
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
        {role: "", instructions: "You are an inventory assistant."}, 16, json, CoverageSummary);
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


isolated client class LongSleepMockModelProvider {
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
        return {role: ai:ASSISTANT, toolCalls: [{name: "sleep", arguments: {"seconds": 300}}]};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final LongSleepMockModelProvider longSleepMockModel = new;

final DurableAgent wakeableAgent = check new ({
    systemPrompt: {role: "", instructions: "You can pause with the sleep tool."},
    model: longSleepMockModel
});

@test:Config {groups: ["unit"], dependsOn: [testBuiltinDurableSleepTool]}
function testWakeSignalInterruptsSleep() returns error? {
    _ = check wfInternal:registerDurableAgentDecl("wakeableAgent", longSleepMockModel,
        {role: "", instructions: "You can pause with the sleep tool."}, 8);
    _ = check wfInternal:registerDurableAgentRunner("wakeableAgent");
    wakeableAgent.bindAgentName("wakeableAgent");

    string|error runResult = wakeableAgent.run("Wait for a long time.");
    if runResult is error {
        if runResult.message().includes("Workflow client not initialized") {
            return;
        }
        return runResult;
    }
    // Give the loop a moment to enter the 300-second durable sleep, then wake it
    // through the management API; the agent must finish promptly with the
    // interruption fed back to the model instead of sleeping out the timer.
    runtime:sleep(2);
    check management:wakeAgent(runResult);
    string result = check wakeableAgent.waitForResult(runResult);
    test:assertEquals(result,
        "Awake: Sleep was interrupted by a wake signal before the 300 seconds elapsed.",
        "The wake signal should end the built-in sleep early");
}

// Calls the two workflow-context builtins in one turn and answers with both results,
// so the test can assert what the model was actually told.
isolated client class ContextReadMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        if messages is ai:ChatMessage[] {
            string? workflowId = ();
            string? currentTime = ();
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatFunctionMessage {
                    if message.name == "getWorkflowId" {
                        workflowId = message.content;
                    } else if message.name == "getCurrentTime" {
                        currentTime = message.content;
                    }
                }
            }
            if workflowId is string && currentTime is string {
                return {role: ai:ASSISTANT, content: "id=" + workflowId + ";time=" + currentTime};
            }
        }
        return {
            role: ai:ASSISTANT,
            toolCalls: [
                {name: "getWorkflowId", arguments: {}},
                {name: "getCurrentTime", arguments: {}}
            ]
        };
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final ContextReadMockModelProvider contextReadMockModel = new;

final DurableAgent contextReadAgent = check new ({
    systemPrompt: {role: "", instructions: "Report your workflow id and the current time."},
    model: contextReadMockModel
});

@test:Config {groups: ["unit"], dependsOn: [testWakeSignalInterruptsSleep]}
function testBuiltinWorkflowContextTools() returns error? {
    _ = check wfInternal:registerDurableAgentDecl("contextReadAgent", contextReadMockModel,
        {role: "", instructions: "Report your workflow id and the current time."}, 8);
    _ = check wfInternal:registerDurableAgentRunner("contextReadAgent");
    contextReadAgent.bindAgentName("contextReadAgent");

    string|error runResult = contextReadAgent.run("Who are you and what time is it?");
    if runResult is error {
        if runResult.message().includes("Workflow client not initialized") {
            return;
        }
        return runResult;
    }
    string result = check contextReadAgent.waitForResult(runResult);
    // getWorkflowId must feed the model this very run's instance id - the reference
    // identifier an agent hands out - and getCurrentTime an ISO-8601 UTC instant.
    test:assertEquals(result.substring(0, 3 + runResult.length()), "id=" + runResult,
        "The built-in getWorkflowId tool should return the run's own instance id");
    int timeAt = <int>result.indexOf(";time=");
    string timeText = result.substring(timeAt + 6);
    time:Utc|error parsed = time:utcFromString(timeText);
    test:assertTrue(parsed is time:Utc,
        "The built-in getCurrentTime tool should return an ISO-8601 UTC instant, got: " + timeText);
}
