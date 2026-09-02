// The args map is a named-able parameter like any other: `callActivity(payClaim, args = {a: 3})`
// must validate exactly as the positional form does (ballerina-library#9092). Reading only the
// second argument slot reported every required parameter missing for the named form.

import ballerina/workflow;

@workflow:Activity
function payClaim(int a) returns error? {
    _ = a;
    return;
}

@workflow:Workflow
function claimFlow(workflow:Context ctx) returns error? {
    // The named form the issue reports — must be as valid as the positional one.
    () _ = check ctx->callActivity(payClaim, args = {a: 3});
    // Named args beside other named arguments, in any order.
    () _ = check ctx->callActivity(payClaim, stepId = "named-first", args = {a: 4});
    // And the named map's KEYS are still validated: `b` draws the extra-parameter error and
    // the missing `a` is still reported — proof the named form is read, not skipped.
    () _ = check ctx->callActivity(payClaim, args = {b: 5});
    return;
}
