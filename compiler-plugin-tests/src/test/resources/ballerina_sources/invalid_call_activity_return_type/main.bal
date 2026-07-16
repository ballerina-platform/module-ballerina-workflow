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
|};

type PaymentRecord record {|
    string txnId;
    decimal amount;
|};

@workflow:Activity
function checkPayment(string orderId) returns PaymentRecord? {
    return {txnId: "TXN-" + orderId, amount: 10.0};
}

@workflow:Activity
function reserveInventory(string orderId) returns string|error {
    return "RES-" + orderId;
}

// Invalid: contextually expected types are incompatible with the activity return types.
// Each mismatching callActivity call should produce a WORKFLOW_137 error.
@workflow:Workflow
function placeOrderWorkflow(workflow:Context ctx, OrderInput input) returns error? {
    // ERROR: checkPayment returns PaymentRecord? but int? is expected (ballerina-library#8835)
    int? paymentRecord = check ctx->callActivity(checkPayment, {"orderId": input.orderId});

    // ERROR: reserveInventory returns string but boolean is expected
    boolean reserved = check ctx->callActivity(reserveInventory, {"orderId": input.orderId});

    if paymentRecord is () || !reserved {
        return error("order not processed");
    }
}
