// Early exits, code-only branches, and truly empty ones — the three ways an `if` can mislead a
// drawing. A guard that only returns is kept with a terminal EXIT leaf: it is the one thing that
// can explain a run that completed without reaching later steps. A branch holding only
// non-durable code is kept too, with the code collapsed to one display node — the diagram should
// read like the source. Only a construct with nothing at all below it is pruned.

import ballerina/workflow;

@workflow:Workflow
function settleOrder(workflow:Context ctx, OrderRequest req) returns error? {
    string _ = check ctx->callActivity(validateOrder, {"id": req.id});

    // A guard: no steps, only an exit. Kept, with an EXIT leaf.
    if req.amount <= 0.0d {
        return;
    }

    // Only non-durable code: kept, its statements collapsed to one CODE node.
    if req.amount > 1000.0d {
        int flagged = req.id.length();
        int _ = flagged + 1;
    }

    // Truly empty: pruned, and its ordinal is not reused.
    if req.express {
    }

    string _ = check ctx->callActivity(chargeCard, {"id": req.id});

    // An arm with a step AND an exit: the return ends the arm, so flow to the next step exists
    // only on the skip path.
    if req.rush {
        string _ = check ctx->callActivity(bookCarrier, {"id": req.id});
        return;
    }

    string _ = check ctx->callActivity(archiveOrder, {"id": req.id});
    return;
}

type OrderRequest record {|
    string id;
    decimal amount;
    boolean express;
    boolean rush;
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
