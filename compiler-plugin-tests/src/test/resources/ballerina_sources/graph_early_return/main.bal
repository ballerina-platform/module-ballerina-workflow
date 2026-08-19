// Early returns and empty branches — the two ways an `if` can lie in a drawing. A guard that only
// returns holds no step, yet it is the one thing that can explain a run that completed without
// reaching the steps after it, so it is described (as a terminal RETURN node). A branch that holds
// nothing durable at all is not described: it is invisible to the runtime, and an empty box invites
// the question the graph cannot answer.

import ballerina/workflow;

@workflow:Workflow
function settleOrder(workflow:Context ctx, OrderRequest req) returns error? {
    string validated = check ctx->callActivity(validateOrder, {"id": req.id});
    _ = validated;

    // A guard: no steps, only an exit. Kept, with a RETURN leaf.
    if req.amount <= 0.0d {
        return;
    }

    // Nothing durable: not described at all.
    if req.amount > 1000.0d {
        int _ = req.id.length();
    }

    string charged = check ctx->callActivity(chargeCard, {"id": req.id});
    _ = charged;

    // An arm with a step AND an exit: the return ends that arm, so flow to the next step exists
    // only on the skip path.
    if req.express {
        string booked = check ctx->callActivity(bookCarrier, {"id": req.id});
        _ = booked;
        return;
    }

    string archived = check ctx->callActivity(archiveOrder, {"id": req.id});
    _ = archived;
    return;
}

type OrderRequest record {|
    string id;
    decimal amount;
    boolean express;
|};

@workflow:Activity
function validateOrder(string id) returns string|error {
    return id;
}

@workflow:Activity
function chargeCard(string id) returns string|error {
    return id;
}

@workflow:Activity
function bookCarrier(string id) returns string|error {
    return id;
}

@workflow:Activity
function archiveOrder(string id) returns string|error {
    return id;
}
