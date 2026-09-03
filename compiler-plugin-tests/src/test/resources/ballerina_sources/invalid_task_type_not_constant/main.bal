// A task's payloadType and resultType are published in the descriptor at build time and are
// what its payload and answer are checked against — so a type computed per execution can be
// neither described nor relied on. Both must name a type.

import ballerina/workflow;

type ApprovalDecision record {|
    boolean approved;
|};

type ApprovalPayload record {|
    string orderId;
|};

@workflow:Workflow
function shipOrder(workflow:Context ctx, string id) returns error? {
    typedesc<map<json>> computedPayloadType = typeof {"orderId": id};
    ApprovalDecision decision = check ctx->awaitHumanTask("approve", {"orderId": id},
        userRoles = "MANAGER", payloadType = computedPayloadType);
    // The named form is fine when it names a type.
    ApprovalDecision second = check ctx->awaitHumanTask("approveAgain", {"orderId": id},
        userRoles = "MANAGER", payloadType = ApprovalPayload, resultType = ApprovalDecision);
    _ = decision.approved || second.approved;
    return;
}
