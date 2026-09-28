// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

// Route coverage for the start resource's request checks — decided before anything reaches
// the runtime, so testable without a live Temporal server.

@test:Config {}
function testStartRouteRefusesBadIdsAndPolicies() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);

    json[] bad = [
        {workflowType: "anything", workflowId: 42},
        {workflowType: "anything", workflowId: "x", ifRunning: "MAYBE"},
        {workflowType: "anything", workflowId: "x", ifClosed: "NEVER"}
    ];
    foreach json body in bad {
        http:Response response = check mgmtClient->post("/workflow/workflows", body);
        test:assertEquals(response.statusCode, 400,
            "A bad id or policy is refused as a bad request: " + body.toJsonString());
    }

    // A well-formed request gets past the checks and on to the runtime, whose absence is
    // what stops it here — never a 400.
    http:Response ordinary = check mgmtClient->post("/workflow/workflows",
        {workflowType: "anything", workflowId: "order-1", ifRunning: "USE_EXISTING"});
    test:assertNotEquals(ordinary.statusCode, 400);

    check stopManagementService();
}
