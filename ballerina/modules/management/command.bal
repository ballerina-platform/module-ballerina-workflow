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
// MANAGEMENT COMMANDS
// ================================================================================
// A data-shaped way to invoke the management operations: a consumer that carries
// operations across a boundary (a control plane delivering them over its own
// channel, a message queue, a test harness) names one with the `Operation` enum
// and passes its parameters as a map, instead of binding to one function per
// operation. `executeCommand` dispatches to the same operation implementations
// the HTTP API in `workflow.management.rest` uses, so both paths produce
// identical payloads and identical errors.
//
// This layer knows nothing about any transport: it returns the operation's `json`
// payload or a `management:Error`. Mapping those to status codes, envelopes, or
// wire formats belongs to the adapter that owns the protocol.

# Names a management operation. The string value of each member is the operation's
# stable wire name, so a consumer that receives an operation as text converts it
# with `cloneWithType`/`ensureType` and gets a compile-time-checked value.
public enum Operation {
    # List the workflow definitions registered with this worker.
    LIST_DEFINITIONS = "definitions.list",
    # List workflow instances.
    LIST_INSTANCES = "instances.list",
    # Start a new workflow instance. This is the only operation that creates one.
    START_INSTANCE = "instances.start",
    # Get one workflow instance.
    GET_INSTANCE = "instances.get",
    # Suspend a running instance.
    SUSPEND_INSTANCE = "instances.suspend",
    # Resume a suspended instance.
    RESUME_INSTANCE = "instances.resume",
    # Wake a sleeping durable agent instance.
    WAKE_INSTANCE = "instances.wake",
    # Terminate an instance.
    TERMINATE_INSTANCE = "instances.terminate",
    # Cancel an instance.
    CANCEL_INSTANCE = "instances.cancel",
    # Get an instance's event history.
    GET_INSTANCE_HISTORY = "instances.history",
    # Get an instance's activity tree.
    GET_INSTANCE_ACTIVITY_TREE = "instances.activityTree",
    # Get an instance's execution graph.
    GET_INSTANCE_EXECUTION_GRAPH = "instances.executionGraph",
    # List human tasks visible to the caller.
    LIST_HUMAN_TASKS = "humanTasks.list",
    # List the caller's unified work queue: human tasks and review activities together.
    LIST_WORK_ITEMS = "workItems.list",
    # Count the caller's pending human tasks.
    COUNT_PENDING_HUMAN_TASKS = "humanTasks.pendingCount",
    # Get one human task.
    GET_HUMAN_TASK = "humanTasks.get",
    # Complete a human task.
    COMPLETE_HUMAN_TASK = "humanTasks.complete",
    # Fail a human task.
    FAIL_HUMAN_TASK = "humanTasks.fail",
    # List review activities visible to the caller.
    LIST_REVIEW_ACTIVITIES = "reviewActivities.list",
    # Get one review activity.
    GET_REVIEW_ACTIVITY = "reviewActivities.get",
    # Decide a review activity.
    DECIDE_REVIEW_ACTIVITY = "reviewActivities.decide"
}

# Identity of the caller an operation runs on behalf of. Drives role-based
# visibility, task-completion authorization, and the audit fields recorded on
# completions and decisions (`completedBy`, `decidedBy`, `startedBy`).
#
# + userId - The caller's user ID, or `()` when unknown
# + roles - The caller's roles; an empty array means the caller holds none
public type Identity record {|
    string? userId = ();
    string[] roles = [];
|};

# A management operation to execute, named by `Operation` and parameterized by a
# map. Parameter names match the operation's own vocabulary and are documented
# with `executeCommand`.
#
# + operation - The operation to run
# + params - The operation's parameters
# + identity - The caller's identity
public type Command record {|
    Operation operation;
    map<json> params = {};
    Identity identity = {};
|};

