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

final ai:Agent supportAgent = check new (
    systemPrompt = {role: "assistant", instructions: "help"},
    model = chatModel,
    tools = []
);

// Invalid: direct model-provider and agent calls inside a @Workflow function.
// LLM calls are non-deterministic and must be wrapped in an @workflow:Activity.
@workflow:Workflow
function classifyOrder(workflow:Context ctx, string orderText) returns string|error {
    ai:ChatAssistantMessage message = check chatModel->chat({role: ai:USER, content: orderText});
    string agentAnswer = check supportAgent.run(orderText);
    return (message.content ?: "") + agentAnswer;
}

// Valid: the same calls wrapped in @workflow:Activity functions.
@workflow:Activity
function classifyWithModel(string orderText) returns string|error {
    ai:ChatAssistantMessage message = check chatModel->chat({role: ai:USER, content: orderText});
    return message.content ?: "";
}

@workflow:Activity
function askSupportAgent(string orderText) returns string|error {
    return check supportAgent.run(orderText);
}
