import ballerina/http;
import ballerina/workflow;

final http:Client connection = check new ("http://localhost:9090");

@workflow:Activity
function fetchCustomer(http:Client connection, string path) returns json|error {
    return connection->get(path);
}

@workflow:Workflow
function customerWorkflow(workflow:Context ctx) returns json|error {
    return ctx->callActivity(fetchCustomer, {
        connection,
        path: "/customers/1"
    });
}
