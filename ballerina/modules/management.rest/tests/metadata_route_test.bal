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
import ballerina/workflow.management;

// Route coverage for GET /metadata: the program's metadata document over HTTP, as a control
// plane receives it in heartbeats. The listener is released by `releaseManagementListener`.

@test:Config {}
function testMetadataRouteServesTheMetadataDocument() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);

    // No identity headers: the document describes the program, not a run, so no roles are needed.
    http:Response response = check mgmtClient->get("/workflow/metadata");
    test:assertEquals(response.statusCode, 200);
    json body = check response.getJsonPayload();
    test:assertEquals(body, (check management:getWorkflowMetadata()).toJson(),
            "GET /metadata must relay the document getWorkflowMetadata builds");
    map<json> document = check body.ensureType();
    test:assertTrue(document.hasKey("metadataVersion") && document.hasKey("definitions"),
            "The body must be the metadata document itself, not wrapped");
    check stopManagementService();
}
