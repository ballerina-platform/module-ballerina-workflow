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

// Route coverage for the reset resources: both the latest-run and pinned-run paths,
// the body-to-parameter mapping `resetParams` performs, and the status code each
// management error becomes.
//
// Every case here is answered before any history is read — either by the role gate or
// by request validation — which is what makes the routes testable without a live
// Temporal server, and is also the ordering the reset operation deliberately follows.

@test:Config {}
function testResetRoutesMapBodyAndErrors() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);
    map<string> asOperator = {"x-user-roles": "OPS"};

    // ── The role gate, before anything reads the run ──────────────────────────
    http:Response pointsNoRoles = check mgmtClient->get("/workflow/workflows/wf-x/reset-points");
    test:assertEquals(pointsNoRoles.statusCode, 403,
            "Reset points expose history detail, so a caller with no roles is refused");

    http:Response resetNoRoles = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "first-workflow-task"});
    test:assertEquals(resetNoRoles.statusCode, 403, "Resetting with no roles is refused");

    http:Response pinnedNoRoles = check mgmtClient->get("/workflow/workflows/wf-x/run-y/reset-points");
    test:assertEquals(pinnedNoRoles.statusCode, 403, "The pinned-run route is gated the same way");

    // ── resetType reaches the operation from the body ─────────────────────────
    http:Response noType = check mgmtClient->post("/workflow/workflows/wf-x/reset", {}, asOperator);
    test:assertEquals(noType.statusCode, 400);
    json noTypeBody = check noType.getJsonPayload();
    test:assertEquals(noTypeBody, <json>{"error": {"message": "resetType is required"}});

    http:Response unknownType = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "beginning"}, asOperator);
    test:assertEquals(unknownType.statusCode, 400);

    // ── eventId reaches it too, and is typed ──────────────────────────────────
    http:Response noEventId = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "workflow-task-id"}, asOperator);
    test:assertEquals(noEventId.statusCode, 400);
    json noEventIdBody = check noEventId.getJsonPayload();
    test:assertEquals(noEventIdBody,
            <json>{"error": {"message": "eventId is required when resetType is \"workflow-task-id\""}});

    http:Response badEventId = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "workflow-task-id", eventId: "not-a-number"}, asOperator);
    test:assertEquals(badEventId.statusCode, 400);
    json badEventIdBody = check badEventId.getJsonPayload();
    test:assertEquals(badEventIdBody, <json>{"error": {"message": "eventId must be an integer"}});

    // ── reapply is forwarded whole, so its own rules apply to it ──────────────
    http:Response scalarReapply = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "first-workflow-task", reapply: "none"}, asOperator);
    test:assertEquals(scalarReapply.statusCode, 400,
            "A reapply that is not an object must be reported, not dropped");
    json scalarReapplyBody = check scalarReapply.getJsonPayload();
    test:assertEquals(scalarReapplyBody, <json>{"error": {"message": "reapply must be a JSON object"}});

    http:Response unknownReapplyType = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "first-workflow-task", reapply: {"type": "everything"}}, asOperator);
    test:assertEquals(unknownReapplyType.statusCode, 400,
            "The nested reapply object must reach the operation intact");

    http:Response unknownExclusion = check mgmtClient->post("/workflow/workflows/wf-x/reset",
            {resetType: "first-workflow-task", reapply: {"exclude": ["timer"]}}, asOperator);
    test:assertEquals(unknownExclusion.statusCode, 400);

    // ── The pinned-run route carries runId through the same mapping ───────────
    http:Response pinnedNoType = check mgmtClient->post("/workflow/workflows/wf-x/run-y/reset", {}, asOperator);
    test:assertEquals(pinnedNoType.statusCode, 400);
    json pinnedBody = check pinnedNoType.getJsonPayload();
    test:assertEquals(pinnedBody, <json>{"error": {"message": "resetType is required"}});

    check stopManagementService();
}
