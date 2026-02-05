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

type ProcessInput record {|
    string id;
|};

type ProcessResult record {|
    string timestamp;
    int count;
|};

// Activity function with NO parameters
@workflow:Activity
function getCurrentTimestamp() returns string|error {
    // Simulated - returns current timestamp
    return "2026-02-02T10:00:00Z";
}

// Activity function with NO parameters returning int
@workflow:Activity
function getCounter() returns int|error {
    // Simulated counter
    return 42;
}

// Activity function WITH parameters (for comparison)
@workflow:Activity
function processData(string id) returns string|error {
    return "processed-" + id;
}

// Process function that calls activities - both with and without args
@workflow:Process
function noArgActivityProcess(workflow:Context ctx, ProcessInput input) returns ProcessResult|error {
    // Call activity WITHOUT args (passing empty record for no-arg activity)
    string timestamp = check ctx->callActivity(getCurrentTimestamp, {});
    
    // Call another no-arg activity (with explicit empty args)
    int count = check ctx->callActivity(getCounter, {});
    
    // Call activity WITH args parameter (regular activity)
    string _ = check ctx->callActivity(processData, {"id": input.id});
    
    return {
        timestamp: timestamp,
        count: count
    };
}
