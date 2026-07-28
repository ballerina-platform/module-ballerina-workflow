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

import ballerina/http;
import ballerina/test;

// Regression test for https://github.com/ballerina-platform/ballerina-library/issues/8942:
// these tests import `workflow.management` (for its programmatic helpers) but leave
// `enableManagementApi` unset (disabled). The management listener must therefore never
// be created — the management port stays free and any request to it fails to connect.
@test:Config {groups: ["unit"]}
function testManagementPortNotReservedWhenApiDisabled() returns error? {
    http:Client mgmtClient = check new ("http://localhost:8234", timeout = 2);
    http:Response|error response = mgmtClient->get("/workflow/definitions");
    test:assertTrue(response is error,
        "The management port must not be open when enableManagementApi = false");
}
