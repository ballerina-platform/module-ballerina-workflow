import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider agentModel = check new ("http://localhost:9099", "test-token");

type OrderInput record {|
    string orderId;
    int quantity;
|};

final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help."},
    model: agentModel,
    inputType: OrderInput
});

@workflow:Workflow
function recordInputWorkflow(workflow:Context ctx, OrderInput input) returns string|error {
    return input.orderId;
}

@workflow:Workflow
function noInputWorkflow(workflow:Context ctx) returns string|error {
    return "done";
}

function notAWorkflow(string s) returns string {
    return s;
}

@workflow:Workflow
function parentWorkflow(workflow:Context ctx, string input) returns string|error {
    // ERROR WORKFLOW_138: a client verb inside a workflow body.
    string child = check workflow:runWithId(noInputWorkflow, "child-1");
    return child;
}

public function startWorkflows() returns error? {
    // ERROR WORKFLOW_130: not a workflow function.
    string wf1 = check workflow:runWithId(notAWorkflow, "id-1", "x");
    // ERROR WORKFLOW_131: the input (third argument) does not match the declared record.
    string wf2 = check workflow:runWithId(recordInputWorkflow, "id-2", "not a record");
    // ERROR WORKFLOW_132: an input for a workflow that declares none.
    string wf3 = check workflow:runWithId(noInputWorkflow, "id-3", {unexpected: true});
    // ERROR WORKFLOW_166 x3: blank, reserved prefix, too long.
    string wf4 = check workflow:runWithId(noInputWorkflow, "");
    string wf5 = check workflow:runWithId(noInputWorkflow, "humantask-1");
    string wf6 = check workflow:runWithId(noInputWorkflow, instanceId = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    // ERROR WORKFLOW_166: a blank agent id; ERROR WORKFLOW_154: a mistyped agent payload at the third position.
    string ag1 = check orderAgent.runWithId(" ", "hello", {orderId: "ORD-1", quantity: 1});
    string ag2 = check orderAgent.runWithId("agent-1", "hello", "not an order");
    _ = [wf1, wf2, wf3, wf4, wf5, wf6, ag1, ag2];
}
