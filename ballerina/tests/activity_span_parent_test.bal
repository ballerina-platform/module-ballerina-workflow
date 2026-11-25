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

import ballerina/lang.runtime;
import ballerina/observe as observability;
import ballerina/observe.mockextension as mock;
import ballerina/test;

// An activity's span parents the calls the Ballerina tracer observes inside it, in plain workflows as in agents.
// Activities run one after another on pooled threads that keep their strand, so each must get its own parent.

isolated client class ObservedProbe {
    isolated remote function ping(string label) returns string => "pong " + label;

    isolated remote function explode(string label) returns string {
        panic error("probe exploded: " + label);
    }
}

final ObservedProbe observedProbe = new;

@Activity
isolated function probeActivity(string label) returns string|error {
    return observedProbe->ping(label);
}

@Activity
isolated function explodingProbeActivity(string label) returns string|error {
    return observedProbe->explode(label);
}

@Workflow
function sequentialProbeWorkflow(Context ctx, string input) returns string|error {
    string first = check ctx->callActivity(probeActivity, {label: input + "-1"});
    string second = check ctx->callActivity(probeActivity, {label: input + "-2"});
    string third = check ctx->callActivity(probeActivity, {label: input + "-3"});
    return string `${first},${second},${third}`;
}

@Workflow
function panicThenProbeWorkflow(Context ctx, string input) returns string|error {
    string[] answers = [];
    foreach int round in 1 ... 3 {
        string|error exploded = ctx->callActivity(explodingProbeActivity, {label: string `${input}-boom-${round}`});
        if exploded is string {
            return error("the exploding probe should fail");
        }
        string answer = check ctx->callActivity(probeActivity, {label: string `${input}-${round}`});
        answers.push(answer);
    }
    return string:'join(",", ...answers);
}

@test:BeforeSuite
function setupActivitySpanParentTests() returns error? {
    map<function> activities = {
        "probeActivity": probeActivity,
        "explodingProbeActivity": explodingProbeActivity
    };
    _ = check registerWorkflowForTest(sequentialProbeWorkflow, "sequentialProbeWorkflow", activities);
    _ = check registerWorkflowForTest(panicThenProbeWorkflow, "panicThenProbeWorkflow", activities);
}

@test:Config {
    groups: ["observe"]
}
function testEachActivityParentsOnlyItsOwnObservedCalls() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string runId = check run(sequentialProbeWorkflow, "seq");
    string result = check waitForResult(runId, timeout = {seconds: 30});
    test:assertEquals(result, "pong seq-1,pong seq-2,pong seq-3");

    mock:Span[] activities = check activitySpansOf(runId, "activity probeActivity", 3);
    assertEachActivityOwnsOneProbeCall(activities);
}

@test:Config {
    groups: ["observe"]
}
function testAPanicInsideAnActivityDoesNotLeakItsSpanToTheNext() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string runId = check run(panicThenProbeWorkflow, "panic");
    string result = check waitForResult(runId, timeout = {seconds: 30});
    test:assertEquals(result, "pong panic-1,pong panic-2,pong panic-3");

    // The panic leaves the observed call's context on the thread's strand; the next activity must not inherit it.
    mock:Span[] activities = check activitySpansOf(runId, "activity probeActivity", 3);
    assertEachActivityOwnsOneProbeCall(activities);
}

@test:Config {
    groups: ["observe"]
}
function testPlainWorkflowSpansCarryNoConversationId() returns error? {
    if !observability:isTracingEnabled() {
        return;
    }
    string runId = check run(sequentialProbeWorkflow, "plain");
    string _ = check waitForResult(runId, timeout = {seconds: 30});

    mock:Span[] story = check workerSpansOf(runId, 4);
    foreach mock:Span span in story {
        test:assertFalse(span.tags.hasKey("gen_ai.conversation.id"),
                string `'${span.operationName}' of a plain workflow is no conversation`);
    }
}

function activitySpansOf(string runId, string operationName, int count) returns mock:Span[]|error {
    mock:Span[] found = [];
    foreach int attempt in 0 ..< 20 {
        mock:Span[] story = check workerSpansOf(runId, 1);
        found = story.filter(span => span.operationName == operationName);
        if found.length() >= count {
            return found;
        }
        runtime:sleep(0.5);
    }
    return error(string `only ${found.length()} '${operationName}' spans finished for ${runId}`);
}

// Each activity has exactly one observed probe call under it, and no two activities share a child.
function assertEachActivityOwnsOneProbeCall(mock:Span[] activities) {
    map<string> parentOfChild = {};
    foreach mock:Span activity in activities {
        mock:Span[]|error children = childSpansOf(activity);
        if children is error {
            test:assertFail(children.message());
        }
        test:assertEquals(children.length(), 1,
                string `activity span ${activity.spanId} should parent exactly its own probe call`);
        foreach mock:Span child in children {
            test:assertEquals(child.traceId, activity.traceId, "the probe call joins the run's trace");
            test:assertFalse(parentOfChild.hasKey(child.spanId), "a probe call belongs to one activity");
            parentOfChild[child.spanId] = activity.spanId;
        }
    }
}
