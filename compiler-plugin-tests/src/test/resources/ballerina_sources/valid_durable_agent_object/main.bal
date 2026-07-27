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

type EscalationReq record {|
    string reason;
|};

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    return item.length() > 0;
}

@workflow:Activity
function reserveStock(string item, int quantity) returns string|error {
    return item + ":" + quantity.toString();
}

@ai:AgentTool
isolated function priceLookup(string item) returns decimal|error {
    return 10.5d;
}

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user place and track orders."},
    model: chatModel,
    activities: [
        checkInventory,
        {activity: reserveStock, name: "reserve", requiresApproval: true}
    ],
    tools: [priceLookup],
    events: [
        {name: "chat", request: string, response: string, cardinality: workflow:MULTI_EVENT},
        {name: "escalate", request: EscalationReq}
    ],
    humanTasks: [
        {name: "approval", roles: "manager", title: "Approve the order"}
    ],
    maxIter: 8
});
