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

// ================================================================================
// SIMPLE WORKFLOW - No Activities
// ================================================================================
// 
// This workflow demonstrates a simple process that doesn't use any activities.
// It directly processes input and returns a result without any I/O operations.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

type SimpleInput record {|
    string id;
    string name;
|};

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A simple workflow that processes input without calling any activities.
# This is useful for pure computation workflows that don't need I/O.
#
# + input - The workflow input containing id and name
# + return - A greeting string or error
@workflow:Process
function simpleWorkflow(SimpleInput input) returns string|error {
    return "Hello from workflow: " + input.name;
}
