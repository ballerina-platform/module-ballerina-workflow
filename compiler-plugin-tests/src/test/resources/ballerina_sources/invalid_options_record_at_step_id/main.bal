// An options record written one parameter early lands on the step-id index, where it is
// ill-typed (a step id is a string?). The plugin's job here is restraint: no rewrite that
// silently drops the record, no appended stepId that adds a bogus "redeclared argument"
// error, and no WORKFLOW_161 noise — the compiler's own type diagnostic is the message.

import ballerina/workflow;

type ApprovalDecision record {|
    boolean approved;
|};

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    ApprovalDecision decision = check ctx->awaitHumanTask("approve", {}, ApprovalDecision,
        {userRoles: "MANAGER"});
    _ = decision.approved;
    return;
}
