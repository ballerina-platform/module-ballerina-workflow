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

// An in-package MCP server: most real agent tools are exposed over MCP, so the
// integration suite hosts one and consumes it through `ai:McpToolKit` to prove
// the whole durable path (toolkit expansion -> registration -> executeAgentTool
// -> MCP call) against a live server.

import ballerina/mcp;

listener mcp:Listener mcpTestListener = check new (9310);

@mcp:ServiceConfig {
    info: {name: "Workflow Integration MCP Server", version: "1.0.0"}
}
service mcp:Service /mcp on mcpTestListener {

    @mcp:Tool {description: "Quotes the price of an item"}
    remote function quoteItemPriceMcp(string item) returns string {
        return item + " costs $750";
    }
}
