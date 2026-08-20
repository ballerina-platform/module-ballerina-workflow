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

// ============================================================================
// Durable agent run input (`inputType`) — declarations exercised end to end
// against the shared Temporal dev server through the real compiler-plugin
// codegen path. Each agent covers one shape of the input contract: a validated
// record payload, the open `json` default, and a query-only agent.
// ============================================================================

import ballerina/ai;
import ballerina/jballerina.java;
import ballerina/workflow;

# An order the agent reasons over. `note` carries a declared default, so a payload
# that omits it must still reach the model with the default filled in.
#
# + id - Order identifier
# + qty - Number of units
# + note - Handling note
public type AgentOrder record {|
    string id;
    int qty;
    string note = "standard";
|};

# A structured result the agent produces at the end of its run.
#
# + summary - What the agent concluded
# + score - Confidence score
public type AgentOrderSummary record {|
    string summary;
    int score;
|};

// Echoes the user turn straight back as the answer, so a test can read exactly
// what the framework put in front of the model — the query, and the structured
// payload the runner appends to it.
isolated client class PayloadEchoModelProvider {
    *ai:ModelProvider;

    isolated remote function chat(ai:ChatMessage[]|ai:ChatUserMessage messages,
            ai:ChatCompletionFunctions[] tools = [], string? stop = ())
            returns ai:ChatAssistantMessage|ai:Error {
        string lastUserTurn = "";
        if messages is ai:ChatMessage[] {
            foreach ai:ChatMessage message in messages {
                if message is ai:ChatUserMessage {
                    string|ai:Prompt content = message.content;
                    if content is string {
                        lastUserTurn = content;
                    }
                }
            }
        } else {
            string|ai:Prompt content = messages.content;
            if content is string {
                lastUserTurn = content;
            }
        }
        return {role: ai:ASSISTANT, content: lastUserTurn};
    }

    isolated remote function generate(ai:Prompt prompt, typedesc<anydata> td = <>)
            returns td|ai:Error = @java:Method {
        'class: "io.ballerina.lib.workflow.test.TestNatives",
        name: "mockGenerate"
    } external;
}

final PayloadEchoModelProvider payloadEchoModel = new;

# Validated payload: every `run` and management start must carry an `AgentOrder`,
# and the declared default is filled before the payload reaches the model turn.
final workflow:DurableAgent orderInputAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an order assistant."},
    model: payloadEchoModel,
    inputType: AgentOrder
});

# The `json` default: any payload shape is accepted without validation.
final workflow:DurableAgent openInputAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an order assistant."},
    model: payloadEchoModel
});

# `inputType: ()` — the query is this agent's only input; a payload has nowhere to go.
final workflow:DurableAgent queryOnlyAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an order assistant."},
    model: payloadEchoModel,
    inputType: ()
});

# A validated payload and a declared result type together: the run input is checked on
# the way in, and the loop's outcome is converted to `AgentOrderSummary` on the way out.
final workflow:DurableAgent orderSummaryAgent = check new ({
    systemPrompt: {role: "", instructions: "You are an order assistant."},
    model: payloadEchoModel,
    inputType: AgentOrder,
    resultType: AgentOrderSummary
});
