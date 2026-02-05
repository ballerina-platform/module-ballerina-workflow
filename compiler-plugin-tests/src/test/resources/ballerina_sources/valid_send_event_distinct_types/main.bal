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

// Types with DIFFERENT structure (not ambiguous)
type ApprovalSignal record {|
    readonly string id;
    boolean approved;
    string approver;
|};

type PaymentSignal record {|
    readonly string id;
    string txnId;
    decimal amount;
|};

type TestInput record {|
    readonly string id;
    string name;
|};

type TestResult record {|
    string status;
|};

// Valid: Process with distinct signal types - no ambiguity
@workflow:Process
function distinctSignalProcess(
    workflow:Context ctx,
    TestInput input,
    record {|
        future<ApprovalSignal> approval;
        future<PaymentSignal> payment;
    |} signals
) returns TestResult|error {
    ApprovalSignal a = check wait signals.approval;
    return {status: "OK"};
}

// This is VALID - distinct types allow signal name inference without explicit signalName
function validSendWithoutSignalName() returns error? {
    ApprovalSignal data = {id: "test-1", approved: true, approver: "admin"};
    _ = check workflow:sendEvent(distinctSignalProcess, data);
}
