// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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

import ballerina/test;

@Workflow
function sleepUnitsWorkflow(Context ctx, Duration input) returns string|error {
    check ctx.sleep(input);
    return "slept";
}

@test:BeforeSuite
function setupSleepUnitsTests() returns error? {
    _ = check registerWorkflowForTest(sleepUnitsWorkflow, "sleepUnitsWorkflow");
}

@test:Config {groups: ["unit"]}
function testSleepCountsWeeksAndDays() returns error? {
    // Time skipping is off, so a week cannot be observed; this sums to 200 ms only when weeks and
    // days both count (a dropped week waits seven days, a dropped day is refused as negative).
    string id = check run(sleepUnitsWorkflow, {weeks: -1, days: 6, hours: 24, seconds: 0.2});
    string result = check waitForResult(id, timeout = {seconds: 15});
    test:assertEquals(result, "slept");
}

@test:Config {groups: ["unit"]}
function testSleepRefusesMonthsAndYears() returns error? {
    string id = check run(sleepUnitsWorkflow, {months: 1});
    string|error refused = waitForResult(id, timeout = {seconds: 15});
    test:assertTrue(refused is InstanceFailedError && refused.message().includes("months or years"),
        "A sleep over months must fail the instance with the reason, got " + (refused is error ? refused.message() : refused));
}
