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

isolated function sendAgentTurnWithRawContext(string agentId, string eventName, anydata data,
        anydata context, int seconds) returns anydata|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives"
} external;
