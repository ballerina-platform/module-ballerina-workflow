// Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/workflow;
import test.m1;

@workflow:Activity
isolated function activityOne() returns int {
    return 1;
}

@workflow:Activity
isolated function activityTwo() returns string {
    return "result";
}

@workflow:Activity
isolated function activityThree() returns int {
    return 3;
}

final workflow:PersistentProvider persistentProvider = object {
    public isolated function registerWorkflowModel(workflow:WorkflowModel svc,  workflow:WorkflowModelData data) returns error? {
    }
    public isolated function unregisterWorkflowModel(workflow:WorkflowModel svc) returns error? {
    }
    public isolated function 'start() returns error? {
    }
    public isolated function stop() returns error? {
    }
    public isolated function getClient() returns workflow:WorkflowEngineClient|error {
        return error("not implemented");
    }
    public isolated function getWorkflowOperators() returns workflow:WorkflowOperators|error {
        return error("not implemented");
    }
};

service "workflow" on new workflow:WorkflowEventListener(persistentProvider) {

    isolated remote function execute() returns error? {
        // These should trigger WORKFLOW_109 error - var binding with activity function call
        var result1 = activityOne();
        var result2 = activityTwo();
        var result3 = activityThree();
        var result4 = m1:activityOne();

        // These should be allowed - explicit type binding
        int explicitResult1 = activityOne();
        string explicitResult2 = activityTwo();
        int|error explicitResult3 = activityThree();
        int explicitResult4 = m1:activityOne();
    }

    @workflow:Signal
    isolated remote function notify() returns error? {
    }
}
