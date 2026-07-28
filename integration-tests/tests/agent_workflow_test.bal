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

    _ = check conversationalStockAgent.sendData(agentId, "chat", "how are you");
    test:assertTrue(waitForAgentResponse(agentId, "Echo: how are you"),
            "Turn 2 should consume the next chat message");

    _ = check conversationalStockAgent.sendData(agentId, "chat", "ok bye");
    string _ = check conversationalStockAgent.waitForResult(agentId);
    test:assertEquals(check management:getAgentResponse(agentId), "Conversation ended",
            "The model ends the conversation by answering without waiting");
}

@test:Config {}
function testDurableAgentEventTurnConversation() returns error? {
    // Token-correlated event turns against the real server: each sendData delivers
    // the message and waitForDataResult returns the answer of that turn.
    string agentId = check conversationalStockAgent.run("hello");

    string turn1 = check conversationalStockAgent.sendData(agentId, "chat", "how are you");
    string reply1 = check conversationalStockAgent.waitForDataResult(agentId, turn1);
    test:assertEquals(reply1, "Echo: how are you",
            "waitForDataResult should return the turn's answer");

    string turn2 = check conversationalStockAgent.sendData(agentId, "chat", "ok bye");
    string reply2 = check conversationalStockAgent.waitForDataResult(agentId, turn2);
    test:assertEquals(reply2, "Conversation ended",
            "The final answer should complete the last turn");

    string _ = check conversationalStockAgent.waitForResult(agentId);
}

@test:Config {}
function testDurableAgentChatDriven() returns error? {
    string agentId = check chatDrivenStockAgent.run("");

    // The agent parks durably on the chat event — the wait is published, so the
    // activity tree shows WHERE the agent is halted (a WAITING DATA node), exactly
    // as for a workflow blocked on `wait dataEvents.<name>`.
    management:ActivityTreeNode? waitingNode = ();
    decimal elapsed = 0.0d;
    while elapsed < 10.0d {
        management:ActivityTreeNode[] nodes = check management:getActivityTree(agentId, "");
        foreach management:ActivityTreeNode node in nodes {
            if node.name == "chat" && node.'type == management:DATA {
                waitingNode = node;
            }
        }
        if waitingNode is management:ActivityTreeNode {
            break;
        }
        runtime:sleep(0.3d);
        elapsed += 0.3d;
    }
    test:assertTrue(waitingNode is management:ActivityTreeNode,
        "The agent's chat-event wait should appear as a tree node while it is halted");
    if waitingNode is management:ActivityTreeNode {
        test:assertEquals(waitingNode.status, "WAITING",
            "An unanswered agent event wait must report WAITING");
    }

    _ = check chatDrivenStockAgent.sendData(agentId, "chat", "Check availability of laptop");

    string result = check chatDrivenStockAgent.waitForResult(agentId);
    test:assertEquals(result, "Stock check result: laptop is in stock",
            "Chat-driven agent should durably wait for the chat event, then complete");

    // The event arrived as a Temporal update (sendData): the waiting node completes
    // in place, exactly as a signal-delivered data event would.
    management:ActivityTreeNode? chatNode = ();
    int chatNodeCount = 0;
    management:ActivityTreeNode[] finalNodes = check management:getActivityTree(agentId, "");
    foreach management:ActivityTreeNode node in finalNodes {
        if node.name == "chat" && node.'type == management:DATA {
            chatNode = node;
            chatNodeCount += 1;
        }
    }
    test:assertEquals(chatNodeCount, 1, "The wait and its event must merge into a single node");
    if chatNode is management:ActivityTreeNode {
        test:assertEquals(chatNode.status, "COMPLETED",
            "The waiting node completes in place when the event arrives");
    }
}
