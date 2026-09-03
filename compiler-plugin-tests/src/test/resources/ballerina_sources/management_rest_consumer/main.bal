// A package that can actually serve the management REST API: it imports the module that owns
// the service object and its listener. The artifact-export tests build this one; a package
// without the import (descriptor_generation, say) must export nothing even when asked.
import ballerina/workflow;
import ballerina/workflow.management.rest as _;

type Shipment record {|
    string id;
|};

@workflow:Workflow
function shipOrder(workflow:Context ctx, Shipment shipment) returns error? {
    string label = check ctx->callActivity(printLabel, {"shipment": shipment});
    _ = label;
    return;
}

@workflow:Activity
function printLabel(Shipment shipment) returns string|error {
    return "label-" + shipment.id;
}
