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

import ballerina/http;

// ================================================================================
// MANAGEMENT COMMAND DISPATCHER
// ================================================================================
// Executes management operations delivered as commands — e.g. tunneled from a
// control plane through the ICP runtime bridge — WITHOUT going through HTTP.
// Every operation runs the exact same code path the workflow.management.rest
// resources delegate to (the shared op functions in operations.bal), so a command
// result's body is byte-identical to the corresponding REST response body, and
// `httpStatus` matches the status code the REST API would have returned.

# Identity of the caller a management command executes on behalf of. Carries the
# same values the REST API reads from the `x-user-id` and `x-user-roles` headers,
# so role-visibility filtering, task-completion authorization, and audit fields
# (`completedBy`, `decidedBy`, `startedBy`) behave identically.
#
# + userId - The caller's user ID, or `()` when unknown
# + roles - The caller's roles; an empty array means "no roles", matching an
#           absent `x-user-roles` header
public type CommandIdentity record {|
    string? userId = ();
    string[] roles = [];
|};

# A management operation to execute.
#
# + operation - Dot-qualified operation name, e.g. `"humanTasks.complete"` (see
#               `executeManagementCommand` for the full vocabulary)
# + params - Operation parameters; keys match the corresponding REST route's query,
#            path, and body parameter names
# + identity - The caller's identity
public type ManagementCommand record {|
    string operation;
    map<json> params = {};
    CommandIdentity identity = {};
|};

# The outcome of a management command.
#
# + httpStatus - The status code the corresponding REST endpoint would have returned
# + body - The response body, byte-identical to the REST response body
public type ManagementCommandResult record {|
    int httpStatus;
    json body;
|};

// The typed responses the shared op functions produce. Each carries the HTTP status
// object (with its numeric `code`) and the response body, which is how
// `toCommandResult` converts an op return into a command result without re-encoding.
type TypedResponse http:Created|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict
        |http:UnprocessableEntity|http:InternalServerError;

# Executes a management operation and returns the REST-equivalent result.
#
# Operations: `definitions.list`, `instances.list`, `instances.start`, `instances.get`,
# `instances.suspend`, `instances.resume`, `instances.wake`, `instances.terminate`, `instances.cancel`,
# `instances.history`, `instances.activityTree`, `instances.executionGraph`,
# `humanTasks.list`, `humanTasks.pendingCount`, `humanTasks.get`, `humanTasks.complete`,
# `humanTasks.fail`, `reviewActivities.list`, `reviewActivities.get`,
# `reviewActivities.decide`.
#
# ```ballerina
# rest:ManagementCommandResult result = rest:executeManagementCommand({
#     operation: "humanTasks.complete",
#     params: {taskId: "humantask-...", result: {approved: true}},
#     identity: {userId: "alice", roles: ["approver"]}
# });
# ```
#
# + command - The command to execute
# + return - The REST-equivalent status code and body; unknown operations and missing
#            required parameters return a `400` result
public isolated function executeManagementCommand(ManagementCommand command) returns ManagementCommandResult {
    map<json> params = command.params;
    [string, string...]? callerRoles = rolesFromIdentity(command.identity);
    string? userId = command.identity.userId;

    match command.operation {
        "definitions.list" => {
            return toCommandResult(opListDefinitions());
        }
        "instances.list" => {
            return toCommandResult(opListWorkflows(strParam(params, "status"),
                    strParam(params, "workflowType"), strParam(params, "workflowId"),
                    strParam(params, "startedBy"), intParam(params, "limit", 20),
                    strParam(params, "pageToken"), strParam(params, "startTimeFrom"),
                    strParam(params, "startTimeTo"), strParam(params, "closeTimeFrom"),
                    strParam(params, "closeTimeTo"), strParam(params, "taskQueue")));
        }
        "instances.start" => {
            // The params map carries the same keys as the REST body
            // (workflowType, input?, workflowId?, timeoutSeconds?).
            return toCommandResult(opStartWorkflow(params, userId));
        }
        "instances.get" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opGetWorkflow(workflowId, strParam(params, "runId"), callerRoles));
        }
        "instances.suspend" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opSuspendWorkflow(workflowId, strParam(params, "runId")));
        }
        "instances.resume" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opResumeWorkflow(workflowId, strParam(params, "runId")));
        }
        "instances.wake" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opWakeWorkflow(workflowId));
        }
        "instances.terminate" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opTerminateWorkflow(workflowId, strParam(params, "runId"),
                    strParam(params, "reason")));
        }
        "instances.cancel" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opCancelWorkflow(workflowId, strParam(params, "runId")));
        }
        "instances.history" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opWorkflowHistory(workflowId, strParam(params, "runId"), callerRoles));
        }
        "instances.activityTree" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opActivityTree(workflowId, strParam(params, "runId"), callerRoles));
        }
        "instances.executionGraph" => {
            string workflowId = strParam(params, "workflowId") ?: "";
            if workflowId == "" {
                return missingParam("workflowId");
            }
            return toCommandResult(opExecutionGraph(workflowId, strParam(params, "runId"), callerRoles));
        }
        "humanTasks.list" => {
            return toCommandResult(opListHumanTasks(strParam(params, "status"),
                    strParam(params, "parentWorkflowId"), strParam(params, "parentWorkflowType"),
                    strParam(params, "taskName"), strParam(params, "userRole"),
                    boolParam(params, "onlyMyTasks"), intParam(params, "limit", 20),
                    strParam(params, "pageToken"), strParam(params, "startTimeFrom"),
                    strParam(params, "startTimeTo"), strParam(params, "closeTimeFrom"),
                    strParam(params, "closeTimeTo"), strParam(params, "taskQueue"), callerRoles));
        }
        "humanTasks.pendingCount" => {
            return toCommandResult(opPendingHumanTaskCount(strParam(params, "taskQueue"), callerRoles));
        }
        "humanTasks.get" => {
            string taskId = strParam(params, "taskId") ?: "";
            if taskId == "" {
                return missingParam("taskId");
            }
            return toCommandResult(opGetHumanTask(taskId, callerRoles));
        }
        "humanTasks.complete" => {
            string taskId = strParam(params, "taskId") ?: "";
            if taskId == "" {
                return missingParam("taskId");
            }
            return toCommandResult(opCompleteHumanTask(taskId, params["result"], callerRoles, userId));
        }
        "humanTasks.fail" => {
            string taskId = strParam(params, "taskId") ?: "";
            if taskId == "" {
                return missingParam("taskId");
            }
            map<json>? details = params["details"] is map<json> ? <map<json>>params["details"] : ();
            return toCommandResult(opFailHumanTask(taskId, params["reason"], details, callerRoles, userId));
        }
        "reviewActivities.list" => {
            return toCommandResult(opListReviewActivities(strParam(params, "status"),
                    strParam(params, "parentWorkflowId"), strParam(params, "taskName"),
                    intParam(params, "limit", 20), strParam(params, "pageToken"),
                    strParam(params, "startTimeFrom"), strParam(params, "startTimeTo"),
                    strParam(params, "closeTimeFrom"), strParam(params, "closeTimeTo"),
                    strParam(params, "taskQueue"), callerRoles));
        }
        "reviewActivities.get" => {
            string taskId = strParam(params, "taskId") ?: "";
            if taskId == "" {
                return missingParam("taskId");
            }
            return toCommandResult(opGetReviewActivity(taskId, callerRoles));
        }
        "reviewActivities.decide" => {
            string taskId = strParam(params, "taskId") ?: "";
            if taskId == "" {
                return missingParam("taskId");
            }
            string action = strParam(params, "action") ?: "";
            if action == "" {
                return missingParam("action");
            }
            map<json>? input = params["input"] is map<json> ? <map<json>>params["input"] : ();
            return toCommandResult(opDecideReviewActivity(taskId, action, input,
                    strParam(params, "feedback"), callerRoles, userId));
        }
        _ => {
            return {httpStatus: 400, body: errorBody("Unknown operation: " + command.operation)};
        }
    }
}

