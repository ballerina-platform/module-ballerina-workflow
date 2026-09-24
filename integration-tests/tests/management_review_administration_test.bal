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

// ================================================================================
// Review activity eligibility flags and administration routing
// ================================================================================
// A review reports the same `canComplete` / `canAdminister` answers a human task
// does, and each administration route serves one kind of task only.

import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management as management;

isolated function reviewThroughCommand(string taskId, string[] roles, string userId)
        returns map<json>|error {
    json result = check management:executeCommand({
        operation: management:GET_REVIEW_ACTIVITY,
        params: {taskId: taskId},
        identity: {userId: userId, roles: roles}
    });
    return <map<json>>result;
}

@test:Config {groups: ["integration", "management"]}
function testReviewActivityReportsEligibilityFlags() returns error? {
    string workflowId = check workflow:run(administeredReviewWorkflow, {id: "flags", mode: "fail"});
    management:ReviewActivitySummary review = check waitForPendingReviewActivity(workflowId);

    // The audience may decide it but does not administer it.
    map<json> asAudience = check reviewThroughCommand(review.taskId, ["approver"], "bob");
    test:assertEquals(asAudience["canComplete"], true, "the audience may decide the review");
    test:assertEquals(asAudience["canAdminister"], false, "the audience does not administer it");

    // The administrator gets both, exactly as on a human task.
    map<json> asAdmin = check reviewThroughCommand(review.taskId, ["ops-lead"], "dana");
    test:assertEquals(asAdmin["canComplete"], true, "an administrator may decide it");
    test:assertEquals(asAdmin["canAdminister"], true, "an administrator administers it");

    // The listing answers the same way it does for a single read.
    json listed = check management:executeCommand({
        operation: management:LIST_REVIEW_ACTIVITIES,
        params: {status: "PENDING"},
        identity: {userId: "dana", roles: ["ops-lead"]}
    });
    map<json> page = <map<json>>listed;
    json[] items = <json[]>page["items"];
    boolean found = false;
    foreach json item in items {
        map<json> row = <map<json>>item;
        if row["taskId"] == review.taskId {
            found = true;
            test:assertEquals(row["canComplete"], true, "a listed review says it may be decided");
            test:assertEquals(row["canAdminister"], true, "a listed review says it may be administered");
        }
    }
    test:assertTrue(found, "the pending review should be listed for its administrator");

    json _ = check management:executeCommand({
        operation: management:DECIDE_REVIEW_ACTIVITY,
        params: {taskId: review.taskId, action: "proceed-with-input", input: {mode: "ok"}},
        identity: {userId: "bob", roles: ["approver"]}
    });
    _ = check workflow:getWorkflowResult(workflowId, 60);
}

@test:Config {groups: ["integration", "management"]}
function testAdministrationRouteServesOneTaskKind() returns error? {
    string workflowId = check workflow:run(administeredReviewWorkflow, {id: "kind", mode: "fail"});
    management:ReviewActivitySummary review = check waitForPendingReviewActivity(workflowId);

    // The human-task route must not administer a review, even for its administrator.
    json|management:Error asHumanTask = management:executeCommand({
        operation: management:REASSIGN_TASK,
        params: {taskId: review.taskId, userRoles: ["other-approver"], kind: "HUMAN_TASK"},
        identity: {userId: "dana", roles: ["ops-lead"]}
    });
    test:assertTrue(asHumanTask is management:Error,
            "a review id must be refused by the human-task administration route");

    // A kind the route does not serve is refused rather than waved through unchecked.
    json|management:Error asUnknownKind = management:executeCommand({
        operation: management:REASSIGN_TASK,
        params: {taskId: review.taskId, userRoles: ["other-approver"], kind: "OTHER"},
        identity: {userId: "dana", roles: ["ops-lead"]}
    });
    test:assertTrue(asUnknownKind is management:InvalidRequestError,
            "an unsupported kind must be refused");

    // Its own route accepts it.
    json|management:Error asReview = management:executeCommand({
        operation: management:REASSIGN_TASK,
        params: {taskId: review.taskId, userRoles: ["other-approver"], kind: "REVIEW_ACTIVITY"},
        identity: {userId: "dana", roles: ["ops-lead"]}
    });
    test:assertTrue(asReview !is management:Error, "the review route should reassign the review");

    // The reassignment took effect: the new audience decides it.
    json _ = check management:executeCommand({
        operation: management:DECIDE_REVIEW_ACTIVITY,
        params: {taskId: review.taskId, action: "proceed-with-input", input: {mode: "ok"}},
        identity: {userId: "erin", roles: ["other-approver"]}
    });
    _ = check workflow:getWorkflowResult(workflowId, 60);
}