# Executes a management operation.
#
# Parameters per operation, all optional unless stated:
# - `LIST_DEFINITIONS` — none.
# - `LIST_INSTANCES` — `status`, `workflowType`, `workflowId`, `startedBy`, `limit`,
#   `pageToken`, `startTimeFrom`, `startTimeTo`, `closeTimeFrom`, `closeTimeTo`, `taskQueue`.
# - `START_INSTANCE` — `workflowType` (required), `input`, `workflowId`, `timeoutSeconds`.
# - `GET_INSTANCE`, `SUSPEND_INSTANCE`, `RESUME_INSTANCE`, `CANCEL_INSTANCE`,
#   `GET_INSTANCE_HISTORY`, `GET_INSTANCE_ACTIVITY_TREE`, `GET_INSTANCE_EXECUTION_GRAPH` —
#   `workflowId` (required), `runId`.
# - `WAKE_INSTANCE` — `workflowId` (required).
# - `TERMINATE_INSTANCE` — `workflowId` (required), `runId`, `reason`.
# - `LIST_HUMAN_TASKS` — `status`, `parentWorkflowId`, `parentWorkflowType`, `taskName`,
#   `userRole`, `limit`, `pageToken`, the four time bounds, `taskQueue`.
# - `LIST_WORK_ITEMS` — `kinds` (comma list of `HUMAN_TASK`/`REVIEW_ACTIVITY`; both when absent),
#   `status`, `parentWorkflowId`, `parentWorkflowType`, `limit`, `pageToken`, the four time
#   bounds, `taskQueue`.
# - `COUNT_PENDING_HUMAN_TASKS` — `taskQueue`.
# - `GET_HUMAN_TASK` — `taskId` (required).
# - `COMPLETE_HUMAN_TASK` — `taskId` (required), `result`.
# - `FAIL_HUMAN_TASK` — `taskId` and `reason` (required), `details`.
# - `LIST_REVIEW_ACTIVITIES` — `status`, `parentWorkflowId`, `taskName`, `limit`,
#   `pageToken`, the four time bounds, `taskQueue`.
# - `GET_REVIEW_ACTIVITY` — `taskId` (required).
# - `DECIDE_REVIEW_ACTIVITY` — `taskId` and `action` (required), `input`, `feedback`.
#
# ```ballerina
# json|management:Error result = management:executeCommand({
#     operation: management:COMPLETE_HUMAN_TASK,
#     params: {taskId: "humantask-...", result: {approved: true}},
#     identity: {userId: "alice", roles: ["approver"]}
# });
# ```
#
# This function authenticates nothing. It trusts `command.identity` as given and
# applies only the role checks the operations themselves perform — the same checks
# the HTTP API relies on once it has resolved a caller. A consumer that accepts
# commands from a remote channel is responsible for authenticating that channel and
# for populating `identity` from a verified credential; scope policies configured
# for the HTTP API (`enforceScopes` and friends) belong to that module and have no
# effect here.
#
# + command - The command to execute
# + return - The operation's payload, or the error explaining why it could not run
public isolated function executeCommand(Command command) returns json|Error {
    map<json>|Error normalized = normalizeParams(command.params);
    if normalized is Error {
        return normalized;
    }
    map<json> params = normalized;
    [string, string...]? callerRoles = rolesFromIdentity(command.identity);
    string? userId = command.identity.userId;

    match command.operation {
        LIST_DEFINITIONS => {
            return opListDefinitions();
        }
        LIST_INSTANCES => {
            return opListWorkflows(strParam(params, "status"),
                    strParam(params, "workflowType"), strParam(params, "workflowId"),
                    strParam(params, "startedBy"), intParam(params, "limit", 20),
                    strParam(params, "pageToken"), strParam(params, "startTimeFrom"),
                    strParam(params, "startTimeTo"), strParam(params, "closeTimeFrom"),
                    strParam(params, "closeTimeTo"), strParam(params, "taskQueue"),
                    strParam(params, "kind"));
        }
        START_INSTANCE => {
            // The params map carries the start request itself
            // (workflowType, input?, workflowId?, timeoutSeconds?).
            return opStartWorkflow(params, userId);
        }
        GET_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opGetWorkflow(workflowId, strParam(params, "runId"), callerRoles);
        }
        SUSPEND_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opSuspendWorkflow(workflowId, strParam(params, "runId"));
        }
        RESUME_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opResumeWorkflow(workflowId, strParam(params, "runId"));
        }
        WAKE_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opWakeWorkflow(workflowId);
        }
        TERMINATE_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opTerminateWorkflow(workflowId, strParam(params, "runId"),
                    strParam(params, "reason"));
        }
        CANCEL_INSTANCE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opCancelWorkflow(workflowId, strParam(params, "runId"));
        }
        GET_INSTANCE_HISTORY => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opWorkflowHistory(workflowId, strParam(params, "runId"), callerRoles);
        }
        GET_INSTANCE_ACTIVITY_TREE => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opActivityTree(workflowId, strParam(params, "runId"), callerRoles);
        }
        GET_INSTANCE_EXECUTION_GRAPH => {
            string|Error workflowId = requiredParam(params, "workflowId");
            if workflowId is Error {
                return workflowId;
            }
            return opExecutionGraph(workflowId, strParam(params, "runId"), callerRoles);
        }
        LIST_HUMAN_TASKS => {
            return opListHumanTasks(strParam(params, "status"),
                    strParam(params, "parentWorkflowId"), strParam(params, "parentWorkflowType"),
                    strParam(params, "taskName"), strParam(params, "userRole"),
                    intParam(params, "limit", 20),
                    strParam(params, "pageToken"), strParam(params, "startTimeFrom"),
                    strParam(params, "startTimeTo"), strParam(params, "closeTimeFrom"),
                    strParam(params, "closeTimeTo"), strParam(params, "taskQueue"), callerRoles);
        }
        LIST_WORK_ITEMS => {
            return opListWorkItems(strParam(params, "kinds"), strParam(params, "status"),
                    strParam(params, "parentWorkflowId"), strParam(params, "parentWorkflowType"),
                    intParam(params, "limit", 20),
                    strParam(params, "pageToken"), strParam(params, "startTimeFrom"),
                    strParam(params, "startTimeTo"), strParam(params, "closeTimeFrom"),
                    strParam(params, "closeTimeTo"), strParam(params, "taskQueue"), callerRoles);
        }
        COUNT_PENDING_HUMAN_TASKS => {
            return opPendingHumanTaskCount(strParam(params, "taskQueue"), callerRoles);
        }
        GET_HUMAN_TASK => {
            string|Error taskId = requiredParam(params, "taskId");
            if taskId is Error {
                return taskId;
            }
            return opGetHumanTask(taskId, callerRoles);
        }
        COMPLETE_HUMAN_TASK => {
            string|Error taskId = requiredParam(params, "taskId");
            if taskId is Error {
                return taskId;
            }
            return opCompleteHumanTask(taskId, params["result"], callerRoles, userId);
        }
        FAIL_HUMAN_TASK => {
            string|Error taskId = requiredParam(params, "taskId");
            if taskId is Error {
                return taskId;
            }
            map<json>? details = params["details"] is map<json> ? <map<json>>params["details"] : ();
            return opFailHumanTask(taskId, params["reason"], details, callerRoles, userId);
        }
        LIST_REVIEW_ACTIVITIES => {
            return opListReviewActivities(strParam(params, "status"),
                    strParam(params, "parentWorkflowId"), strParam(params, "taskName"),
                    intParam(params, "limit", 20), strParam(params, "pageToken"),
                    strParam(params, "startTimeFrom"), strParam(params, "startTimeTo"),
                    strParam(params, "closeTimeFrom"), strParam(params, "closeTimeTo"),
                    strParam(params, "taskQueue"), callerRoles);
        }
        GET_REVIEW_ACTIVITY => {
            string|Error taskId = requiredParam(params, "taskId");
            if taskId is Error {
                return taskId;
            }
            return opGetReviewActivity(taskId, callerRoles);
        }
        DECIDE_REVIEW_ACTIVITY => {
            string|Error taskId = requiredParam(params, "taskId");
            if taskId is Error {
                return taskId;
            }
            string|Error action = requiredParam(params, "action");
            if action is Error {
                return action;
            }
            map<json>? input = params["input"] is map<json> ? <map<json>>params["input"] : ();
            return opDecideReviewActivity(taskId, action, input,
                    strParam(params, "feedback"), callerRoles, userId);
        }
        _ => {
            // Unreachable for a well-typed Command: the operation field is the enum.
            // Kept so the dispatch stays total if a member is added without a branch.
            return invalidRequest("Unsupported operation: " + command.operation);
        }
    }
}

