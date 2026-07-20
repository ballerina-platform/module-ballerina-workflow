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
    string agentId = check workflow:runDurableAgent(stockCheckAgent,
            {id: "agent-int-001", request: "Is the laptop in stock?"});

    _ = check workflow:getWorkflowResult(agentId, 60);

    test:assertEquals(getAgentFinalResponse(agentId), "Stock check result: laptop is in stock",
            "Prompt-driven agent should complete the LLM -> tool -> LLM round trip");
}

@test:Config {}
function testDurableAgentMultiTurnConversation() returns error? {
    // MULTI_EVENT: FIFO re-armed chat waits across turns against the real server,
    // with per-turn responses observable via management:getAgentResponse.
    string agentId = check workflow:runDurableAgent(conversationalStockAgent,
            {id: "agent-int-conv-001", request: "hello"});

    test:assertTrue(waitForAgentResponse(agentId, "Turn 1 answer"),
            "Turn 1 answer should be observable while the agent waits for chat");

    _ = check workflow:updateAgentAsync(conversationalStockAgent, agentId, "chat", "how are you");
    test:assertTrue(waitForAgentResponse(agentId, "Echo: how are you"),
            "Turn 2 should consume the next chat message");

    _ = check workflow:updateAgentAsync(conversationalStockAgent, agentId, "chat", "ok bye");
    _ = check workflow:getWorkflowResult(agentId, 60);
    test:assertEquals(check management:getAgentResponse(agentId), "Conversation ended",
            "The model ends the conversation by answering without waiting");
}

@test:Config {}
function testDurableAgentUpdateConversation() returns error? {
    // updateAgent (Temporal Update) against the real server: each call delivers
    // the message and returns the answer of the turn that consumed it.
    string agentId = check workflow:runDurableAgent(conversationalStockAgent,
            {id: "agent-int-update-001", request: "hello"});

    string reply1 = check workflow:updateAgent(conversationalStockAgent, agentId, "chat", "how are you");
    test:assertEquals(reply1, "Echo: how are you",
            "updateAgent should return the turn's answer synchronously");

    string reply2 = check workflow:updateAgent(conversationalStockAgent, agentId, "chat", "ok bye");
    test:assertEquals(reply2, "Conversation ended",
            "The final answer should complete the last update");

    _ = check workflow:getWorkflowResult(agentId, 60);
}

@test:Config {}
function testDurableAgentChatDriven() returns error? {
    string agentId = check workflow:runDurableAgent(chatDrivenStockAgent,
            {id: "agent-int-002", request: "unused"});

    // Give the agent a moment to start and park on the chat event, then send it.
    runtime:sleep(2);
    _ = check workflow:updateAgentAsync(chatDrivenStockAgent, agentId, "chat", "Check availability of laptop");

    _ = check workflow:getWorkflowResult(agentId, 60);

    test:assertEquals(getAgentFinalResponse(agentId), "Stock check result: laptop is in stock",
            "Chat-driven agent should durably wait for the chat event, then complete");
}
