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

import ballerina/test;
import ballerina/workflow.observe;

// ================================================================================
// workflow.observe SUBMODULE - TESTS
// ================================================================================
// This build runs without observabilityIncluded, so tracing is disabled and every
// span operation must be a safe no-op — the default for all existing users. The
// observability-enabled behavior (real span/metric emission) is asserted in the
// integration tests, which build with observabilityIncluded = true.
// ================================================================================

function observeSampleFlow() returns string => "ok";

@test:Config {
    groups: ["observe"]
}
function testWorkflowTypeNameOf() {
    test:assertEquals(observe:workflowTypeNameOf(observeSampleFlow), "workflow-observeSampleFlow",
            "workflowTypeNameOf should apply the engine's workflow type prefix to the function name");
}

@test:Config {
    groups: ["observe"]
}
function testStartWorkflowSpanNoOpWhenTracingDisabled() {
    observe:StartWorkflowSpan span = observe:createStartWorkflowSpan("workflow-observeSampleFlow");
    span.addInstanceId("wf-instance-1");
    span.close();

    observe:StartWorkflowSpan failedSpan = observe:createStartWorkflowSpan("workflow-observeSampleFlow");
    failedSpan.close(error("start failed"));
}

@test:Config {
    groups: ["observe"]
}
function testDataAndResultSpansNoOpWhenTracingDisabled() {
    observe:SendDataSpan sendSpan = observe:createSendDataSpan("wf-instance-1", "approval");
    sendSpan.close();

    observe:GetWorkflowResultSpan resultSpan = observe:createGetWorkflowResultSpan("wf-instance-1");
    resultSpan.close(error("timed out"));

    observe:CompleteHumanTaskSpan taskSpan = observe:createCompleteHumanTaskSpan("humantask-wf-1-approve-x");
    taskSpan.close();
}

@test:Config {
    groups: ["observe"]
}
function testAgentSpansNoOpWhenTracingDisabled() {
    observe:StartAgentSpan agentSpan = observe:createStartAgentSpan("assistantAgent");
    agentSpan.addInstanceId("wf-agent-1");
    agentSpan.close();

    observe:SendAgentEventSpan eventSpan = observe:createSendAgentEventSpan("assistantAgent", "wf-agent-1", "chat");
    eventSpan.close(error("agent event failed"));
}