// ── Parameter access ──────────────────────────────────────────────────────────

// The type each parameter carries. A command may arrive from a channel that
// encodes every scalar as text (a query string relayed verbatim, a queue message),
// so numeric parameters accept their string form and are coerced here; a value
// that cannot be coerced is reported rather than silently replaced by a default.
// `result` is the value a human submitted and may be any json, so it is absent from
// this table, as is any name an operation does not read. `details` is documented as a
// JSON object and `opFailHumanTask` binds it as one: a scalar or array would otherwise
// be silently dropped and the operation would report success having lost what the
// caller sent.
//
// This table is keyed by parameter name, so a name may only appear here when every
// operation using it agrees on the type. `input` does not qualify: a review decision's
// `input` is an object, but a workflow's input is whatever the workflow's parameter
// accepts — a string, a number, an array — and the runtime binds it positionally.
// Requiring an object here would make every workflow with a non-record input
// unstartable. The decision path keeps its own check, in `opDecideReviewActivity`.
final readonly & map<string> PARAM_TYPES = {
    "action": "string",
    "details": "object",
    "closeTimeFrom": "string",
    "closeTimeTo": "string",
    "feedback": "string",
    "limit": "int",
    "pageToken": "string",
    "parentWorkflowId": "string",
    "parentWorkflowType": "string",
    "reason": "string",
    "runId": "string",
    "startTimeFrom": "string",
    "startTimeTo": "string",
    "startedBy": "string",
    "status": "string",
    "taskId": "string",
    "taskName": "string",
    "taskQueue": "string",
    "timeoutSeconds": "int",
    "userRole": "string",
    "workflowId": "string",
    "workflowType": "string"
};

