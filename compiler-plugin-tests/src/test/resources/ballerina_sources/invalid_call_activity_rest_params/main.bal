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

// Invalid: Activity function with rest parameters - not supported
// This should produce WORKFLOW_111 error when called
@workflow:Activity
function processOrder(string orderId, string... additionalParams) returns boolean|error {
    return orderId.length() > 0;
}

// Process function calling ctx->callActivity() with rest params activity
// This should produce WORKFLOW_111 error
@workflow:Process
function orderProcess(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // ERROR: Activity function has rest parameters which are not supported
    boolean isValid = check ctx->callActivity(processOrder, {"orderId": input.orderId});
    if !isValid {
        return error("Invalid order");
    }
    
    return {
        status: "COMPLETED"
    };
}
