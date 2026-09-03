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

import ballerina/jballerina.java;
import ballerina/log;
import ballerina/observe;

// Tag names attached to workflow tracing spans. Only identifiers and
// declared names are recorded — never business payloads.
enum WorkflowTagNames {
    OPERATION_NAME = "workflow.operation.name",
    WORKFLOW_TYPE = "workflow.type",
    INSTANCE_ID = "workflow.instance.id",
    DATA_NAME = "workflow.data.name",
    HUMAN_TASK_ID = "workflow.human_task.id",
    AGENT_NAME = "gen_ai.agent.name",
    EVENT_NAME = "workflow.event.name"
}

// Operation names recorded on spans, one per instrumented client-side call.
enum Operations {
    START_WORKFLOW = "start_workflow",
    SEND_DATA = "send_data",
    GET_WORKFLOW_RESULT = "get_workflow_result",
    COMPLETE_HUMAN_TASK = "complete_human_task",
    START_AGENT = "start_agent",
    SEND_AGENT_EVENT = "send_agent_event"
}

# Represents a workflow tracing span that allows adding tags and closing the span.
public type WorkflowSpan distinct isolated object {

    # Closes the span and records its final status.
    #
    # + 'err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ());
};

# Implementation of the `WorkflowSpan` interface used to trace workflow operations.
#
# Spans are recorded only when tracing is enabled for the program AND the
# current call is not executing inside a workflow body. Workflow bodies are
# replayed deterministically by the durable engine, so emitting spans from
# inside them would duplicate telemetry on every replay; execution-side
# visibility is provided by the engine's history and the management API instead.
isolated class BaseSpanImp {
    *WorkflowSpan;
    private final int|error? spanId;

    isolated function init(string name) {
        if !isSpanRecordingEnabled() {
            self.spanId = ();
            return;
        }
        int|error spanId = observe:startSpan(name);
        self.spanId = spanId;
        if spanId is error {
            log:printError("failed to start workflow span", 'error = spanId);
            return;
        }
        addOtherTags("span.type", "workflow", spanId);
    }

    isolated function addTag(WorkflowTagNames key, string value) {
        int|error? spanId = self.spanId;
        if spanId is () {
            return;
        }
        if spanId is error {
            return;
        }
        error? result = observe:addTagToSpan(key, value, spanId);
        if result is error {
            log:printError(string `failed to add tag '${key}' to span with ID '${spanId}'`, 'error = result);
        }
    }

    public isolated function close(error? err = ()) {
        int|error? spanId = self.spanId;
        if spanId is () {
            return;
        }
        if spanId is error {
            return;
        }
        error? result;
        if err is error {
            result = observe:finishSpanWithError(spanId, err);
        } else {
            result = observe:finishSpan(spanId);
        }
        if result is error {
            log:printError(string `failed to close span with ID '${spanId}'`, 'error = result);
        }
    }
}

isolated function isSpanRecordingEnabled() returns boolean {
    return observe:isTracingEnabled() && !isInsideWorkflowContext();
}

isolated function addOtherTags(string key, string value, int spanId) {
    error? result = observe:addTagToSpan(key, value, spanId);
    if result is error {
        log:printError(string `failed to add tag '${key}' to span with ID '${spanId}'`, 'error = result);
    }
}

# Checks whether the current call is executing inside a workflow body.
#
# + return - `true` when called from within a workflow execution context
isolated function isInsideWorkflowContext() returns boolean = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative",
    name: "isInsideWorkflowContext"
} external;

# Returns the registered workflow type name for a workflow function.
#
# + processFunction - The workflow function
# + return - The workflow type name used by the durable engine
public isolated function workflowTypeNameOf(function processFunction) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative",
    name: "workflowTypeNameOf"
} external;
