// Control-flow shapes the descriptor's graph has to describe. The motivating case is
// `postToLedger`, called from both arms of an `if`: two nodes, two site ids, one activity.

import ballerina/workflow;

type ShipmentRequest record {|
    string id;
    int quantity;
|};

type StockStatus record {|
    boolean inStock;
    int available;
|};

type PostingResult record {|
    string ref;
|};

type ApprovalDecision record {|
    boolean approved;
|};

type ShipmentEvents record {|
    future<ApprovalDecision> managerDecision;
    future<boolean> stockRestocked;
|};

@workflow:Workflow
function shipOrder(workflow:Context ctx, ShipmentRequest request) returns error? {
    StockStatus stock = check ctx->callActivity(checkStock, {"id": request.id});
    if stock.inStock {
        // Two call sites of one activity, one per arm: this is the pair the graph exists to
        // tell apart — postToLedger#1 here, postToLedger#2 below.
        PostingResult _ = check ctx->callActivity(postToLedger, {"id": request.id});
        string carrier = check ctx->callActivity(bookCarrier, {"id": request.id});
        _ = carrier;
    } else if stock.available > 0 {
        PostingResult _ = check ctx->callActivity(postToLedger, {"id": request.id});
    } else {
        ApprovalDecision decision = check ctx->awaitHumanTask("restockApproval", userRoles = "OPS");
        if decision.approved {
            check ctx.sleep({seconds: 30});
        }
    }
    return;
}

@workflow:Workflow
function retryShipments(workflow:Context ctx, ShipmentRequest request) returns error? {
    int attempts = 0;
    while attempts < 3 {
        // Inside a loop: one site, many executions — the graph node is annotated by the
        // execution count rather than duplicated.
        string carrier = check ctx->callActivity(bookCarrier, {"id": request.id});
        _ = carrier;
        attempts += 1;
    }
    foreach int index in 0 ..< request.quantity {
        string notified = check ctx->callActivity(notifyWarehouse, {"id": request.id, "line": index});
        _ = notified;
    }
    return;
}

@workflow:Workflow
function reconcileShipment(workflow:Context ctx, ShipmentRequest request,
        ShipmentEvents events) returns error? {
    do {
        string notified = check ctx->callActivity(notifyWarehouse, {"id": request.id, "line": 0});
        _ = notified;
        ApprovalDecision decision = check wait events.managerDecision;
        match decision.approved {
            true => {
                PostingResult _ = check ctx->callActivity(postToLedger, {"id": request.id});
            }
            false => {
                string carrier = check ctx->callActivity(bookCarrier, {"id": request.id});
                _ = carrier;
            }
        }
    } on fail error e {
        string failed = check ctx->callActivity(notifyWarehouse, {"id": request.id, "line": -1});
        _ = failed;
        return e;
    }
    return;
}

@workflow:Workflow
function orchestrateShipment(workflow:Context ctx, ShipmentRequest request) returns error? {
    // Awaiting another workflow's result is routed through an implicit activity so it stays
    // deterministic, so it appears in history and is a step the author wrote. (`workflow:run` and
    // `workflow:sendData` are compile errors here — the child-workflow remote methods replace them.)
    string child = check ctx->runChildWorkflow(retryShipments, request);
    anydata outcome = check workflow:getWorkflowResult(child, 30);
    _ = outcome;
    return;
}

@workflow:Workflow
function dispatchShipment(workflow:Context ctx, ShipmentRequest request) returns error? {
    // A child workflow belongs in the graph, but `runChildWorkflow` has no `stepId` parameter — so
    // this call must come out of the build untouched.
    string child = check ctx->runChildWorkflow(retryShipments, request);
    _ = child;
    return;
}

@workflow:Workflow
function namedSteps(workflow:Context ctx, ShipmentRequest request) returns error? {
    // A chosen id names this step in the graph, in the activity's recorded input, and in the
    // Temporal UI.
    string primary = check ctx->callActivity(bookCarrier, {"id": request.id}, stepId = "book-primary");
    _ = primary;
    // The ordinal is consumed either way, so naming the call above leaves this one at #2 — adding
    // or removing a name never renumbers a sibling.
    string fallback = check ctx->callActivity(bookCarrier, {"id": request.id});
    _ = fallback;
    return;
}

@workflow:Activity
function checkStock(string id) returns StockStatus|error {
    return {inStock: true, available: 1};
}

@workflow:Activity
function postToLedger(string id) returns PostingResult|error {
    return {ref: id};
}

@workflow:Activity
function bookCarrier(string id) returns string|error {
    return id;
}

@workflow:Activity
function notifyWarehouse(string id, int line) returns string|error {
    return id + ":" + line.toString();
}
