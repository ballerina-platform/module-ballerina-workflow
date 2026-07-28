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
import ballerina/lang.runtime as langRuntime;
import ballerina/test;

// Exercises the listener lifecycle that `startManagementService` gates behind
// `enableManagementApi`. The test configuration leaves the API disabled, so module
// init reserves no port (the root package's regression test for
// https://github.com/ballerina-platform/ballerina-library/issues/8942 asserts that);
// here the lifecycle is driven directly: starting the listener serves the management
// API on the configured port, and stopping the service releases the port again.
@test:Config {}
function testManagementListenerLifecycle() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);
    http:Response response = check mgmtClient->get("/workflow/definitions");
    test:assertEquals(response.statusCode, 200,
        "The started management listener should serve management API requests");

    check stopManagementService();
    // A second stop is a clean no-op (the graceful-stop handler may fire later).
    check stopManagementService();
    langRuntime:sleep(0.1);
    http:Response|error afterStop = mgmtClient->get("/workflow/definitions");
    test:assertTrue(afterStop is error,
        "Stopping the management service must release the port");
}
