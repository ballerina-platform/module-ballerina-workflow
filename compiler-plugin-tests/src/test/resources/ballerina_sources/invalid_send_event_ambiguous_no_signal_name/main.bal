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

// Types with SAME structure - this creates ambiguity
type SignalType1 record {|
    readonly string id;
    string value;
|};

type SignalType2 record {|
    readonly string id;
    string value;
|};

type TestInput record {|
    readonly string id;
    string name;
|};

type TestResult record {|
    string status;
|};

// Process with ambiguous signal types (same structure)
@workflow:Process
function ambiguousSignalProcess(
    workflow:Context ctx,
    TestInput input,
    record {|
        future<SignalType1> signal1;
        future<SignalType2> signal2;
    |} signals
) returns TestResult|error {
    SignalType1 s1 = check wait signals.signal1;
    return {status: "OK"};
}

// INVALID: sendEvent without signalName when signals are ambiguous
// Should trigger WORKFLOW_112 error
function invalidSendWithoutSignalName() returns error? {
    SignalType1 data = {id: "test-1", value: "test"};
    // Missing signalName parameter - ambiguous, cannot infer which signal to send
    _ = check workflow:sendEvent(ambiguousSignalProcess, data);
}
