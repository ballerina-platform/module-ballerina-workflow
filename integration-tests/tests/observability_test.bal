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
// OBSERVABILITY - TESTS
// ================================================================================
//
// This package builds with observabilityIncluded = true and the tests run with
// metrics enabled (Prometheus reporter) and tracing enabled (the distribution's
// mock tracer), so these tests assert the real emission paths: the workflow_*
// metrics recorded by the wrapper layer and the client-side spans recorded by
// the workflow.observe submodule.
//
// ================================================================================

import ballerina/lang.runtime;
import ballerina/observe;
import ballerina/observe.mockextension as mock;
import ballerina/test;
import ballerina/workflow;

import ballerinax/prometheus as _;

// Service names under which the runtime may register spans; the mock tracer
// stores finished spans per service.
final readonly & string[] spanServiceCandidates = ["Ballerina", "Unknown Service"];

@test:Config {
    groups: ["integration", "observability"]
}
function testWorkflowMetricsEmission() returns error? {
    string workflowId = check workflow:run(observabilityFlow, {name: "metrics"});
    check workflow:sendData(observabilityFlow, workflowId, "obsApproval", true);
    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, "obs:metrics", "Workflow should complete normally");

    check assertMetricAtLeast("workflow_starts_total", {workflow_type: "workflow-observabilityFlow"}, 1.0);
    check assertMetricAtLeast("workflow_completions_total",
            {workflow_type: "workflow-observabilityFlow", status: "completed"}, 1.0);
    // Activities are scheduled under their plain function name (no workflow qualifier).
    check assertMetricAtLeast("workflow_activity_executions_total",
            {activity_type: "observabilityEcho", status: "completed"}, 1.0);
    check assertMetricAtLeast("workflow_data_events_sent_total", {data_name: "obsApproval"}, 1.0);

    // Duration summaries exist for the completed run (value is duration, not a count).
    test:assertTrue(findMetricValue("workflow_duration_seconds",
            {workflow_type: "workflow-observabilityFlow", status: "completed"}) !is (),
            "workflow_duration_seconds should be recorded for the completed run");
    test:assertTrue(findMetricValue("workflow_activity_duration_seconds",
            {activity_type: "observabilityEcho", status: "completed"}) !is (),
            "workflow_activity_duration_seconds should be recorded for the activity execution");
}

@test:Config {
    groups: ["integration", "observability"]
}
function testWorkflowFailureMetricsEmission() returns error? {
    string workflowId = check workflow:run(observabilityFailingFlow);
    anydata|error result = workflow:getWorkflowResult(workflowId, 30);
    test:assertTrue(result is error, "Failing workflow should surface an error result");

    check assertMetricAtLeast("workflow_completions_total",
            {workflow_type: "workflow-observabilityFailingFlow", status: "failed"}, 1.0);
}

@test:Config {
    groups: ["integration", "observability"]
}
function testWorkflowSpanEmission() returns error? {
    string workflowId = check workflow:run(observabilityFlow, {name: "spans"});
    check workflow:sendData(observabilityFlow, workflowId, "obsApproval", true);
    anydata result = check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(result, "obs:spans", "Workflow should complete normally");

    mock:Span startSpan = check findSpan("start_workflow workflow-observabilityFlow", workflowId);
    test:assertEquals(startSpan.tags["span.type"], "workflow", "start span should be typed as a workflow span");
    test:assertEquals(startSpan.tags["workflow.operation.name"], "start_workflow");
    test:assertEquals(startSpan.tags["workflow.type"], "workflow-observabilityFlow");

    mock:Span sendSpan = check findSpan("send_data obsApproval", workflowId);
    test:assertEquals(sendSpan.tags["workflow.data.name"], "obsApproval");

    mock:Span resultSpan = check findSpan(string `get_workflow_result ${workflowId}`, workflowId);
    test:assertEquals(resultSpan.tags["workflow.operation.name"], "get_workflow_result");
}

// ================================================================================
// HELPERS
// ================================================================================

# Looks up the current value of a metric matching the given name and tag subset.
#
# + name - The metric name
# + expectedTags - Tags the metric must carry (subset match)
# + return - The metric value, or `()` when no matching metric exists yet
function findMetricValue(string name, map<string> expectedTags) returns float? {
    foreach observe:Metric metric in observe:getAllMetrics() {
        if metric.name != name {
            continue;
        }
        boolean matches = true;
        foreach [string, string] [key, value] in expectedTags.entries() {
            if metric.tags[key] != value {
                matches = false;
                break;
            }
        }
        if matches {
            int|float value = metric.value;
            return value is int ? <float>value : value;
        }
    }
    return ();
}

# Asserts that a metric reaches at least the given value, retrying briefly because
# worker-side recording completes asynchronously with result delivery.
#
# + name - The metric name
# + expectedTags - Tags the metric must carry (subset match)
# + minimum - The minimum expected value
# + return - An error when the metric never reaches the minimum
function assertMetricAtLeast(string name, map<string> expectedTags, float minimum) returns error? {
    float? value = ();
    foreach int attempt in 0 ..< 10 {
        value = findMetricValue(name, expectedTags);
        if value is float && value >= minimum {
            return;
        }
        runtime:sleep(0.5);
    }
    return error(string `metric '${name}' with tags ${expectedTags.toString()} expected to reach ` +
            string `${minimum} but was ${value is float ? value.toString() : "absent"}`);
}

# Finds a finished span by operation name carrying the given workflow instance ID,
# retrying briefly because the tracer finishes spans asynchronously.
#
# + operationName - The span's operation name
# + workflowId - The workflow instance ID the span must be tagged with
# + return - The matching span, or an error when none is found
function findSpan(string operationName, string workflowId) returns mock:Span|error {
    foreach int attempt in 0 ..< 10 {
        foreach string serviceName in spanServiceCandidates {
            foreach mock:Span span in mock:getFinishedSpans(serviceName) {
                if span.operationName == operationName && span.tags["workflow.instance.id"] == workflowId {
                    return span;
                }
            }
        }
        runtime:sleep(0.5);
    }
    return error(string `span '${operationName}' for workflow '${workflowId}' was not recorded`);
}
