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

import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/observe as observability;
import ballerina/observe.mockextension as mock;
import ballerina/test;
import ballerina/workflow.internal as wfInternal;
import ballerina/workflow.management;

// A chat turn may carry its sender's trace context as an extra update argument. It is telemetry only:
// a history carrying it must replay, and a malformed one must never stall the agent.

@test:Config {groups: ["unit"]}
function testAMalformedSenderContextNeverStallsTheAgent() returns error? {
    string agentId = check run(legacyTurnChatAgent, {id: "turn-context-malformed-001", request: "unused"});

    // Each would fail the workflow task on every retry if the handler read it as a typed map.
    anydata first = check sendAgentTurnWithRawContext(agentId, "chat", "laptop", "not-a-map", 30);
    test:assertTrue(first is string && first.includes("laptop"), "a non-map context is ignored: " + first.toString());
    anydata second = check sendAgentTurnWithRawContext(agentId, "chat", "phone", {traceId: 42, spanId: true}, 30);
    test:assertTrue(second is string && second.includes("phone"), "non-string ids are ignored: " + second.toString());
    anydata bye = check sendAgentTurnWithRawContext(agentId, "chat", "bye", {traceId: "zz", spanId: "x"}, 30);
    test:assertEquals(bye, "Goodbye", "invalid ids are ignored");
    _ = check getWorkflowResult(agentId, 30);

    // The malformed arguments are in the history; replaying it must take the same decisions.
    check replayHistoryJson(check exportHistoryJson(agentId));
}

@test:Config {groups: ["observe"]}
function testAHistoryWhoseTurnsCarryTheSendersContextReplays() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string agentId = check run(legacyTurnChatAgent, {id: "turn-context-replay-001", request: "unused"});

    int requestSpanId = check observability:startSpan("replayed chat request");
    _ = check updateAgentTurn(agentId, "chat", "laptop");
    _ = check updateAgentTurn(agentId, "chat", "bye");
    check observability:finishSpan(requestSpanId);
    _ = check getWorkflowResult(agentId, 30);

    // The turns did carry the context: their model calls are in the request's trace.
    mock:Span request = check spanNamedInAnyService("replayed chat request");
    mock:Span[] turnSpans = [];
    foreach int attempt in 0 ..< 20 {
        turnSpans = from mock:Span span in mock:getFinishedSpans("workflow")
            where span.traceId == request.traceId && span.tags["workflow.instance.id"] == agentId
            select span;
        if hasOperation(turnSpans, "agent.model_call llmChat") {
            break;
        }
        runtime:sleep(0.5);
    }
    test:assertTrue(hasOperation(turnSpans, "agent.model_call llmChat"), "the turns recorded under the request");

    // Replay ignores the context, so the history replays on the same decisions.
    check replayHistoryJson(check exportHistoryJson(agentId));
}

@test:Config {groups: ["observe"]}
function testATurnSentOutsideAnySpanStaysInTheRunsTrace() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string runId = check run(chatStockAgent, {id: "observe-turn-nospan-001", request: "unused"});
    string token = check agentTurnDriver.sendData(runId, "chat", "Check availability of laptop");
    string _ = check agentTurnDriver.waitForDataResult(runId, token);
    _ = check getWorkflowResult(runId, 30);

    mock:Span startSpan = check findUnitSpan("start_workflow", "workflow.instance.id", runId);
    mock:Span[] story = check workerSpansOf(runId, 4);
    test:assertTrue(hasOperation(story, "agent.model_call llmChat"), "the turn's model call is recorded");
    foreach mock:Span span in story {
        test:assertEquals(span.traceId, startSpan.traceId,
                string `'${span.operationName}' of a turn with no sender span stays in the run's trace`);
    }
}

@test:Config {groups: ["observe"]}
function testTheFirstTurnRecordsUnderTheSpanThatStartedTheAgent() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    int requestSpanId = check observability:startSpan("agent start request");
    string agentId = check run(stockAgent, {id: "turn-context-first-001", request: "Check availability of laptop"});
    check observability:finishSpan(requestSpanId);
    _ = check getWorkflowResult(agentId, 30);

    mock:Span request = check spanNamedInAnyService("agent start request");
    mock:Span[] turnSpans = spansInTrace(request.traceId, agentId, "agent.model_call llmChat");
    test:assertTrue(hasOperation(turnSpans, "agent.model_call llmChat"), "the first turn recorded under the starter");

    // The run itself still opens in the instance's own trace; only its first turn moved.
    mock:Span[] story = check workerSpansOf(agentId, 2);
    mock:Span[] runSpans = story.filter(span => span.operationName.startsWith("workflow "));
    test:assertEquals(runSpans.length(), 1, "the run span was recorded");
    test:assertNotEquals(runSpans[0].traceId, request.traceId, "the run stays in the instance's trace");
    check replayHistoryJson(check exportHistoryJson(agentId));
}

