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
    ROOT_INSTANCE_ID = "workflow.root.instance.id",
    DATA_NAME = "workflow.data.name",
    HUMAN_TASK_ID = "workflow.human_task.id",
    REVIEW_ACTIVITY_ID = "workflow.review_activity.id",
    TASK_ID = "workflow.task.id",
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
    ADMINISTER_TASK = "administer_task",
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
//
// The span is built when it closes: which instance the call belongs to is sometimes known only by then, and
// that is what places the span in the instance's trace rather than under the caller.
isolated class BaseSpanImp {
    *WorkflowSpan;
    private final string name;
    private final int spanId;
    private map<string> tags = {};

    isolated function init(string name) {
        self.name = name;
        self.spanId = isSpanRecordingEnabled() ? beginClientSpan() : 0;
    }

    isolated function addTag(WorkflowTagNames key, string value) {
        if self.spanId == 0 {
            return;
        }
        lock {
            self.tags[key] = value;
        }
    }

    public isolated function close(error? err = ()) {
        if self.spanId == 0 {
            return;
        }
        map<string> tags;
        lock {
            tags = self.tags.clone();
        }
        endClientSpan(self.spanId, self.name, tags, anchorInstanceOf(tags),
                err is error ? errorTypeName(err) : "", err is error ? err.message() : "");
    }
}

// The instance whose trace the span joins: the top of the run's tree when the receipt named it, else the run a
// call names. A task id is never an anchor: nothing else joins a trace derived from it, so the span would stand
// alone; with no anchor the runtime keeps the span in its caller's trace instead.
isolated function anchorInstanceOf(map<string> tags) returns string {
    return tags[ROOT_INSTANCE_ID] ?: tags[INSTANCE_ID] ?: "";
}

isolated function isSpanRecordingEnabled() returns boolean {
    return observe:isTracingEnabled() && !isInsideWorkflowContext();
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

// Opens a client-side span recording; 0 when nothing would be recorded.
isolated function beginClientSpan() returns int = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// Records the opened span in the trace of `instanceId`, linked to the span the caller was in.
isolated function endClientSpan(int spanId, string name, map<string> tags, string instanceId,
        string errorType, string errorMessage) = @java:Method {
    'class: "io.ballerina.lib.workflow.observability.ObservabilityNative"
} external;

// The error's type name, for the bounded error_type dimension.
isolated function errorTypeName(error e) returns string {
    // `typeof e` prints as `typedesc <TypeName>`; the name starts after the space.
    string typedescString = (typeof e).toString();
    return typedescString.length() > 9 ? typedescString.substring(9) : typedescString;
}
