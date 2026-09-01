// An agent has no lexical control flow — the model decides what runs — so its graph is the
// star the designer draws: channels in, capabilities and the model out.

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider expenseModel = check new ("http://localhost:9099", "test-token");

type ExpenseClaim record {|
    string id;
    decimal amount;
|};

type ExpenseOutcome record {|
    string summary;
|};

type ApprovalDecision record {|
    boolean approved;
|};

@ai:AgentTool
isolated function validateClaim(string id) returns boolean|error {
    return id.length() > 0;
}

@workflow:Activity
function makePayment(string id, decimal amount) returns string|error {
    return id;
}

final workflow:DurableAgent expenseAgent = check new ({
    systemPrompt: {role: "Expense approval assistant", instructions: "Process expense claims."},
    model: expenseModel,
    inputType: ExpenseClaim,
    resultType: ExpenseOutcome,
    activities: [{activity: makePayment}],
    tools: [validateClaim],
    // The mapping form on purpose: the descriptor must read the primary declaration
    // style, or the agent map loses its whole inbound column.
    events: {billSubmitted: {request: string}},
    humanTasks: {approveExpense: {roles: "MANAGER", resultType: ApprovalDecision}}
});
