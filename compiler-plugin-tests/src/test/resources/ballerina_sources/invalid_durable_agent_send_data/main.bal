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

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider chatModel = check new ("http://localhost:9099", "test-token");

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    return item.length() > 0;
}

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    activities: [checkInventory],
    events: [
        {name: "chat", request: string, response: string},
        {name: "notify", request: string}
    ]
});

public function main() returns error? {
    string id = check orderAgent.run("hi");

    // OK: duplex channel — the token reads the turn's answer.
    string chatTurn = check orderAgent.sendData(id, "chat", "hello");
    string answer = check orderAgent.waitForDataResult(id, chatTurn);

    // OK: one-way channel with the token discarded.
    _ = check orderAgent.sendData(id, "notify", "fyi");

    // ERROR (WORKFLOW_153): one-way channel, but the token is kept to read a result.
    string notifyTurn = check orderAgent.sendData(id, "notify", "fyi again");

    // ERROR (WORKFLOW_152): the agent declares no channel named "nosuch".
    _ = check orderAgent.sendData(id, "nosuch", "x");

    _ = answer;
    _ = notifyTurn;
}
