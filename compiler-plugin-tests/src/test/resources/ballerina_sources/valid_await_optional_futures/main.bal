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

// Events record whose fields hold futures of optional primitive types.
// Reproduces: future<int?> and future<string?> matched against [int?, string?].
type BioDataWorkflowData record {|
    future<int?> a;
    future<string?> q;
|};

// Valid: futures whose inner type is already optional (e.g. future<int?>) must be
// accepted when the LHS tuple member is the same optional type (int?).
// minCount = 1 < 2 futures, so nilable is required — int? and string? both satisfy that.
@workflow:Workflow
function bioDataWorkflow(
    workflow:Context ctx,
    BioDataWorkflowData data
) returns string|error {
    [int?, string?] [a, q] = check ctx->await([data.a, data.q], minCount = 1);
    return string `a=${a.toString()}, q=${q.toString()}`;
}

