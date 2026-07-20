// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/ai;
import ballerina/http;
import ballerina/io;
import ballerina/workflow;
import ballerina/workflow.management;

type OrderRequest record {|
    string orderId;
    string userPrompt;
|};

# The manager's decision for an expedited-shipping request.
#
# + approved - Whether expedited shipping was approved
# + comment - The manager's comment, relayed to the user by the agent
type ExpediteApproval record {|
    boolean approved;
    string comment;
|};

// The WSO2 default model provider, configured via `ballerina.ai.wso2ProviderConfig`
// in Config.toml (see README.md — the Ballerina VS Code extension can generate it).
final ai:Wso2ModelProvider orderModel = check ai:getDefaultModelProvider();

@workflow:Activity
function checkInventory(string item) returns string|error {
    io:println(string `[activity] checkInventory(${item})`);
    return item + " is in stock";
}

// A conversational durable agent with a human task in its toolbox. When the
// user asks to expedite shipping, the agent creates the "approveExpedite"
// human task — a durable sub-workflow — and suspends (for hours or days,
// without holding a thread) until a manager completes it, then reports the
// decision back to the user.
@workflow:DurableAgent
function orderAgent(workflow:AgentContext durableAgentContext, OrderRequest req) returns error? {
    check durableAgentContext.registerActivity(checkInventory);
    check durableAgentContext.registerUpdateEvents("chat", string);
    check durableAgentContext.registerHumanTask("approveExpedite", "MANAGER", ExpediteApproval,
            title = "Approve expedited shipping",
            description = "Requests a manager's approval to expedite the order's shipping. "
                + "Pass the order id and the customer's reason as fields.");
    check durableAgentContext.buildAndRun(req.userPrompt,
            systemPrompt = {
                role: string `You are the assistant for order ${req.orderId}.`,
                instructions: string `Use the checkInventory tool for product availability questions.
                        Expedited shipping requires manager approval: when the user asks to
                        expedite, call the approveExpedite tool with the order id and the
                        user's reason, then tell the user the manager's decision.
                        When the user says goodbye, call the endConversation tool with a
                        short farewell.`
            },
            model = orderModel, interaction = workflow:MULTI_EVENT, eventTimeout = {minutes: 30});
}

// The chat turn blocks while the agent waits on the manager, so the listener
// response timeout is disabled.
service /orders on new http:Listener(8085, timeout = 0) {

    # Starts a new order agent.
    # + return - The agent ID used by the other resources
    resource function post 'start() returns json|error {
        string agentId = check workflow:runDurableAgent(orderAgent, {orderId: "ORD-001", userPrompt: ""});
        return {agentId: agentId};
    }

    # Sends a chat message to the agent and returns its reply for that turn
    # (a Temporal Update under the hood). Blocks while the agent waits on the
    # manager's approval.
    # + agentId - The agent ID
    # + message - The user's chat message
    # + return - The agent's reply
    resource function post [string agentId]/chat(@http:Payload string message) returns string|error {
        return workflow:updateAgent(orderAgent, agentId, "chat", message);
    }

    # Lists the agent's pending human tasks (the manager's inbox).
    # + agentId - The agent ID
    # + return - Pending human task groups
    resource function get [string agentId]/tasks() returns management:HumanTaskGroup[]|error {
        return management:listPendingHumanTasks(agentId);
    }

    # Completes a pending approval task as the MANAGER role; the suspended
    # agent resumes and answers the user's pending chat turn.
    # + taskId - The human task ID (from the tasks resource)
    # + decision - The manager's decision
    # + return - A confirmation message
    resource function post tasks/[string taskId]/complete(ExpediteApproval decision) returns string|error {
        check management:completeHumanTask(taskId, decision, ["MANAGER"]);
        return "Task " + taskId + " completed";
    }
}
