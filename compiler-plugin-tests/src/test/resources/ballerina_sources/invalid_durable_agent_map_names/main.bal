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

final string channelName = "chat";
final string taskName = "signoff";

// ERROR (WORKFLOW_156 x2): a computed key has no static name, which the registration and
// the designer rendering need — for a channel and for a human task alike.
// ERROR (WORKFLOW_152): the async peer's callbackChannel names a channel the agent does
// not declare, so the peer's reply would be swallowed silently.
final workflow:DurableAgent computedAgent = check new ({
    systemPrompt: {role: "Assistant", instructions: "Help."},
    model: chatModel,
    events: {
        [channelName]: {request: string, response: string},
        replies: {request: string, response: string}
    },
    humanTasks: {
        [taskName]: {userRoles: "manager"}
    },
    peers: [
        {agent: helperAgent, name: "askHelper", 'wait: false, callbackChannel: "answers"}
    ]
});

final workflow:DurableAgent helperAgent = check new ({
    systemPrompt: {role: "Helper", instructions: "Help."},
    model: chatModel
});
