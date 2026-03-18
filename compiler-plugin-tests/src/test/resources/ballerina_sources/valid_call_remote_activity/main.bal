import ballerina/workflow;

// Define a simple client with remote and resource methods
public client class TestHttpClient {
    private string baseUrl;

    public function init(string baseUrl) {
        self.baseUrl = baseUrl;
    }

    remote function post(string path, json message) returns json|error {
        return {"status": "ok", "path": path};
    }

    remote function get(string path) returns json|error {
        return {"status": "ok", "path": path};
    }

    resource function get users() returns json|error {
        return [{"name": "Alice"}];
    }

    resource function post users(json payload) returns json|error {
        return payload;
    }

    resource function get users/[string id]() returns json|error {
        return {"id": id, "name": "Alice"};
    }
}

type Input record {|
    string id;
|};

// Valid: callRemoteActivity with a client object and valid remote method
@workflow:Workflow
function remoteActivityWorkflow(workflow:Context ctx, Input input) returns json|error {
    TestHttpClient httpClient = new ("http://localhost:8080");
    json result = check ctx->callRemoteActivity(httpClient, "post",
        {"path": "/api/users", "message": {"name": "Alice"}});
    return result;
}

// Valid: callResourceActivity with a client object and valid resource method
@workflow:Workflow
function resourceActivityWorkflow(workflow:Context ctx, Input input) returns json|error {
    TestHttpClient httpClient = new ("http://localhost:8080");
    json users = check ctx->callResourceActivity(httpClient, "get", "/users");
    return users;
}

// Valid: callResourceActivity with POST resource method
@workflow:Workflow
function resourcePostWorkflow(workflow:Context ctx, Input input) returns json|error {
    TestHttpClient httpClient = new ("http://localhost:8080");
    json result = check ctx->callResourceActivity(httpClient, "post", "/users",
        {"payload": {"name": "Bob"}});
    return result;
}
