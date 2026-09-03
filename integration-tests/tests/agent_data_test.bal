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
// Durable agent data-event turns (`sendData`) — the payload contract, verified
// against the real Temporal server. The compiler plugin rejects the statically
// visible misuses (WORKFLOW_152/158); reaching the runtime checks that stand
// behind them needs the agent handed through a parameter, exactly as for run's
// input. The runtime validates against the TARGET instance's declaration, so
// what used to be a black hole (a send to an undeclared channel parked its
// update forever and waitForDataResult hung) is an immediate, named error.
// ================================================================================

import ballerina/test;
import ballerina/workflow;

function sendDataDynamically(workflow:DurableAgent agent, string instanceId, string eventName,
        json data) returns string|error {
    return agent.sendData(instanceId, eventName, data);
}

@test:Config {
    groups: ["integration"]
}
function testSendDataRejectsAnUndeclaredChannel() returns error? {
    string agentId = check orderChannelAgent.run("Start");

    string|error unknown = sendDataDynamically(orderChannelAgent, agentId, "nope",
            {id: "ORD-D1", qty: 1});
    test:assertTrue(unknown is error,
            "A send to an undeclared channel must fail immediately, not hang");
    if unknown is error {
        test:assertTrue(unknown.message().includes("declares no data-event channel named 'nope'")
                && unknown.message().includes("chat"),
                "The rejection should name the channel and list the declared ones: "
                        + unknown.message());
    }
}

@test:Config {
    groups: ["integration"]
}
function testSendDataRejectsAMistypedPayload() returns error? {
    string agentId = check orderChannelAgent.run("Start");

    string|error mismatch = sendDataDynamically(orderChannelAgent, agentId, "chat",
            {reference: "ORD-D2"});
    test:assertTrue(mismatch is error,
            "A payload that violates the channel's request type must be rejected at send");
    if mismatch is error {
        test:assertTrue(mismatch.message().includes("declared request type"),
                "The rejection should name the declared request type: " + mismatch.message());
    }
}

@test:Config {
    groups: ["integration"]
}
function testSendDataDeliversTheConvertedPayload() returns error? {
    // The payload is converted against the declared request type before delivery — not just
    // checked — so the record default (`note = "standard"`) is filled in, exactly as on the
    // run-input path. The echo model returns the turn it was shown, so the reply proves what
    // actually reached the model.
    string agentId = check orderChannelAgent.run("Start");

    string turn = check orderChannelAgent.sendData(agentId, "chat", {id: "ORD-D3", qty: 4});
    string reply = check orderChannelAgent.waitForDataResult(agentId, turn);

    test:assertTrue(reply.includes("ORD-D3"),
            "The typed payload must reach the model turn: " + reply);
    test:assertTrue(reply.includes("standard"),
            "The omitted field must arrive with the request type's declared default: " + reply);
}

@test:Config {
    groups: ["integration"]
}
function testSendDataArrayFormChannelStillWorks() returns error? {
    // The deprecated array form of `events` declares channels that register and validate
    // exactly as the mapping form's do — pinned here end-to-end so deprecation never turns
    // into silent breakage.
    string agentId = check chatDrivenStockAgent.run("");

    _ = check chatDrivenStockAgent.sendData(agentId, "chat", "Check availability of laptop");
    string result = check chatDrivenStockAgent.waitForResult(agentId);
    test:assertEquals(result, "Stock check result: laptop is in stock",
            "An array-form channel should still drive the agent");

    string|error unknown = sendDataDynamically(chatDrivenStockAgent, agentId, "nope", "hello");
    test:assertTrue(unknown is error,
            "Array-form declarations feed the same send-time validation");
}
