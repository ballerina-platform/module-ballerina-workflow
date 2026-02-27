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

// Signal type WITHOUT annotation
type ApprovalSignal record {|
    boolean approved;
    string approver;
|};

// Input type WITHOUT annotation fields
type OrderInput record {|
    string orderId;
    string customerName;
|};

type OrderResult record {|
    string status;
|};

// Process with events but NO annotation fields
@workflow:Workflow
function orderProcessNoCorrelation(
    workflow:Context ctx,
    OrderInput input,
    record {|
        future<ApprovalSignal> approval;
    |} signals
) returns OrderResult|error {
    ApprovalSignal a = check wait signals.approval;
    return {status: a.approved ? "approved" : "rejected"};
}

// Valid: sendData with all required params (no fields needed)
function validSendSignalNoCorrelation() returns error? {
    ApprovalSignal data = {approved: true, approver: "admin"};
    check workflow:sendData(orderProcessNoCorrelation, "wf-12345", "approval", data);
}
