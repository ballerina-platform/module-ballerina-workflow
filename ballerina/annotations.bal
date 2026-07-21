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

# Marks a function as a workflow.
#
# ```ballerina
# @workflow:Workflow
# function orderProcess(Order input) returns OrderResult|error {
# }
# ```
public annotation Workflow on function;

# Marks a function as a workflow activity.
#
# ```ballerina
# @workflow:Activity
# function sendEmail(EmailRequest req) returns EmailResponse|error {
# }
# ```
public annotation Activity on function;

# Marks a function as a durable AI agent workflow. The agent runs as a durable
# workflow, so `@workflow:DurableAgentFunction` implies `@workflow:Workflow` and must not
# be combined with it.
#
# The function receives a `workflow:AgentContext` as its first parameter, an
# input record, and an optional events record (`record {| future<T> ... |}`).
# It configures the agent imperatively — registering activities and human tasks
# on the context — and finally calls `ctx.runDurableAgent(...)`, which takes the
# same configuration as a regular `ai:Agent` (system prompt, model, AI tools).
# The function returns `error?`; a durable agent has no direct return value (it
# may run for days).
#
# ```ballerina
# @workflow:DurableAgentFunction
# function processOrderAgent(workflow:AgentContext ctx, OrderRequest req) returns error? {
#     final ai:ModelProvider llm = check ai:getDefaultModelProvider();
#     check ctx.registerActivity(checkInventory);
#     check ctx.runDurableAgent(req.prompt,
#             systemPrompt = {role: "Order assistant", instructions: "Help the user with their order."},
#             model = llm);
# }
# ```
public annotation DurableAgentFunction on function;
