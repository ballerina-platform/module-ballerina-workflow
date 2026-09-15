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

// Route coverage for the data-event resource: the two path parameters reaching the operation, and
// the status code the reserved-name denial becomes. Both are decided before anything reads the
// instance, which is what makes them testable without a live Temporal server — a name the runtime
// reserves is refused on the name alone.

@test:Config {}
function testSendDataRouteRefusesReservedNames() returns error? {
    check startManagementListener();
    http:Client mgmtClient = check new (string `http://localhost:${port}`, timeout = 5);

    // A framework control signal carries the checks of the operation that sends it — completing a
    // task, resuming a run — so forging one through the data-event route is a denial, not a
    // delivery failure.
    foreach string reserved in ["__wf_resume", "taskCompletion", "taskDecision"] {
        http:Response response = check mgmtClient->post(
                string `/workflow/workflows/wf-x/data/${reserved}`, {approved: true});
        test:assertEquals(response.statusCode, 403,
                string `'${reserved}' is reserved for the framework and must not be deliverable`);
        json body = check response.getJsonPayload();
        test:assertEquals(body, <json>{"error": {"message": "reserved event name: " + reserved}},
                "The denial names the event it refused");
    }

    // The same route with an ordinary name gets past the gate and on to delivery, where the
    // absent runtime is what stops it. A `dataName` that never reached the operation would be
    // refused here instead.
    http:Response ordinary = check mgmtClient->post("/workflow/workflows/wf-x/data/approval",
            {approved: true});
    test:assertNotEquals(ordinary.statusCode, 403,
            "An ordinary event name is not a reserved one");

    check stopManagementService();
}
