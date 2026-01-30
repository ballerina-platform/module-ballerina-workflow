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

// Invalid activity functions - parameters not subtype of anydata
@workflow:Activity
function activityWithFunctionParam(function (int) returns int invalidParam) returns int {
    return 1;
}

type MyService service object {
    resource function get name() returns string;
};

@workflow:Activity
function activityWithServiceParam(MyService svc) returns string {
    return "result";
}

@workflow:Activity
function activityWithStreamParam(stream<int> streamParam) returns int {
    return 1;
}

class MyClass {
    int value = 10;
}

@workflow:Activity
function activityWithObjectParam(MyClass obj) returns int {
    return 1;
}

// Valid activity functions - parameters are subtype of anydata
@workflow:Activity
function validActivityWithInt(int x) returns int {
    return x;
}

@workflow:Activity
function validActivityWithString(string name) returns string {
    return name;
}

@workflow:Activity
function validActivityWithRecord(record {|int id; string name;|} data) returns string {
    return data.name;
}

@workflow:Activity
function validActivityWithJson(json payload) returns json {
    return payload;
}

@workflow:Activity
function validActivityMultipleParams(int x, string y, boolean z) returns string {
    return y;
}

function regularFunc(function (int) returns int invalidParam) returns int {
    return 1;
}

@deprecated
function regularFuncDeprecated(function (int) returns int invalidParam) returns int {
    return 1;
}
