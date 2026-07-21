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
// Object-model durable agent declaration — unit tests
// ================================================================================
// The compiler plugin does not run on the workflow package itself, so these tests
// exercise the declaration surface (object construction, driver method stubs) and
// the wfInternal registration natives directly — the same calls the plugin
// generates at module init for user packages.

import ballerina/test;
import ballerina/workflow.internal as wfInternal;

final MockModelProvider declTestModel = new;

@Activity
function declTestActivity(string item) returns boolean|error {
    return item.length() > 0;
}

final DurableAgent declTestAgent = check new ({
    systemPrompt: {role: "Test assistant", instructions: "Assist with tests."},
    model: declTestModel,
    activities: [declTestActivity],
    events: [
        {name: "chat", request: string, response: string, cardinality: MULTI_EVENT}
    ],
    humanTasks: [
        {name: "signoff", roles: "manager"}
    ]
});

@test:Config {}
function testDurableAgentDeclRegistration() returns error? {
    // Mirror the plugin-generated module-init registration.
    _ = check wfInternal:registerDurableAgentDecl("declTestAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    _ = check wfInternal:registerDurableAgentActivity("declTestAgent", "declTestActivity",
        declTestActivity, {requiresApproval: false});
    _ = check wfInternal:registerDurableAgentEvent("declTestAgent", "chat", string, string,
        "MULTI_EVENT");
    _ = check wfInternal:registerDurableAgentHumanTask("declTestAgent", "signoff",
        {roles: "manager"});

    // Registering the same agent name twice is an error.
    boolean|error duplicate = wfInternal:registerDurableAgentDecl("declTestAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    test:assertTrue(duplicate is error, "Duplicate agent registration should fail");

    // Capability registration against an unknown agent is an error.
    boolean|error unknown = wfInternal:registerDurableAgentActivity("noSuchAgent", "x",
        declTestActivity);
    test:assertTrue(unknown is error, "Registration for an unknown agent should fail");
}

@test:Config {}
function testDurableAgentDriverStubs() {
    // The driver methods are declaration anchors until the object-model runner lands;
    // each returns a descriptive error rather than silently doing nothing.
    string|error runResult = declTestAgent.run("hello");
    test:assertTrue(runResult is error, "run should not be supported yet");
    if runResult is error {
        test:assertTrue(runResult.message().includes("not supported yet"));
    }

    string|error sendResult = declTestAgent.sendEvent("wf-1", "chat", "hi");
    test:assertTrue(sendResult is error, "sendEvent should not be supported yet");

    string|error getResult = declTestAgent.getResult("wf-1");
    test:assertTrue(getResult is error, "getResult should not be supported yet");

    string|error waitResult = declTestAgent.waitForResult("wf-1");
    test:assertTrue(waitResult is error, "waitForResult should not be supported yet");

    string|error eventResult = declTestAgent.getEventResult("wf-1", "token-1");
    test:assertTrue(eventResult is error, "getEventResult should not be supported yet");

    string|error waitEventResult = declTestAgent.waitForEventResult("wf-1", "token-1");
    test:assertTrue(waitEventResult is error, "waitForEventResult should not be supported yet");
}
