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

// Three signals with same structure - tests detection of first two ambiguous signals
type StringSignal record {|
    readonly string id;
    string data;
|};

type TestInput record {|
    readonly string id;
    string name;
|};

type TestResult record {|
    string status;
|};

// Process with three signals of same type - ambiguous
@workflow:Process
function threeAmbiguousSignalProcess(
    workflow:Context ctx,
    TestInput input,
    record {|
        future<StringSignal> signalA;
        future<StringSignal> signalB;
        future<StringSignal> signalC;
    |} signals
) returns TestResult|error {
    StringSignal s = check wait signals.signalA;
    return {status: "OK"};
}

// INVALID: sendEvent without signalName when three signals are ambiguous
// Should trigger WORKFLOW_112 error
function invalidSendToThreeAmbiguousSignals() returns error? {
    StringSignal data = {id: "test-1", data: "test"};
    _ = check workflow:sendEvent(threeAmbiguousSignalProcess, data);
}
