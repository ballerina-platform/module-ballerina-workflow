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
import sample_package_01.m1;

type Person record {|
    string name;
    int age;
|};

workflow:PersistentProvider persistentProvider = object {
    public isolated function registerWorkflowModel(workflow:WorkflowModel svc, workflow:WorkflowModelData data) returns error? {
    }
    public isolated function unregisterWorkflowModel(workflow:WorkflowModel svc) returns error? {
    }
    public isolated function 'start() returns error? {
    }
    public isolated function stop() returns error? {
    }
    public isolated function getClient() returns workflow:WorkflowEngineClient|error {
        return error("Not implemented");
    }
    public isolated function getWorkflowOperators() returns workflow:WorkflowOperators|error {
        return error("Not implemented");
    }
};

service "workflow" on new workflow:WorkflowEventListener(persistentProvider) {
    isolated remote function execute() {
        record { string name; } a = performActivity1();
        Person b = activityReturnsUserDefinedType(5, "john");
        var c = foo(3, "doe");
        int d = m1:performActivity(7, "smith");
        var e = {"a": "a"};
        int[] f = activityReturnsArray();
        Person[] g = activityReturnsUserDefinedTypeArray(10, "alice");
        () h = activityReturnsNil(4, "bob");
        map<anydata> m = activityReturnsMap(6, "charlie");
        table<map<anydata>> n = activityReturnsTable(8, "david");

        int[] i = m1:activityReturnsArray();
        m1:Person1[] j = m1:activityReturnsUserDefinedTypeArray(10, "alice");
        () k = m1:activityReturnsNil(4, "bob");
        m1:Person1 l = m1:activityReturnsUserDefinedType(5, "john");
        map<anydata> o = m1:activityReturnsMap(6, "charlie");
        table<map<anydata>> p = m1:activityReturnsTable(8, "david");
    }
}

@workflow:Activity
isolated function performActivity1() returns record { string name; } {
    return { name: "John" };
}

@workflow:Activity
isolated function activityReturnsUserDefinedType(int a, string name) returns Person {
    return { name, age: a };
}

@workflow:Activity
isolated function unusedActivity(int a, string name) returns int {
    return a;
}

isolated function foo(int a, string name) returns int {
    return a;
}

@workflow:Activity
isolated function activityReturnsArray() returns int[] {
    return [1, 2];
}

@workflow:Activity
isolated function activityReturnsUserDefinedTypeArray(int a, string name) returns Person[] {
    return [{ name, age: a }];
}

@workflow:Activity
isolated function activityReturnsNil(int a, string name)  {
}

@workflow:Activity
isolated function activityReturnsMap(int a, string name) returns map<anydata> {
    return { name, age: a };
}

@workflow:Activity
isolated function activityReturnsTable(int a, string name) returns table<map<anydata>> {
    return table [{ name, age: a }];
}
