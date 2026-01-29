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

public type Person1 record {|
    string name;
    int age;
|};

@workflow:Activity
public isolated function performActivity(int a, string name) returns int {
    return a;
}

@workflow:Activity
public isolated function activityReturnsArray() returns int[] {
    return [1, 2];
}

@workflow:Activity
public isolated function activityReturnsUserDefinedTypeArray(int a, string name) returns Person1[] {
    return [{ name, age: a }];
}

@workflow:Activity
public isolated function activityReturnsNil(int a, string name)  {
}

@workflow:Activity
public isolated function activityReturnsUserDefinedType(int a, string name) returns Person1 {
    return { name, age: a };
}

@workflow:Activity
public isolated function activityReturnsMap(int a, string name) returns map<anydata> {
    return { name, age: a };
}

@workflow:Activity
public isolated function activityReturnsTable(int a, string name) returns table<map<anydata>> {
    return table [{ name, age: a }];
}
