import ballerina/workflow;

// A regular class (not a client)
public class NotAClient {
    public function init() {
    }

    function doSomething() returns string {
        return "hello";
    }
}

type Input record {|
    string id;
|};

// Invalid: first argument to callRemoteActivity is not a client object
@workflow:Workflow
function invalidNotClientWorkflow(workflow:Context ctx, Input input) returns json|error {
    NotAClient obj = new ();
    json result = check ctx->callRemoteActivity(obj, "doSomething");
    return result;
}
