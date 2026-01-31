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
// ARRAY PROCESSING WORKFLOW
// ================================================================================
// 
// This workflow demonstrates passing array data to activities.
// Useful for batch processing or aggregate operations.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

type ArrayProcessInput record {|
    string id;
    int[] numbers;
|};

// ================================================================================
// ACTIVITY DEFINITION
// ================================================================================

# Activity that sums an array of numbers.
#
# + numbers - Array of integers to sum
# + return - The sum or error
@workflow:Activity
function sumArrayActivity(int[] numbers) returns int|error {
    int sum = 0;
    foreach int n in numbers {
        sum += n;
    }
    return sum;
}

// ================================================================================
// WORKFLOW DEFINITION
// ================================================================================

# A workflow that processes an array by summing its elements.
#
# + ctx - The workflow context for calling activities
# + input - The workflow input containing numbers array
# + return - The sum result or error
@workflow:Process
function arrayProcessingWorkflow(workflow:Context ctx, ArrayProcessInput input) returns int|error {
    anydata result = check ctx->callActivity(sumArrayActivity, input.numbers);
    return <int>result;
}
