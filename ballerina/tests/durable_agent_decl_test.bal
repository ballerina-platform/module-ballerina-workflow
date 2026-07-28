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
        {roles: "manager", timeout: {minutes: 5}});

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
function testDurableAgentBuiltinActivityNameCollision() returns error? {
    // A declared activity must not shadow a built-in agent activity: the runner
    // registration rejects the collision instead of silently overwriting the
    // built-in entry (which would make the loop's llmChat calls invoke the user
    // function).
    _ = check wfInternal:registerDurableAgentDecl("builtinCollisionAgent", declTestModel,
        {role: "Test assistant", instructions: "Assist with tests."}, 8);
    _ = check wfInternal:registerDurableAgentActivity("builtinCollisionAgent", "llmChat",
        declTestActivity);
    boolean|error collision = wfInternal:registerDurableAgentRunner("builtinCollisionAgent");
    test:assertTrue(collision is error,
        "A declared activity named after a built-in agent activity should be rejected");
    if collision is error {
        test:assertTrue(collision.message().includes("built-in"),
            "The collision error should name the built-in conflict: " + collision.message());
    }
}

@test:Config {}
function testDurableAgentDriverStubs() {
    // run() requires the plugin-generated name binding, which does not run for the
    // module's own tests — an unbound object reports a descriptive error.
    string|error runResult = declTestAgent.run("hello");
    test:assertTrue(runResult is error, "run on an unbound agent should fail");
    if runResult is error {
        test:assertTrue(runResult.message().includes("no agent name is bound"));
    }

    // The event-turn methods are live but need a running instance: without one (or
    // without a workflow client in this unit-test context) each reports the failing
    // instance/turn in its error.
    string|error sendResult = declTestAgent.sendData("wf-1", "chat", "hi");
    test:assertTrue(sendResult is error, "sendData to a missing instance should fail");
    if sendResult is error {
        test:assertTrue(sendResult.message().includes("agent instance 'wf-1'")
                || sendResult.message().includes("Workflow client not initialized"),
            "sendData error should name the missing instance: " + sendResult.message());
    }

    string|error eventResult = declTestAgent.getDataResult("wf-1", "token-1");
    test:assertTrue(eventResult is error, "getDataResult for a missing instance should fail");
    if eventResult is error {
        test:assertTrue(eventResult.message().includes("agent instance 'wf-1'")
                || eventResult.message().includes("Workflow client not initialized"),
            "getDataResult error should name the missing instance: " + eventResult.message());
    }

    string|error waitEventResult = declTestAgent.waitForDataResult("wf-1", "token-1");
    test:assertTrue(waitEventResult is error, "waitForDataResult for a missing instance should fail");
    if waitEventResult is error {
        test:assertTrue(waitEventResult.message().includes("agent instance 'wf-1'")
                || waitEventResult.message().includes("Workflow client not initialized"),
            "waitForDataResult error should name the missing instance: " + waitEventResult.message());
    }
}
