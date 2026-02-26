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

// Signal types WITHOUT @CorrelationKey - correlation keys are not needed
// when signals are sent via workflowId
type ApprovalSignal record {|
    boolean approved;
    string approver;
|};

// Input type WITHOUT @CorrelationKey fields
type OrderInput record {|
    string orderId;
    string customerName;
|};

type OrderResult record {|
    string status;
|};

// Valid: Process with events but NO @CorrelationKey fields.
// This is valid because signals can be routed via workflowId + signalName.
@workflow:Process
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

// Valid: sendData with workflowId + signalName (no correlation keys needed)
function validSendSignalWithWorkflowId() returns error? {
    ApprovalSignal data = {approved: true, approver: "admin"};
    _ = check workflow:sendData(orderProcessNoCorrelation,
        workflowId = "wf-12345", signalName = "approval", signalData = data);
}
