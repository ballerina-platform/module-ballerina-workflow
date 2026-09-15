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

import ballerina/jballerina.java;
import ballerina/log;
import ballerina/observe;
import ballerina/time;

# Whether a task decision's content (what the person was shown and submitted) joins its span and audit entry.
# Who decided, in which roles, what and when are always recorded; this governs only the content.
configurable boolean captureHumanTaskContent = true;

# Whether every activity attempt logs its arguments and result (or error) to the worker's module log.
# Long values are truncated.
configurable boolean captureActivityContent = true;

# Whether the runtime publishes one structured log record per workflow event under `logger = "workflow-metrics"`,
# the workflow counterpart of `ballerinax/metrics.logs`. Structural fields only, never content.
configurable boolean publishMetricSamples = true;

function init() {
    configure(captureActivityContent, publishMetricSamples);
}

# Reports whether the runtime publishes one structured log record per workflow event.
#
# + return - The value of `publishMetricSamples`
public isolated function isMetricSamplesPublished() returns boolean => publishMetricSamples;

# Reports whether decision content is recorded on task-decision spans and audit entries.
#
# + return - The value of `captureHumanTaskContent`
public isolated function isHumanTaskContentCaptured() returns boolean => captureHumanTaskContent;

# Reports whether activity executions log their arguments and results.
#
# + return - The value of `captureActivityContent`
public isolated function isActivityContentCaptured() returns boolean => captureActivityContent;

// Span tag names: identifiers, declared names and, for a task decision, who made it.
enum WorkflowTagNames {
    OPERATION_NAME = "workflow.operation.name",
    WORKFLOW_TYPE = "workflow.type",
    INSTANCE_ID = "workflow.instance.id",
    DATA_NAME = "workflow.data.name",
    HUMAN_TASK_ID = "workflow.human_task.id",
    REVIEW_ACTIVITY_ID = "workflow.review_activity.id",
    TASK_NAME = "workflow.task.name",
    TASK_ACTION = "workflow.task.action",
    TASK_INPUT = "workflow.task.input",
    TASK_CONTENT = "workflow.task.content",
    USER_ID = "user.id",
    USER_ROLES = "user.roles",
    IDENTITY_SOURCE = "user.identity.source",
    AGENT_NAME = "gen_ai.agent.name",
    EVENT_NAME = "workflow.event.name"
}

// Operation names recorded on spans, one per instrumented client-side call.
enum Operations {
    START_WORKFLOW = "start_workflow",
    SEND_DATA = "send_data",
    GET_WORKFLOW_RESULT = "get_workflow_result",
    COMPLETE_HUMAN_TASK = "complete_human_task",
    FAIL_HUMAN_TASK = "fail_human_task",
    COMPLETE_REVIEW_ACTIVITY = "complete_review_activity",
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

// Records a span only when tracing is on and the call is outside a workflow body, since bodies replay.
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
        foreach [string, string] [key, value] in spanIdentityTags().entries() {
            addOtherTags(key, value, spanId);
        }
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

isolated function nowText() returns string => time:utcToString(time:utcNow());

isolated function isInsideWorkflowContext() returns boolean = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

# Returns the workflow type name the engine registers for a workflow function.
# + processFunction - The workflow function
# + return - The engine's workflow type name
public isolated function workflowTypeNameOf(function processFunction) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// Hands the worker-side switches to the runtime; workflow and activity threads cannot read configurables.
isolated function configure(boolean activityContent, boolean metricSamples) = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// Counts one task decision in the metric registry; taskName is "none" when the decision was refused unresolved.
isolated function recordTaskDecisionMetric(string taskKind, string taskName, string action,
        boolean accepted, string errorType) = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// Identity tags every span carries: module, caller side, engine endpoint, task queue, host.
isolated function spanIdentityTags() returns map<string> = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// The error's type name, for the bounded error_type dimension.
isolated function errorTypeName(error e) returns string {
    // `typeof e` prints as `typedesc <TypeName>`; the name starts after the space.
    string typedescString = (typeof e).toString();
    return typedescString.length() > 9 ? typedescString.substring(9) : typedescString;
}
