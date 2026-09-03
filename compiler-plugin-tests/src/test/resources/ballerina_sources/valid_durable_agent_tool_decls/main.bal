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

final ai:Wso2ModelProvider toolsModel = check new ("http://localhost:9099", "test-token");

@ai:AgentTool
isolated function priceLookup(string item) returns decimal|error {
    return 10.5d;
}

@ai:AgentTool
isolated function stockLookup(string item) returns boolean|error {
    return item.length() > 0;
}

isolated function plainQuote(string item) returns string {
    return item + " costs $500";
}

final ai:ToolConfig quoteTool = {
    name: "quoteTool",
    description: "Quotes the price of an item",
    caller: plainQuote
};

isolated class PricingToolKit {
    *ai:BaseToolKit;

    public isolated function getTools() returns ai:ToolConfig[] {
        return ai:getToolConfigs([priceLookup]);
    }
}

final PricingToolKit pricingToolKit = new;

// Every accepted tool shape: a bare @ai:AgentTool function, ToolDecl wrappers with
// gating over a function / an ai:ToolConfig, and toolkit / config variable references.
final workflow:DurableAgent toolsAgent = check new ({
    systemPrompt: {role: "Pricing assistant", instructions: "Answer pricing questions."},
    model: toolsModel,
    tools: [
        priceLookup,
        {tool: stockLookup, requiresApproval: true, userRoles: "finance"},
        {tool: quoteTool, requiresApproval: true, userRoles: ["finance", "manager"]},
        pricingToolKit
    ]
});
