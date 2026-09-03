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

// Route coverage for the bulk-retry resource: that the path is served, that the body
// reaches the operation as parameters, and that the management error the operation
// returns becomes the status code and payload this module defines. Every case here is
// refused by request shape alone, so none of them needs a workflow to exist — which is
// what makes the route testable without a live Temporal server.
//
// One test drives the whole surface because it owns the listener lifecycle: starting
// and stopping it once avoids racing the other lifecycle test for the port.

@test:Config {}
function testBulkRetryRouteMapsErrorsToStatusCodes() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);

    // A selector naming nothing: the operation's InvalidRequestError becomes 400, and
    // the body is this module's canonical error shape.
    http:Response noSelector = check mgmtClient->post("/workflow/review-activities/bulk-retry",
            {action: "retry"}, {"x-user-roles": "OPS"});
    test:assertEquals(noSelector.statusCode, 400, "A bulk retry naming no tasks must be a bad request");
    json noSelectorBody = check noSelector.getJsonPayload();
    test:assertEquals(noSelectorBody, <json>{"error": {"message": "Either taskIds or parentWorkflowId is required"}},
            "Errors must serialize through the module's one representation");

    // Both selectors: the route forwards each body field it knows, so the operation
    // sees the ambiguity rather than a silently applied precedence.
    http:Response bothSelectors = check mgmtClient->post("/workflow/review-activities/bulk-retry",
            {action: "retry", taskIds: ["reviewactivity-x"], parentWorkflowId: "wf-1"},
            {"x-user-roles": "OPS"});
    test:assertEquals(bothSelectors.statusCode, 400);
    json bothBody = check bothSelectors.getJsonPayload();
    test:assertEquals(bothBody, <json>{"error": {"message": "Specify either taskIds or parentWorkflowId, not both"}});

    // A scalar where an array belongs is caught by parameter typing, which the route
    // reaches only because it forwards taskIds verbatim.
    http:Response scalarIds = check mgmtClient->post("/workflow/review-activities/bulk-retry",
            {action: "retry", taskIds: "reviewactivity-x"}, {"x-user-roles": "OPS"});
    test:assertEquals(scalarIds.statusCode, 400);
    json scalarBody = check scalarIds.getJsonPayload();
    test:assertEquals(scalarBody, <json>{"error": {"message": "taskIds must be a JSON array"}});

    http:Response unknownAction = check mgmtClient->post("/workflow/review-activities/bulk-retry",
            {action: "escalate", taskIds: ["reviewactivity-x"]}, {"x-user-roles": "OPS"});
    test:assertEquals(unknownAction.statusCode, 400);

    // A body with no action at all: the required-parameter check runs before anything
    // touches the runtime.
    http:Response noAction = check mgmtClient->post("/workflow/review-activities/bulk-retry",
            {taskIds: ["reviewactivity-x"]}, {"x-user-roles": "OPS"});
    test:assertEquals(noAction.statusCode, 400);
    json noActionBody = check noAction.getJsonPayload();
    test:assertEquals(noActionBody, <json>{"error": {"message": "action is required"}});

    check stopManagementService();
}