@test:Config {groups: ["observe"]}
function testSendDataToAnAgentRecordsTheTurnUnderTheSender() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string agentId = check run(chatStockAgent, {id: "turn-context-senddata-001", request: "unused"});
    int requestSpanId = check observability:startSpan("agent sendData request");
    check sendData(chatStockAgent, agentId, "chat", "Check availability of laptop");
    check observability:finishSpan(requestSpanId);
    _ = check getWorkflowResult(agentId, 30);

    mock:Span request = check spanNamedInAnyService("agent sendData request");
    mock:Span[] turnSpans = spansInTrace(request.traceId, agentId, "agent.model_call llmChat");
    test:assertTrue(hasOperation(turnSpans, "agent.model_call llmChat"), "the signalled turn recorded under the sender");
    check replayHistoryJson(check exportHistoryJson(agentId));
}

final DurableAgent observedSideTurnAgent = check new ({
    systemPrompt: {role: "", instructions: "Chat while you work."},
    model: sideTurnMockModel,
    events: {chat: {request: string, response: string, cardinality: MULTI_EVENT}}
});

@test:Config {groups: ["observe"]}
function testASideTurnRecordsUnderTheRequestThatAskedIt() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    _ = check wfInternal:registerDurableAgentDecl("observedSideTurnAgent", sideTurnMockModel,
        {role: "", instructions: "Chat while you work."}, 8);
    _ = check wfInternal:registerDurableAgentEvent("observedSideTurnAgent", "chat", string, string,
        "MULTI_EVENT");
    _ = check wfInternal:registerDurableAgentRunner("observedSideTurnAgent");
    observedSideTurnAgent.bindAgentName("observedSideTurnAgent");
    string agentId = check observedSideTurnAgent.run("Start the long job.");
    // The next message is a side turn only once the loop is parked on its durable sleep.
    check waitForHistory(agentId, "EVENT_TYPE_TIMER_STARTED", "the agent parked on its sleep");

    int requestSpanId = check observability:startSpan("side turn request");
    string token = check observedSideTurnAgent.sendData(agentId, "chat", "status please?");
    string answer = check observedSideTurnAgent.waitForDataResult(agentId, token);
    check observability:finishSpan(requestSpanId);
    test:assertTrue(answer.startsWith("SIDE:"), "a side turn answered: " + answer);

    mock:Span request = check spanNamedInAnyService("side turn request");
    mock:Span[] sideCalls = [];
    foreach int attempt in 0 ..< 20 {
        sideCalls = from mock:Span span in mock:getFinishedSpans("workflow")
            where span.traceId == request.traceId && span.operationName.startsWith("activity ")
                && span.operationName.includes("llmChat")
            select span;
        if sideCalls.length() > 0 {
            break;
        }
        runtime:sleep(0.5);
    }
    test:assertEquals(sideCalls.length(), 1, "the side turn's model call recorded under the request");

    check management:wakeAgent(agentId);
    // The park ends in the workflow task that takes the wake; "bye" sent before that would be a side turn too.
    check waitForHistory(agentId, "EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED", "the agent took the wake",
            completedAfter = true);
    string byeToken = check observedSideTurnAgent.sendData(agentId, "chat", "bye");
    string farewell = check observedSideTurnAgent.waitForDataResult(agentId, byeToken);
    test:assertEquals(farewell, "Done SAW_ASIDE", "the next turn saw the side turn's answer");
    string result = check observedSideTurnAgent.waitForResult(agentId);
    test:assertEquals(result, "Done SAW_ASIDE", "the side turn was merged and the agent finished");
    check replayHistoryJson(check exportHistoryJson(agentId));
}

// Waits until the instance's history has `eventType` (and, with `completedAfter`, a completed workflow task after
// its last one), so a test acts on a state the run reached rather than on a guess at how long it takes.
function waitForHistory(string instanceId, string eventType, string what, boolean completedAfter = false)
        returns error? {
    foreach int attempt in 0 ..< 60 {
        json history = check (check exportHistoryJson(instanceId)).fromJsonString();
        json[] events = check (check history.events).ensureType();
        int last = -1;
        boolean completed = false;
        foreach int i in 0 ..< events.length() {
            json event = events[i];
            json kind = check event.eventType;
            if kind == eventType {
                last = i;
                completed = false;
            } else if last >= 0 && kind == "EVENT_TYPE_WORKFLOW_TASK_COMPLETED" {
                completed = true;
            }
        }
        if last >= 0 && (!completedAfter || completed) {
            return;
        }
        runtime:sleep(0.25);
    }
    return error(string `timed out waiting until ${what}`);
}

// The instance's spans in `traceId`, once one named `operationName` has finished.
function spansInTrace(string traceId, string instanceId, string operationName) returns mock:Span[] {
    mock:Span[] found = [];
    foreach int attempt in 0 ..< 20 {
        found = from mock:Span span in mock:getFinishedSpans("workflow")
            where span.traceId == traceId && span.tags["workflow.instance.id"] == instanceId
            select span;
        if hasOperation(found, operationName) {
            break;
        }
        runtime:sleep(0.5);
    }
    return found;
}

isolated function sendAgentTurnWithRawContext(string agentId, string eventName, anydata data,
        anydata context, int seconds) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;
