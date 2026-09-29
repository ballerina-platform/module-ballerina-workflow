// An agent has no lexical control flow — the model decides what runs — so its graph is the
// star the designer draws: channels in, capabilities and the model out.

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider expenseModel = check new ("http://localhost:9099", "test-token");

// ERROR: a blank label on a workflow.
@display {label: ""}
@workflow:Workflow
function expenseApproval(workflow:Context ctx, string id) returns string|error {
    return check ctx->callActivity(makePayment, {"id": id, "amount": 1.0d});
}

// ERROR: a whitespace-only label on an activity.
@display {label: "   "}
@workflow:Activity
function makePayment(string id, decimal amount) returns string|error {
    return id;
}

// ERROR: a blank template label on a durable agent.
@display {label: string ` `}
final workflow:DurableAgent expenseAgent = check new ({
    systemPrompt: {role: "Expense approval assistant", instructions: "Process expense claims."},
    model: expenseModel,
    activities: [makePayment]
});

const string EMPTY_LABEL = "";

// ERROR: an escape that evaluates to whitespace.
@display {label: "\t"}
@workflow:Activity
function refund(string id) returns string|error {
    return id;
}

// ERROR: a reference to a blank constant.
@display {label: EMPTY_LABEL}
@workflow:Activity
function notify(string id) returns string|error {
    return id;
}

// OK: an unrelated function with a blank label is not the descriptor's business.
@display {label: ""}
function helper() {
}
