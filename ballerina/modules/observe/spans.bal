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

import ballerina/log;

# Represents a tracing span for starting a workflow instance.
public isolated distinct class StartWorkflowSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string workflowType) {
        self.baseSpan = new (string `${START_WORKFLOW} ${workflowType}`);
        self.baseSpan.addTag(OPERATION_NAME, START_WORKFLOW);
        self.baseSpan.addTag(WORKFLOW_TYPE, workflowType);
    }

    # Records the instance ID assigned to the started workflow.
    #
    # + instanceId - The workflow instance identifier
    public isolated function addInstanceId(string instanceId) {
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for sending data to a running workflow instance.
public isolated distinct class SendDataSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string instanceId, string dataName) {
        self.baseSpan = new (string `${SEND_DATA} ${dataName}`);
        self.baseSpan.addTag(OPERATION_NAME, SEND_DATA);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
        self.baseSpan.addTag(DATA_NAME, dataName);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for waiting on a workflow instance's result.
public isolated distinct class GetWorkflowResultSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string instanceId) {
        self.baseSpan = new (string `${GET_WORKFLOW_RESULT} ${instanceId}`);
        self.baseSpan.addTag(OPERATION_NAME, GET_WORKFLOW_RESULT);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# The kind of task a person decides on.
public enum TaskKind {
    HUMAN_TASK,
    REVIEW_ACTIVITY
}

# What a person decided: `complete` or `fail` for a human task; `proceed`,
# `proceed-with-input` or `reject` for a review activity.
public type TaskAction "complete"|"fail"|"proceed"|"proceed-with-input"|"reject";

# Where a decision's user identity came from: `verified` when it was resolved from a
# credential the receiving service's auth layer validated (a JWT claim, a basic-auth
# username), `asserted` when the application or a forwarded header supplied it.
public type IdentitySource "asserted"|"verified";

# Decision content longer than this is cut, so one oversized submission cannot flood a span
# or a log line.
const int MAX_CONTENT_CHARS = 8192;

const string DECISION_ACCEPTED = "accepted";
const string DECISION_DENIED = "denied";
// A refusal that never resolved the task has no name for it; the uniform label
// contract uses the `none` sentinel for a dimension with no value.
const string UNKNOWN_TASK_NAME = "none";

# One decision a person makes on a task, as a span that also writes the decision's audit entry and counts it.
# The audit entry is written whether or not tracing or metrics are enabled.
public isolated distinct class TaskDecisionSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;
    private final TaskKind kind;
    private final string taskId;
    private final TaskAction action;
    private string? userId = ();
    private string[] & readonly userRoles = [];
    private IdentitySource identitySource = "asserted";
    private string? contentJson = ();
    private string? taskInputJson = ();
    private string? taskName = ();
    private string? parentWorkflowId = ();
    private string[] & readonly assignedRoles = [];

    isolated function init(TaskKind kind, string taskId, TaskAction action) {
        self.kind = kind;
        self.taskId = taskId;
        self.action = action;
        Operations operation = kind == HUMAN_TASK
            ? (action == "fail" ? FAIL_HUMAN_TASK : COMPLETE_HUMAN_TASK)
            : COMPLETE_REVIEW_ACTIVITY;
        self.baseSpan = new (string `${operation} ${taskId}`);
        self.baseSpan.addTag(OPERATION_NAME, operation);
        self.baseSpan.addTag(kind == HUMAN_TASK ? HUMAN_TASK_ID : REVIEW_ACTIVITY_ID, taskId);
        self.baseSpan.addTag(TASK_ACTION, action);
    }

    # Records who decided, as the caller identified them, and where that identity came from.
    # + identitySource - `verified` when resolved from a credential the receiving service validated
    public isolated function addDecider(string? userId, string[]? roles,
            IdentitySource identitySource = "asserted") {
        string[] & readonly presented = (roles ?: []).cloneReadOnly();
        lock {
            self.userId = userId;
            self.userRoles = presented;
            self.identitySource = identitySource;
        }
        if userId is string {
            self.baseSpan.addTag(USER_ID, userId);
        }
        if presented.length() > 0 {
            self.baseSpan.addTag(USER_ROLES, string:'join(",", ...presented));
        }
        self.baseSpan.addTag(IDENTITY_SOURCE, identitySource);
    }

    # Records what the person submitted. A no-op when `captureHumanTaskContent` is off.
    # + content - The submitted value
    public isolated function addContent(anydata content) {
        if !captureHumanTaskContent {
            return;
        }
        string text = boundedJson(content);
        lock {
            self.contentJson = text;
        }
        self.baseSpan.addTag(TASK_CONTENT, text);
    }

    # Records what the runtime confirmed on accepting the decision: task name, parent, allowed roles and input.
    # + receipt - The receipt the runtime returned for the accepted decision
    public isolated function addTaskDetails(map<anydata> receipt) {
        anydata name = receipt["taskName"];
        anydata parent = receipt["parentWorkflowId"];
        anydata roles = receipt["assignedRoles"];
        string[] & readonly allowed = (roles is anydata[])
            ? (from anydata role in roles where role is string select role).cloneReadOnly()
            : [];
        string? shown = (captureHumanTaskContent && receipt.hasKey("taskInput"))
            ? boundedJson(receipt["taskInput"]) : ();
        lock {
            self.taskName = (name is string) ? name : ();
            self.parentWorkflowId = (parent is string) ? parent : ();
            self.assignedRoles = allowed;
            self.taskInputJson = shown;
        }
        if name is string {
            self.baseSpan.addTag(TASK_NAME, name);
        }
        if shown is string {
            self.baseSpan.addTag(TASK_INPUT, shown);
        }
    }

    # Closes the span, writes the decision's audit entry, and counts the decision.
    #
    # + err - The error the runtime refused the decision with, if it did
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
        self.audit(err);
    }

    isolated function audit(error? err) {
        string? userId;
        string[] & readonly userRoles;
        IdentitySource identitySource;
        string? contentJson;
        string? taskInputJson;
        string? taskName;
        string? parentWorkflowId;
        string[] & readonly assignedRoles;
        lock {
            userId = self.userId;
            userRoles = self.userRoles;
            identitySource = self.identitySource;
            contentJson = self.contentJson;
            taskInputJson = self.taskInputJson;
            taskName = self.taskName;
            parentWorkflowId = self.parentWorkflowId;
            assignedRoles = self.assignedRoles;
        }
        string outcome = (err is ()) ? DECISION_ACCEPTED : DECISION_DENIED;
        recordTaskDecisionMetric(self.kind, taskName ?: UNKNOWN_TASK_NAME, self.action, err is (),
                (err is ()) ? "" : errorTypeName(err));
        if publishMetricSamples {
            // The decision's sample for log-based metrics: what was decided and on which task —
            // never who, and never the content. Those stay on the audit entry below.
            log:printInfo("", logger = "workflow-metrics", sample = "task.decided", task_kind = self.kind,
                    task_name = taskName ?: UNKNOWN_TASK_NAME, action = self.action,
                    outcome = (err is ()) ? "success" : "failure");
        }
        string subject = self.kind == HUMAN_TASK ? "human task" : "review activity";
        if err is () {
            log:printInfo(string `${subject} decision ${outcome}`, taskKind = self.kind, taskId = self.taskId,
                    taskName = taskName, parentWorkflowId = parentWorkflowId, action = self.action,
                    outcome = outcome, userId = userId, userRoles = userRoles,
                    identitySource = identitySource, assignedRoles = assignedRoles,
                    decidedAt = nowText(), taskInput = taskInputJson, content = contentJson);
        } else {
            log:printWarn(string `${subject} decision ${outcome}`, 'error = err, taskKind = self.kind,
                    taskId = self.taskId, taskName = taskName, parentWorkflowId = parentWorkflowId,
                    action = self.action, outcome = outcome, userId = userId, userRoles = userRoles,
                    identitySource = identitySource, assignedRoles = assignedRoles,
                    decidedAt = nowText(), taskInput = taskInputJson, content = contentJson);
        }
    }
}

