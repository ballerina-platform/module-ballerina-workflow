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

// Types with same structure
type SignalType1 record {|
    string id;
    string value;
|};

type SignalType2 record {|
    string id;
    string value;
|};

type TestInput record {|
    string id;
    string name;
|};

type TestResult record {|
    string status;
|};

// Valid: Process with signal types
@workflow:Workflow
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

// Valid: sendData with all required params
function validSendWithExplicitSignalName() returns error? {
    SignalType1 data = {id: "test-1", value: "test"};
    check workflow:sendData(ambiguousSignalProcess, "wf-12345", "signal1", data);
}
