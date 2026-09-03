// A step id chosen at the call site is kept, and the descriptor's graph records the same id — so
// naming a step is a feature rather than a hazard. A chosen id is also stable in a way a generated
// one is not: adding an earlier call to the same activity shifts every later ordinal, while a name
// stays put.

import ballerina/workflow;

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    string stock = check ctx->callActivity(checkStock, {"id": id}, stepId = "stock-check");
    _ = stock;
    return;
}

@workflow:Activity
function checkStock(string id) returns string|error {
    return id;
}

// Regression: an unrelated client whose remote method merely shares a workflow-context
// operation name must not draw the step-id diagnostics for its ordinary arguments.
// This fixture package expects ZERO validator errors, so a false WORKFLOW_160/161 here
// fails the suite.
client class NotTheWorkflowContext {
    remote function callActivity(string plainArgument, string anotherOne) returns string {
        return plainArgument + anotherOne;
    }
}

final NotTheWorkflowContext lookalike = new;

function usesTheLookalike() returns string {
    // The second string would parse as a positional step id if the validator keyed on
    // the method name alone — and a dynamic value there would be WORKFLOW_160.
    string dynamicValue = "not-a-step-id";
    return lookalike->callActivity("x", dynamicValue);
}
