// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/test;

// Replay coverage for the versioned turn answer: an agent's history recorded before an update was
// answered by the closing reply (the update completed at the first text, beside a tool call) must
// still replay on the current code, and so must a history recorded now.

// "Let me check" beside a tool call, then the answer; on "bye", a farewell beside endConversation.
isolated client class TalkativeThenAnswerMockModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        string lastChat = "";
        boolean checkedSinceChat = false;
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatUserMessage {
                    string|ai:Prompt content = message.content;
                    lastChat = content is string ? content : "";
                    checkedSinceChat = false;
                } else if message is ai:ChatFunctionMessage && message.name == "checkStock" {
                    checkedSinceChat = true;
                }
            }
        }
        if lastChat.includes("bye") {
            return {role: ai:ASSISTANT, content: "Goodbye", toolCalls: [{name: "endConversation", arguments: {}}]};
        }
        if !checkedSinceChat {
            return {role: ai:ASSISTANT, content: "Let me check that for you.",
                toolCalls: [{name: "checkStock", arguments: {"item": lastChat}}]};
        }
        return {role: ai:ASSISTANT, content: "Here is your answer about " + lastChat};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final TalkativeThenAnswerMockModelProvider talkativeThenAnswerAgentModel = new;

// Kept identical to the definition the legacy history fixture was captured with.
function legacyTurnChatAgent(handle ctx, AgentOrderInput input) returns error? {
    check registerActivity(ctx, checkStock);
    check registerAgentEvent(ctx, "chat", string, string);
    check buildAndRun(ctx, systemPrompt = {role: "", instructions: "Legacy turn chat agent."},
            model = talkativeThenAnswerAgentModel, maxIter = 4, interaction = MULTI_EVENT);
}

const LEGACY_TURN_HISTORY = "tests/resources/legacy-turn-answer.history.json";

@test:BeforeSuite
function setupReplayTests() returns error? {
    map<function> activities = {
        "checkStock": checkStock,
        "llmChat": llmChat,
        "generate": generate,
        "executeAgentTool": executeAgentTool
    };
    _ = check registerAgentWorkflowForTest(legacyTurnChatAgent, "legacyTurnChatAgent", activities);
}

// Drives the two turns the fixture records: a text-beside-tool-call turn, then the goodbye.
isolated function driveLegacyTurnConversation(string agentId) returns error? {
    _ = check updateAgentTurn(agentId, "chat", "laptop");
    _ = check updateAgentTurn(agentId, "chat", "bye");
    _ = check getWorkflowResult(agentId, 30);
}

@test:Config {groups: ["unit"]}
function testAHistoryRecordedBeforeTheTurnAnswerChangeReplays() returns error? {
    // Captured on the code before the change, where the update completed at the first text
    // (beside the tool call). The versioned path must take the same decisions on replay.
    check replayHistoryFile(LEGACY_TURN_HISTORY);
}

@test:Config {groups: ["unit"]}
function testAHistoryRecordedNowReplays() returns error? {
    map<anydata> input = {id: "agent-replay-now-001", request: "unused"};
    string agentId = check run(legacyTurnChatAgent, input);
    check driveLegacyTurnConversation(agentId);
    string history = check exportHistoryJson(agentId);
    // Marker payloads are base64 in the JSON, so the change id itself is not visible; a history
    // recorded now carries one more version marker than the legacy fixture (which has one).
    int markers = 0;
    int searchFrom = 0;
    while true {
        int? at = history.indexOf("EVENT_TYPE_MARKER_RECORDED", searchFrom);
        if at is () {
            break;
        }
        markers += 1;
        searchFrom = at + 1;
    }
    test:assertTrue(markers >= 2, "A history recorded now carries the version marker, found " + markers.toString());
    check replayHistoryJson(history);
}

isolated function exportHistoryJson(string workflowId) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "exportHistoryJson"
} external;

isolated function replayHistoryJson(string history) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "replayHistoryJson"
} external;

isolated function replayHistoryFile(string path) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "replayHistoryFile"
} external;
