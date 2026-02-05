// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

// ================================================================================
// ORDER PROCESSING WORKFLOW - Record Results
// ================================================================================
// 
// This workflow demonstrates an activity that returns a record type.
// Useful for business workflows that process structured data.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// TYPES
// ================================================================================

# Input for order processing workflow.
#
# + id - The workflow identifier
# + orderId - The order identifier
# + quantity - The quantity ordered
type OrderInput record {|
    string id;
    string orderId;
    int quantity;
|};

# Result of order processing.
#
# + orderId - The order identifier
# + status - The order status
# + quantity - The quantity processed
type OrderResult record {|
    string orderId;
    string status;
    int quantity;
|};

// ================================================================================
// ACTIVITY DEFINITION
// ================================================================================

# Activity that processes an order and returns structured result.
#
# + orderId - The order identifier
# + quantity - The quantity ordered
# + return - The processed order result or error
@workflow:Activity
function processOrderActivity(string orderId, int quantity) returns OrderResult|error {
    return {
        orderId: orderId,
        status: "PROCESSED",
        quantity: quantity
    };
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow that processes an order and returns structured data.
#
# + ctx - The workflow context for calling activities
# + input - The order input data
# + return - The order processing result or error
@workflow:Process
function orderWorkflow(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    map<anydata> resultMap = check ctx->callActivity(processOrderActivity, 
            {"orderId": input.orderId, "quantity": input.quantity});
    return {
        orderId: <string>resultMap["orderId"],
        status: <string>resultMap["status"],
        quantity: <int>resultMap["quantity"]
    };
}
