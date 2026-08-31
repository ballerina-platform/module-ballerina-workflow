// The graph is written at build time, so a step id evaluated per execution cannot be described.

import ballerina/workflow;

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    string chosen = "book-" + id;
    string booked = check ctx->callActivity(bookCarrier, {"id": id}, stepId = chosen);
    // ctx.sleep chooses step ids too — a plain method call, not a remote call.
    check ctx.sleep({seconds: 1}, stepId = chosen);
    _ = booked;
    return;
}

@workflow:Activity
function bookCarrier(string id) returns string|error {
    return id;
}
