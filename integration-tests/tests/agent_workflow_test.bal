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

import ballerina/jballerina.java;
import ballerina/lang.runtime;
import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// Probe: reads the final response the agent recorded on completion (agents have
// no workflow return value).
isolated function getAgentFinalResponse(string workflowId) returns string? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.AgentResponseStore",
    name: "getFinalResponse"
} external;

// Polls until the agent's latest recorded response equals `expected`.
function waitForAgentResponse(string workflowId, string expected) returns boolean {
    foreach int i in 0 ..< 40 {
        string?|error response = management:getAgentResponse(workflowId);
        if response is string && response == expected {
            return true;
        }
        runtime:sleep(0.5);
    }
    return false;
}

@test:Config {}
function testDurableAgentPromptDriven() returns error? {
    string agentId = check stockCheckAgent.run("Is the laptop in stock?");

    string result = check stockCheckAgent.waitForResult(agentId);
    test:assertEquals(result, "Stock check result: laptop is in stock",
            "Prompt-driven agent should complete the LLM -> tool -> LLM round trip");
    test:assertEquals(getAgentFinalResponse(agentId), "Stock check result: laptop is in stock",
            "The recorded final response should match the workflow result");
}

@test:Config {}
function testDurableAgentMultiTurnConversation() returns error? {
    // MULTI_EVENT: FIFO re-armed chat waits across turns against the real server,
    // with per-turn responses observable via management:getAgentResponse.
    string agentId = check conversationalStockAgent.run("hello");

    test:assertTrue(waitForAgentResponse(agentId, "Turn 1 answer"),
            "Turn 1 answer should be observable while the agent waits for chat");

    _ = check conversationalStockAgent.sendEvent(agentId, "chat", "how are you");
    test:assertTrue(waitForAgentResponse(agentId, "Echo: how are you"),
            "Turn 2 should consume the next chat message");

    _ = check conversationalStockAgent.sendEvent(agentId, "chat", "ok bye");
    _ = check conversationalStockAgent.waitForResult(agentId);
    test:assertEquals(check management:getAgentResponse(agentId), "Conversation ended",
            "The model ends the conversation by answering without waiting");
}

@test:Config {}
function testDurableAgentEventTurnConversation() returns error? {
    // Token-correlated event turns against the real server: each sendEvent delivers
    // the message and waitForEventResult returns the answer of that turn.
    string agentId = check conversationalStockAgent.run("hello");

    string turn1 = check conversationalStockAgent.sendEvent(agentId, "chat", "how are you");
    string reply1 = check conversationalStockAgent.waitForEventResult(agentId, turn1);
    test:assertEquals(reply1, "Echo: how are you",
            "waitForEventResult should return the turn's answer");

    string turn2 = check conversationalStockAgent.sendEvent(agentId, "chat", "ok bye");
    string reply2 = check conversationalStockAgent.waitForEventResult(agentId, turn2);
    test:assertEquals(reply2, "Conversation ended",
            "The final answer should complete the last turn");

    _ = check conversationalStockAgent.waitForResult(agentId);
}

@test:Config {}
function testDurableAgentChatDriven() returns error? {
    string agentId = check chatDrivenStockAgent.run("");

    // Give the agent a moment to start and park on the chat event, then send it.
    runtime:sleep(2);
    _ = check chatDrivenStockAgent.sendEvent(agentId, "chat", "Check availability of laptop");

    string result = check chatDrivenStockAgent.waitForResult(agentId);
    test:assertEquals(result, "Stock check result: laptop is in stock",
            "Chat-driven agent should durably wait for the chat event, then complete");
}
