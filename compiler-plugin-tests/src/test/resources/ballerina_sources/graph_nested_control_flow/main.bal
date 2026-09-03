// Control flow nested the way real routing logic nests: a while holding an if, whose arm holds
// another while and another if. What the graph must get right here is *lineage* — every step's
// parent chain names each enclosing construct in order, and ordinals count constructs of a kind
// across the whole body (if#1, if#2, while#1, while#2), not per nesting level — because a consumer
// reconstructs the drawing from parent links alone.

import ballerina/workflow;

@workflow:Workflow
function routeShipment(workflow:Context ctx, ShipmentRequest request) returns error? {
    int attempts = 0;
    while attempts < 3 {
        if request.premium {
            int retries = 0;
            while retries < 2 {
                string carrier = check ctx->callActivity(bookCarrier, {"id": request.id});
                _ = carrier;
                retries += 1;
            }
            if request.rush {
                string posted = check ctx->callActivity(postToLedger, {"id": request.id});
                _ = posted;
            } else {
                check ctx.sleep({seconds: 60});
            }
        } else {
            string notified = check ctx->callActivity(notifyWarehouse, {"id": request.id});
            _ = notified;
        }
        attempts += 1;
    }
    return;
}

type ShipmentRequest record {|
    string id;
    boolean premium;
    boolean rush;
|};

@workflow:Activity
function bookCarrier(string id) returns string|error {
    return id;
}

@workflow:Activity
function postToLedger(string id) returns string|error {
    return id;
}

@workflow:Activity
function notifyWarehouse(string id) returns string|error {
    return id;
}
