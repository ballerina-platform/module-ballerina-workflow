// The options record travels positionally in the shapes the API advertises, and the step-id
// injection must survive every one of them: the record IS the forward-compatibility door, so a
// rewrite that drops it, or strands it behind a named argument, breaks the feature the record
// exists to provide. Positionally the record always FOLLOWS the step id — a mapping AT the
// step-id index is ill-typed source, covered by the invalid_options_record_at_step_id package.

import ballerina/workflow;

type ApprovalDecision record {|
    boolean approved;
|};

@workflow:Activity
function postToLedger(string id) returns string|error {
    return id;
}

@workflow:Workflow
function optionShapes(workflow:Context ctx, string id) returns error? {
    // Shape 1: the record after an explicit `()` step id, carrying a HumanReview retryPolicy.
    // The nil placeholder must be replaced POSITIONALLY, or the record becomes a positional
    // argument after a named one — and the descriptor must see the policy riding the record.
    string posted = check ctx->callActivity(postToLedger, {"id": id}, string, (),
        {
            // The comment above the field is part of the regression: a trivia-carrying key
            // reading would stop matching retryPolicy and silently un-gate the activity.
            retryPolicy: ["OPS"]
        });

    // Shape 2: the same door on awaitHumanTask, with an unknown field riding along.
    ApprovalDecision first = check ctx->awaitHumanTask("firstReview", ApprovalDecision, (),
        {userRoles: "MANAGER", "futureOption": true});

    // Shape 3: the record alongside a chosen step id, which must be preserved verbatim.
    ApprovalDecision second = check ctx->awaitHumanTask("secondReview", ApprovalDecision,
        "final-signoff", {userRoles: ["MANAGER", "AUDITOR"]});

    _ = posted.length() + (first.approved ? 1 : 0) + (second.approved ? 1 : 0);
    return;
}
