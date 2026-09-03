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

// WARNING (WORKFLOW_159 x2): the array forms still work — the declarations register and the
// channels drive sendData validation exactly as the mapping form does — but each array is
// flagged once as deprecated.
final workflow:DurableAgent arrayAgent = check new ({
    systemPrompt: {role: "Assistant", instructions: "Help."},
    model: chatModel,
    events: [
        {name: "chat", request: string, response: string, cardinality: workflow:MULTI_EVENT}
    ],
    humanTasks: [
        {name: "signoff", roles: "manager"}
    ]
});

public function main() returns error? {
    string agentId = check arrayAgent.run("Start");
    // The channel declared through the array form still validates: this send is clean...
    string turn = check arrayAgent.sendData(agentId, "chat", "hello");
    _ = turn;
}
