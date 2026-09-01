// A blank id is no id: the compiler generates one rather than naming a node after nothing, which is
// what an empty form field would otherwise produce.

import ballerina/workflow;

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    string stock = check ctx->callActivity(checkStock, {"id": id}, stepId = "");
    _ = stock;
    return;
}

@workflow:Activity
function checkStock(string id) returns string|error {
    return id;
}
