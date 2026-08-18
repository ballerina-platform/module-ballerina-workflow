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