// Converts an op function's return — a success json or a typed HTTP response — into
// the command result envelope, reading the status code and body off the typed record.
isolated function toCommandResult(json|TypedResponse response) returns ManagementCommandResult {
    // A typed response record can never be a json value (its status field is an object),
    // so these checks are unambiguous. Branch per type because the compiler does not
    // narrow the union to TypedResponse for common field access.
    if response is http:Created {
        return typedResult(http:STATUS_CREATED, response?.body);
    }
    if response is http:BadRequest {
        return typedResult(http:STATUS_BAD_REQUEST, response?.body);
    }
    if response is http:NotFound {
        return typedResult(http:STATUS_NOT_FOUND, response?.body);
    }
    if response is http:Forbidden {
        return typedResult(http:STATUS_FORBIDDEN, response?.body);
    }
    if response is http:Conflict {
        return typedResult(http:STATUS_CONFLICT, response?.body);
    }
    if response is http:UnprocessableEntity {
        return typedResult(http:STATUS_UNPROCESSABLE_ENTITY, response?.body);
    }
    if response is http:InternalServerError {
        return typedResult(http:STATUS_INTERNAL_SERVER_ERROR, response?.body);
    }
    // Every typed response is handled above, so the remaining value is the success json;
    // the cast is needed because the compiler does not narrow the union here.
    return {httpStatus: 200, body: <json>response};
}

isolated function typedResult(int httpStatus, anydata body) returns ManagementCommandResult {
    anydata responseBody = body ?: ();
    return {httpStatus: httpStatus, body: responseBody.toJson()};
}

isolated function missingParam(string name) returns ManagementCommandResult {
    return {httpStatus: 400, body: errorBody(name + " is required")};
}



isolated function rolesFromIdentity(CommandIdentity identity) returns [string, string...]? {
    string[] roles = identity.roles;
    if roles.length() == 0 {
        return ();
    }
    return [roles[0], ...roles.slice(1)];
}

isolated function strParam(map<json> params, string name) returns string? {
    json value = params[name];
    return value is string ? value : ();
}

isolated function intParam(map<json> params, string name, int defaultValue) returns int {
    json value = params[name];
    return value is int ? value : defaultValue;
}

isolated function boolParam(map<json> params, string name) returns boolean {
    json value = params[name];
    return value is boolean ? value : false;
}
