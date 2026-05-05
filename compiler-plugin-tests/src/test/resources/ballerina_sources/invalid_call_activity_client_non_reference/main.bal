import ballerina/http;
import ballerina/workflow;

@workflow:Activity
function fetchCustomer(http:Client connection, string path) returns json|error {
    return connection->get(path);
}

@workflow:Workflow
function customerWorkflow(workflow:Context ctx) returns json|error {
    // Invalid for client-object parameters: must be a simple reference to a
    // module-level final client variable.
    return ctx->callActivity(fetchCustomer, {
        connection: check new ("http://localhost:9090"),
        path: "/customers/1"
    });
}
