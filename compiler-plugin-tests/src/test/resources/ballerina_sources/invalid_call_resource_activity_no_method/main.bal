import ballerina/workflow;

// A client that only has GET /users resource method
public client class LimitedResourceClient {
    public function init() {
    }

    resource function get users() returns json|error {
        return [{"name": "Alice"}];
    }
}

type Input record {|
    string id;
|};

// Invalid: calling a resource method with accessor "post" and path "/orders" that doesn't exist
@workflow:Workflow
function invalidResourceMethodWorkflow(workflow:Context ctx, Input input) returns json|error {
    LimitedResourceClient httpClient = new ();
    json result = check ctx->callResourceActivity(httpClient, "post", "/orders",
        {"payload": {"item": "laptop"}});
    return result;
}
