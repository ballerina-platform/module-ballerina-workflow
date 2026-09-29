// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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
import ballerina/http;
import ballerina/workflow;

final ai:Wso2ModelProvider agentModel = check new ("http://localhost:9099", "test-token");

final workflow:DurableAgent helpAgent = check new ({
    systemPrompt: {role: "Assistant", instructions: "Help."},
    model: agentModel
});

type AgentHolder record {|
    workflow:DurableAgent agent;
|};

final AgentHolder holder = {agent: helpAgent};

@workflow:Workflow
function orderFlow(workflow:Context ctx, string input) returns string|error {
    return input;
}

@workflow:Workflow
function parentFlow(workflow:Context ctx, string input) returns string|error {
    // ERROR WORKFLOW_167 x3: client reads inside a workflow body.
    string _ = check workflow:getResult(input);
    string _ = check workflow:waitForResult(input);
    workflow:InstanceStatus _ = check workflow:getStatus(input);
    // OK: an agent's getResult inside a workflow reads a child of this workflow.
    string|error agentRead = helpAgent.getResult(input);
    return agentRead is string ? agentRead : input;
}

service /orders on new http:Listener(9090) {
    resource function get [string id]() returns json|error {
        // WARNING WORKFLOW_169 x2: `check` straight on a non-blocking read in a resource function.
        string result = check workflow:getResult(id);
        string agentResult = check helpAgent.getResult(id);
        // WARNING WORKFLOW_169: the agent reached through a field is still an agent.
        string heldResult = check holder.agent.getResult(id);
        // OK: tested for progress first.
        string|error read = workflow:getResult(id);
        if read is workflow:WorkflowInProgressError {
            return {id, status: "IN_PROGRESS"};
        }
        // OK: a bounded wait is the caller's choice to block.
        string waited = check workflow:waitForResult(id, timeout = {seconds: 5});
        return {result, agentResult, heldResult, waited};
    }
}

service class StatusService {
    remote function status(string id) returns json|error {
        // WARNING WORKFLOW_169: the same in a remote function.
        string result = checkpanic workflow:getResult(id);
        return {result};
    }
}

public function script() returns error? {
    // ERROR WORKFLOW_168 x2: negative fields in a literal wait bound, for a workflow and an agent.
    string _ = check workflow:waitForResult("id", timeout = {seconds: -1});
    string _ = check helpAgent.waitForResult("id", timeout = {minutes: -2, seconds: 3});
    // ERROR WORKFLOW_168 x2: a parenthesised negative, and a negative through a field-held agent.
    string _ = check workflow:waitForResult("id", timeout = {seconds: (-1)});
    string _ = check holder.agent.waitForResult("id", timeout = {hours: -1});
    // OK outside a service: `check` is a plain script's choice.
    string _ = check workflow:getResult("id");
}
