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

// Single signal type
type ResponseSignal record {|
    readonly string id;
    string message;
|};

type TestInput record {|
    readonly string id;
    string name;
|};

type TestResult record {|
    string status;
|};

// Valid: Process with single signal - no ambiguity possible
@workflow:Process
function singleSignalProcess(
    workflow:Context ctx,
    TestInput input,
    record {|
        future<ResponseSignal> response;
    |} signals
) returns TestResult|error {
    ResponseSignal r = check wait signals.response;
    return {status: "OK"};
}

// This is VALID - single signal can always be inferred
function validSendToSingleSignalProcess() returns error? {
    ResponseSignal data = {id: "test-1", message: "hello"};
    _ = check workflow:sendEvent(singleSignalProcess, data);
}
