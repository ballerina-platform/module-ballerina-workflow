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

// Tool authorization is enforced by the ai:Agent run loop, which durable agents do not
// use — declaring such a tool on a durable agent must be rejected instead of running it
// unauthenticated.
@ai:AgentTool {
    auth: {
        baseAuthUrl: "https://auth.example.com",
        clientId: "client-1",
        redirectUri: "https://app.example.com/callback",
        scopes: ["orders:read"]
    }
}
isolated function lookupOrder(string id) returns string {
    return "order " + id;
}

@ai:AgentTool {
    auth: {
        baseAuthUrl: "https://auth.example.com",
        clientId: "client-1",
        redirectUri: "https://app.example.com/callback",
        scopes: "orders:write"
    }
}
isolated function cancelOrder(string id) returns string {
    return "cancelled " + id;
}

@ai:AgentTool
isolated function plainTool(string id) returns string {
    return id;
}

// A local annotation that happens to be named AgentTool with an auth field — NOT the ai
// module's annotation, so it must not be flagged.
type LocalToolConfig record {|
    anydata auth?;
|};

annotation LocalToolConfig AgentTool on function;

@AgentTool {
    auth: {scopes: ["local:read"]}
}
isolated function locallyAnnotatedTool(string id) returns string {
    return id;
}

// ERROR x2: the bare reference and the ToolDecl entry both carry an auth requirement;
// the un-authed tool passes.
final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    tools: [
        lookupOrder,
        {tool: cancelOrder, requiresApproval: true},
        plainTool,
        locallyAnnotatedTool
    ]
});
