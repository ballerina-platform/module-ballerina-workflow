// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/workflow;
import ballerina/io;

type OrderRequest record {|
    string orderId;
    string item;
    int quantity;
|};

@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns string|error {
    boolean inStock = check ctx->callActivity(checkInventory, {
        "item": request.item,
        "quantity": request.quantity
    });

    if !inStock {
        return string `Order ${request.orderId} failed: "${request.item}" is out of stock.`;
    }

    string reservationId = check ctx->callActivity(reserveStock, {
        "orderId": request.orderId,
        "item": request.item,
        "quantity": request.quantity
    });

    return string `Order ${request.orderId} confirmed. Reservation ID: ${reservationId}`;
}

@workflow:Activity
function checkInventory(string item, int quantity) returns boolean|error {
    io:println(string `Checking inventory for ${item}, quantity: ${quantity}`);
    return true;
}

@workflow:Activity
function reserveStock(string orderId, string item, int quantity) returns string|error {
    io:println(string `Reserving ${quantity} unit(s) of ${item} for order ${orderId}`);
    return "RES-" + orderId;
}

public function main() returns error? {
    // Start a new workflow instance
    string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop", quantity: 2});
    io:println("Workflow started with ID: " + workflowId);

    workflow:WorkflowExecutionInfo result = check workflow:getWorkflowResult(workflowId);
    io:println("Result: " + result.result.toString());
}
