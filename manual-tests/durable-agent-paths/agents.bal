// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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
import ballerina/workflow;

final ai:Wso2ModelProvider model = check ai:getDefaultModelProvider();

final ai:SystemPrompt answerer = {role: "You answer questions.", instructions: "Answer accurately and briefly."};

// Result types: one agent per declared resultType, all query-only.

final workflow:DurableAgent textAgent = check new ({systemPrompt: answerer, model: model, inputType: ()});

final workflow:DurableAgent stringAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: string});

final workflow:DurableAgent intAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: int});

final workflow:DurableAgent booleanAgent = check new ({
    systemPrompt: answerer,
    model: model,
    inputType: (),
    resultType: boolean
});

final workflow:DurableAgent enumAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: Risk});

final workflow:DurableAgent recordAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: Verdict});

final workflow:DurableAgent optionalFieldsAgent = check new ({
    systemPrompt: answerer,
    model: model,
    inputType: (),
    resultType: Contact
});

final workflow:DurableAgent nestedAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: Invoice});

final workflow:DurableAgent recordArrayAgent = check new ({
    systemPrompt: answerer,
    model: model,
    inputType: (),
    resultType: Cities
});

final workflow:DurableAgent mapAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: WordLengths});

final workflow:DurableAgent annotatedAgent = check new ({
    systemPrompt: answerer,
    model: model,
    inputType: (),
    resultType: AnnotatedSummary
});

final workflow:DurableAgent unionAgent = check new ({
    systemPrompt: answerer,
    model: model,
    inputType: (),
    resultType: VerdictOrNote
});

final workflow:DurableAgent jsonAgent = check new ({systemPrompt: answerer, model: model, inputType: (), resultType: json});

// Inputs.

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {
        role: "You review orders.",
        instructions: string `Accept the order in the input when its quantity is at most 10 and checkStock shows
                enough units; otherwise refuse it. Put the reason in the note.`
    },
    model: model,
    inputType: Order,
    resultType: OrderDecision,
    activities: [checkStock]
});

final workflow:DurableAgent payloadAgent = check new ({
    systemPrompt: {role: "You count things.", instructions: "Answer with how many numbers the input payload holds."},
    model: model,
    resultType: int
});

// Capabilities.

final workflow:DurableAgent toolsAgent = check new ({
    systemPrompt: {
        role: "You are an order desk assistant.",
        instructions: "Use checkStock for stock questions. To reserve units, call reserveStock. Report what you did."
    },
    model: model,
    inputType: (),
    activities: [
        checkStock,
        {activity: reserveStock, bindings: {warehouse: "WH-1"}, description: "Reserves units of an item"}
    ]
});

final workflow:DurableAgent refundAgent = check new ({
    systemPrompt: {
        role: "You process refund requests.",
        instructions: string `Call issueRefund with the order id and amount in the request. Then say whether the
                refund was issued, or that it was refused and why.`
    },
    model: model,
    inputType: (),
    activities: [
        {activity: issueRefund, approvalPolicy: {userRoles: "FINANCE", title: "Approve the refund"}}
    ]
});

final workflow:DurableAgent currencyAgent = check new ({
    systemPrompt: {role: "You convert currencies.", instructions: "Use the convertCurrency tool and give the amount."},
    model: model,
    inputType: (),
    resultType: decimal,
    tools: [convertCurrency]
});

final workflow:DurableAgent expediteAgent = check new ({
    systemPrompt: {
        role: "You handle shipping requests.",
        instructions: string `Expedited shipping needs a manager: call approveExpedite with the order id and the
                customer's reason, then report the manager's decision and comment.`
    },
    model: model,
    inputType: (),
    resultType: ManagerApproval,
    humanTasks: {
        approveExpedite: {
            userRoles: "MANAGER",
            resultType: ManagerApproval,
            title: "Approve expedited shipping",
            description: "Asks a manager to approve expedited shipping. Pass the order id and the reason."
        }
    }
});

final workflow:DurableAgent chatAgent = check new ({
    systemPrompt: {
        role: "You are a store assistant.",
        instructions: string `Answer each chat message in one sentence. Use checkStock for stock questions.
                When the user says goodbye, call endConversation with a short farewell.`
    },
    model: model,
    inputType: (),
    activities: [checkStock],
    events: {
        chat: {request: string, response: string, cardinality: workflow:MULTI_EVENT}
    }
});

final workflow:DurableAgent researcherAgent = check new ({
    systemPrompt: {role: "You research cities.", instructions: "Answer questions about cities in two sentences."},
    model: model,
    inputType: ()
});

final workflow:DurableAgent coordinatorAgent = check new ({
    systemPrompt: {
        role: "You coordinate research.",
        instructions: "Delegate the question to the researcher, then summarize its answer in one sentence."
    },
    model: model,
    inputType: (),
    peers: [{agent: researcherAgent, description: "Answers questions about cities"}]
});

final workflow:DurableAgent auditingAgent = check new ({
    systemPrompt: {
        role: "You keep the audit log.",
        instructions: "Record the user's message with the auditLog tool, then confirm it was recorded."
    },
    model: model,
    inputType: (),
    peers: [{agent: auditLog, description: "Records a message in the audit log"}]
});

final workflow:DurableAgent rateAgent = check new ({
    systemPrompt: {role: "You quote exchange rates.", instructions: "Use fetchExchangeRate and give the rate."},
    model: model,
    inputType: (),
    activities: [{activity: fetchExchangeRate, retryPolicy: {maxRetries: 3, retryDelay: 1.0}}]
});

final workflow:DurableAgent limitedAgent = check new ({
    systemPrompt: {
        role: "You check stock.",
        instructions: "Call checkStock separately for each item, one call at a time, before answering."
    },
    model: model,
    inputType: (),
    activities: [checkStock],
    maxIter: 1
});

type Cities City[];

type WordLengths map<int>;

type VerdictOrNote Verdict|string;
