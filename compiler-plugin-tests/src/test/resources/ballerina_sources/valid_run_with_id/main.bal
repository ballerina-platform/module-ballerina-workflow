import ballerina/workflow;

type OrderInput record {|
    string orderId;
    int quantity;
|};

@workflow:Workflow
function recordInputWorkflow(workflow:Context ctx, OrderInput input) returns string|error {
    return input.orderId;
}

@workflow:Workflow
function noInputWorkflow(workflow:Context ctx) returns string|error {
    return "done";
}

public function startWorkflows() returns error? {
    OrderInput orderInput = {orderId: "ORD-1", quantity: 2};
    // The id is the second positional argument; the input follows it.
    string wf1 = check workflow:runWithId(recordInputWorkflow, "order-ORD-1", orderInput);
    // Named arguments in any order, with both policies.
    string wf2 = check workflow:runWithId(recordInputWorkflow, input = orderInput, instanceId = orderInput.orderId,
        ifRunning = workflow:USE_EXISTING, ifClosed = workflow:REJECT_DUPLICATE);
    // No input for a no-input workflow, and an explicit nil.
    string wf3 = check workflow:runWithId(noInputWorkflow, "no-input-1");
    string wf4 = check workflow:runWithId(noInputWorkflow, "no-input-2", ());
    _ = [wf1, wf2, wf3, wf4];
}