# Checks every parameter against the type its operation expects, coercing the
# string form of a number and reporting anything that cannot be coerced.
#
# + params - The command's parameters
# + return - The parameters with numbers normalized, or the first type error found
isolated function normalizeParams(map<json> params) returns map<json>|Error {
    map<json> normalized = {};
    foreach [string, json] [name, value] in params.entries() {
        string? expected = PARAM_TYPES[name];
        if expected is () || value is () {
            normalized[name] = value;
            continue;
        }
        if expected == "int" {
            if value is int {
                normalized[name] = value;
                continue;
            }
            if value is string {
                int|error parsed = int:fromString(value);
                if parsed is int {
                    normalized[name] = parsed;
                    continue;
                }
            }
            return invalidRequest(name + " must be an integer");
        }
        if expected == "object" {
            if value is map<json> {
                normalized[name] = value;
                continue;
            }
            return invalidRequest(name + " must be a JSON object");
        }
        if value !is string {
            return invalidRequest(name + " must be a string");
        }
        normalized[name] = value;
    }
    return normalized;
}

isolated function requiredParam(map<json> params, string name) returns string|Error {
    // Wrong-typed values are already reported by normalizeParams, so reaching here
    // with a non-string value is impossible; absence is the only failure left.
    string? value = strParam(params, name);
    if value is () || value == "" {
        return invalidRequest(name + " is required");
    }
    return value;
}

isolated function rolesFromIdentity(Identity identity) returns [string, string...]? {
    string[] roles = identity.roles;
    if roles.length() == 0 {
        return ();
    }
    return [roles[0], ...roles.slice(1)];
}

// Both accessors read a normalized map: a value of the wrong type has already been
// reported, so a missing value is the only case left and the default applies.

isolated function strParam(map<json> params, string name) returns string? {
    json value = params[name];
    return value is string ? value : ();
}

isolated function intParam(map<json> params, string name, int defaultValue) returns int {
    json value = params[name];
    return value is int ? value : defaultValue;
}
