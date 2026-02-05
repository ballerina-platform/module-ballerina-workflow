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
    int quantity;
|};

type OrderResult record {|
    string status;
|};

// Activity function
@workflow:Activity
function validateOrderActivity(OrderInput input) returns boolean|error {
    return input.quantity > 0;
}

// Invalid: Process function that directly calls @Activity function
// This should produce WORKFLOW_108 error
// Users must use ctx->callActivity(activityFunc, args...) instead
@workflow:Process
function orderProcess(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // ERROR: Direct call to @Activity function is not allowed
    boolean isValid = check validateOrderActivity(input);
    if !isValid {
        return error("Invalid order");
    }
    
    return {
        status: "COMPLETED"
    };
}
