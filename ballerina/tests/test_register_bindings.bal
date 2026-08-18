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

import ballerina/jballerina.java;

// Test-only registration bindings. Production programs register workflows, activities,
// and human tasks from the packed workflow descriptor (workflow.def.json) — see
// wfInternal:registerWorkflowDescriptor — but the compiler plugin does not run on the
// workflow package itself, so these tests register their fixtures by function pointer
// directly against the same native registration the descriptor path feeds into.

isolated function registerWorkflowForTest(function workflowFunction, string workflowName,
        map<function>? activities = ()) returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "registerWorkflow"
} external;

isolated function registerAgentWorkflowForTest(function workflowFunction, string workflowName,
        map<function>? activities = ()) returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "registerAgentWorkflow"
} external;

isolated function registerHumanTaskForTest(string taskName) returns boolean|error = @java:Method {
    'class: "io.ballerina.lib.workflow.worker.WorkflowWorkerNative",
    name: "registerHumanTask"
} external;
