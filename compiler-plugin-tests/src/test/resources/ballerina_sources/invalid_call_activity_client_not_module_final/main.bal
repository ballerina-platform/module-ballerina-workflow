import ballerina/http;
import ballerina/workflow;

http:Client connection = check new ("http://localhost:9090");

@workflow:Activity
function fetchCustomer(http:Client connection, string path) returns json|error {
    return connection->get(path);
}

@workflow:Workflow
function customerWorkflow(workflow:Context ctx) returns json|error {
    // Invalid for client-object parameters: the reference must resolve to a
    // module-level final client variable.
    return ctx->callActivity(fetchCustomer, {
        connection,
        path: "/customers/1"
    });
}
