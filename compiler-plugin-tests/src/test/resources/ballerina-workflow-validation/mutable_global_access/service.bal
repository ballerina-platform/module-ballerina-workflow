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

int globalCounter = 0;
isolated int isolatedCounter = 0;
isolated final int[] mutableArray = [1, 2, 3];
final int & readonly readonlyInt = 42;
isolated final map<string> finalMap = {a: "one", b: "two"};
isolated final record{|string a;|} finalRecord = {a: "one"};
isolated string & readonly readonlyString = "immutable";
configurable int timeout = 30;

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
    private int a = 5;
    isolated remote function execute() returns error? {
        lock {
            int isoCount = isolatedCounter;
        }

        lock {
            int[] arr = mutableArray;
        }

        lock {
            string readonlyStr = readonlyString;
        }

        lock {
            string readonlyStr = readonlyString + " modified";
        }

        lock {
            string? readonlyStr = finalMap["a"];
        }

        lock {
            string? readonlyStr = finalRecord.a;
        }
        // These should be allowed inside lock (final AND readonly, or configurable)
        lock {
            int readonlyValue = readonlyInt;
        }

        lock {
            int timeoutValue = timeout;
        }

        lock {
            int b = self.a;
        }
        // Note: Accessing globalCounter outside lock will be caught by Ballerina's
        // isolated function validation, so we don't need to test it here
    }

    @workflow:Signal
    isolated remote function notify() returns error? {
    }
}

