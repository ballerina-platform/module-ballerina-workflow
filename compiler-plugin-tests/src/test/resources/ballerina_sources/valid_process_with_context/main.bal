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

import ballerina/workflow;

type OrderInput record {|
    string orderId;
    string productId;
    int quantity;
|};

type OrderResult record {|
    string status;
    string workflowId;
|};

// Activity function to validate an order
@workflow:Activity
function validateOrderActivity(OrderInput input) returns boolean|error {
    // Simulated order validation
    return input.quantity > 0;
}

// Activity function to get tracking number
@workflow:Activity
function getTrackingNumberActivity(string orderId) returns string|error {
    return "TRACK-" + orderId;
}

// Valid: Process function with Context as first parameter, using ctx->callActivity()
@workflow:Process
function orderProcessWithContext(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // Call activity through context using ctx->callActivity() pattern
    boolean isValid = check ctx->callActivity(validateOrderActivity, {"input": input});
    if !isValid {
        return error("Invalid order");
    }
    
    string trackingNo = check ctx->callActivity(getTrackingNumberActivity, {"orderId": input.orderId});
    
    return {
        status: "COMPLETED",
        workflowId: ctx.workflowId + "-" + trackingNo
    };
}

// Valid: Process function with only input parameter (Context is optional)
@workflow:Process
function orderProcessWithoutContext(OrderInput input) returns OrderResult|error {
    return {
        status: "COMPLETED",
        workflowId: "unknown"
    };
}
