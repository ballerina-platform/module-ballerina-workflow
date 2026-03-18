import ballerina/workflow;

// A client that only has a "get" remote method
public client class LimitedClient {
    public function init() {
    }

    remote function get(string path) returns json|error {
        return {"path": path};
    }
}

type Input record {|
    string id;
|};

// Invalid: calling a remote method "post" that doesn't exist on the client
@workflow:Workflow
function invalidRemoteMethodWorkflow(workflow:Context ctx, Input input) returns json|error {
    LimitedClient httpClient = new ();
    json result = check ctx->callRemoteActivity(httpClient, "post",
        {"path": "/api/users"});
    return result;
}
