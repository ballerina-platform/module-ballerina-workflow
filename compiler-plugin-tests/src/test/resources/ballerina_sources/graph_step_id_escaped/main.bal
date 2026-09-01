// A chosen step id is decoded from its literal (escapes and all) when the graph is built, and then
// spliced back into source by the injector. An id containing the characters a string literal must
// escape — quotes, backslashes, line breaks — therefore round-trips through both: the graph carries
// the decoded value, and the rewritten call still parses to the same value.

import ballerina/workflow;

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    string stock = check ctx->callActivity(checkStock, {"id": id}, stepId = "q\"uote b\\ack\nline");
    _ = stock;
    return;
}

@workflow:Activity
function checkStock(string id) returns string|error {
    return id;
}
