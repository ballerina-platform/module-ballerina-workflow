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

// ================================================================================
// BUILT-IN CONTEXT METHODS - TESTS
// ================================================================================
// Tests for built-in context methods: ctx.currentTime() and ctx.sleep().

import ballerina/test;
import ballerina/time;
import ballerina/workflow;

// --- currentTime tests ---

@test:Config {
    groups: ["integration"]
}
function testCurrentTime() returns error? {
    string testId = uniqueId("current-time");
    BuiltinActivityInput input = {id: testId};
    time:Utc beforeTime = time:utcNow();
    string workflowId = check workflow:run(currentTimeWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    time:Utc afterTime = time:utcNow();

    if result is map<anydata> {
        int seconds = <int>result["seconds"];
        // Workflow time should fall within the wall-clock window of the test (±5 s tolerance)
        test:assertTrue(seconds >= beforeTime[0] - 5, "Workflow time should be at or after test start");
        test:assertTrue(seconds <= afterTime[0] + 5, "Workflow time should be at or before test end");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}

// --- sleep tests ---

@test:Config {
    groups: ["integration"]
}
function testSleep() returns error? {
    string testId = uniqueId("sleep");
    BuiltinActivityInput input = {id: testId};
    string workflowId = check workflow:run(sleepWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, "slept successfully", "Result should confirm sleep completed");
}

// --- sleep with time advancement test ---

@test:Config {
    groups: ["integration"]
}
function testSleepWithTimeAdvancement() returns error? {
    string testId = uniqueId("sleep-time");
    BuiltinActivityInput input = {id: testId};
    string workflowId = check workflow:run(sleepWithTimeWorkflow, input);

    anydata result = check workflow:getWorkflowResult(workflowId, 30);

    if result is map<anydata> {
        int beforeSleep = <int>result["beforeSleep"];
        int afterSleep = <int>result["afterSleep"];
        // time:Utc[0] is epoch seconds, so after sleeping 2s the difference should be >= 2
        test:assertTrue((afterSleep - beforeSleep) >= 2,
            "Workflow time should advance by at least 2 seconds after sleep");
    } else {
        test:assertFail("Expected map<anydata> result");
    }
}
