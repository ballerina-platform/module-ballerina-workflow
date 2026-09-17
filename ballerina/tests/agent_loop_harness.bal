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

// Test-only stand-ins for the imperative agent API the module no longer has. They drive the
// same natives the object-model runner uses, so the loop tests keep exercising the real loop
// without declaring an object per scenario.

type AgentLoopConfig record {|
    ai:SystemPrompt systemPrompt;
    ai:ModelProvider model;
    int maxIter = 16;
    EventCardinality interaction = SINGLE_EVENT;
    Duration? eventTimeout = ();
    int maxEventWaits = MAX_EVENT_WAITS;
|};

isolated function registerActivity(handle agentCtx, function activity, string? name = (),
        string? description = (), map<anydata|object {}>? bindings = (),
        ApprovalPolicy approvalPolicy = NoApproval, RetryPolicy retryPolicy = NoRetry) returns error? {
    return recordActivityTool(agentCtx, activity, name, description, bindings, approvalPolicy, retryPolicy);
}

isolated function registerAgentEvent(handle agentCtx, string name, typedesc<anydata> requestType,
        typedesc<anydata>? responseType = ()) returns error? {
    return registerAgentUpdateEvent(agentCtx, name, requestType, responseType);
}

isolated function registerHumanTask(handle agentCtx, string taskName, string|string[] userRoles,
        typedesc<anydata> resultType = anydata, string? title = (), string? description = (),
        Duration? timeout = (), typedesc<map<json>>? taskInputType = (),
        string|string[]? administratorRoles = ()) returns error? {
    return recordHumanTaskTool(agentCtx, taskName, userRoles, (), (), (), administratorRoles, (), resultType, title,
            description, timeout, taskInputType);
}

isolated function buildAndRun(handle agentCtx, string query = "", *AgentLoopConfig config) returns error? {
    check setAgentInteraction(agentCtx, config.interaction, config.eventTimeout, config.maxEventWaits);
    setAgentModelProvider(agentCtx, config.model);
    check registerAgentModelForContext(agentCtx);
    string toolDefsJson = check getAgentToolDefs(agentCtx);
    json toolDefs = check toolDefsJson.fromJsonString();
    AgentToolDef[] defs = check toolDefs.cloneWithType();
    error? result = runAgentLoop(agentCtx, getAgentWorkflowType(agentCtx), config.systemPrompt, config.maxIter,
            query, defs);
    finishAgentUpdates(agentCtx, result is error ? result.message() : ());
    return result;
}
