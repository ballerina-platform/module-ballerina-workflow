// Two steps cannot share an id — an id names one node of the graph — but duplicating one is easy
// (copy a step in the designer, paste a call), so the later step is described with a numeric suffix
// and the compiler warns rather than refusing to build. The suffixed id is what the descriptor
// publishes and what the call sends, so the graph and the execution still agree.

import ballerina/workflow;

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    string first = check ctx->callActivity(bookCarrier, {"id": id}, stepId = "book");
    string second = check ctx->callActivity(bookCarrier, {"id": id}, stepId = "book");
    _ = first + second;
    return;
}

@workflow:Activity
function bookCarrier(string id) returns string|error {
    return id;
}