# A value as JSON, cut at `MAX_CONTENT_CHARS` so one oversized payload cannot flood a span or a line.
isolated function boundedJson(anydata value) returns string {
    string text = value.toJsonString();
    return text.length() > MAX_CONTENT_CHARS ? text.substring(0, MAX_CONTENT_CHARS) + "…" : text;
}

# Represents a tracing span for starting a durable agent instance.
public isolated distinct class StartAgentSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string agentName) {
        self.baseSpan = new (string `${START_AGENT} ${agentName}`);
        self.baseSpan.addTag(OPERATION_NAME, START_AGENT);
        self.baseSpan.addTag(AGENT_NAME, agentName);
    }

    # Records the instance ID assigned to the started agent.
    #
    # + instanceId - The agent instance identifier
    public isolated function addInstanceId(string instanceId) {
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for sending an event to a running durable agent.
public isolated distinct class SendAgentEventSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string agentName, string instanceId, string eventName) {
        self.baseSpan = new (string `${SEND_AGENT_EVENT} ${eventName}`);
        self.baseSpan.addTag(OPERATION_NAME, SEND_AGENT_EVENT);
        self.baseSpan.addTag(AGENT_NAME, agentName);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
        self.baseSpan.addTag(EVENT_NAME, eventName);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Creates the span for starting a workflow instance.
# + return - The span
public isolated function createStartWorkflowSpan(string workflowType) returns StartWorkflowSpan {
    return new (workflowType);
}

# Creates the span for sending data to a workflow instance.
# + return - The span
public isolated function createSendDataSpan(string instanceId, string dataName) returns SendDataSpan {
    return new (instanceId, dataName);
}

# Creates the span for waiting on a workflow instance's result.
# + return - The span
public isolated function createGetWorkflowResultSpan(string instanceId) returns GetWorkflowResultSpan {
    return new (instanceId);
}

# Creates the span for a person's decision on a human task.
# + action - `complete` to submit a result, `fail` to reject the task
# + return - The span
public isolated function createHumanTaskDecisionSpan(string taskWorkflowId, "complete"|"fail" action)
        returns TaskDecisionSpan {
    return new (HUMAN_TASK, taskWorkflowId, action);
}

# Creates the span for a person's decision on a review activity.
# + action - `proceed`, `proceed-with-input` or `reject`
# + return - The span
public isolated function createReviewActivityDecisionSpan(string taskWorkflowId,
        "proceed"|"proceed-with-input"|"reject" action) returns TaskDecisionSpan {
    return new (REVIEW_ACTIVITY, taskWorkflowId, action);
}

# Creates the span for starting a durable agent instance.
# + return - The span
public isolated function createStartAgentSpan(string agentName) returns StartAgentSpan {
    return new (agentName);
}

# Creates the span for sending an event to a durable agent instance.
# + return - The span
public isolated function createSendAgentEventSpan(string agentName, string instanceId,
        string eventName) returns SendAgentEventSpan {
    return new (agentName, instanceId, eventName);
}
