import ballerina/workflow;

type ExpenseRequest record {|
    string id;
    decimal amount;
    string? note;
|};

type ApprovalDecision record {|
    boolean approved;
    string comment?;
|};

type PostingResult record {|
    string ref;
    int sequence;
|};

type OrderEvents record {|
    future<ApprovalDecision> approval;
|};

@workflow:Workflow
function expenseApproval(workflow:Context ctx, ExpenseRequest expense) returns error? {
    ApprovalDecision decision = check ctx->awaitHumanTask("managerApproval", userRoles = "MANAGER");
    if decision.approved {
        PostingResult|error posting = ctx->callActivity(postToLedger,
            {"expense": expense}, retryPolicy = "FINANCE");
        _ = check posting;
        xml report = check ctx->callActivity(fetchAudit, {});
        _ = report;
        json extra = check ctx->callActivity(freeform, {});
        _ = extra;
    }
    return;
}

@workflow:Workflow
function orderFlow(workflow:Context ctx, OrderEvents events) returns error? {
    // A review activity declared positionally: the typedesc is supplied explicitly, so
    // the retry policy lands as the fourth argument rather than a named one.
    PostingResult _ = check ctx->callActivity(postToLedger, {"expense": {id: "x", amount: 1, note: ()}},
        PostingResult, "OPS");
    // The descriptor captures the events record from the signature; waits are
    // exercised by other test packages.
    return;
}

@workflow:Activity
function postToLedger(ExpenseRequest expense, int retries = 1) returns PostingResult|error {
    return {ref: expense.id, sequence: retries};
}

@workflow:Activity
function fetchAudit() returns xml|error {
    return xml `<audit/>`;
}

@workflow:Activity
function freeform() returns json|error {
    return {ok: true};
}
