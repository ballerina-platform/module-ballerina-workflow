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
import ballerina/time;
import ballerina/workflow.management;

// ================================================================================
// MANAGEMENT OPERATIONS
// ================================================================================
// The single implementation of every management operation, shared by the REST
// resources in service.bal (which map these returns to HTTP responses verbatim)
// and by `executeManagementCommand` in dispatcher.bal (which converts the same
// values into {httpStatus, body} command results). Keeping one implementation is
// what guarantees a command result is byte-identical to the corresponding REST
// response body.

// ── Definitions ───────────────────────────────────────────────────────────────

isolated function opListDefinitions() returns json|http:InternalServerError {
    management:WorkflowDefinition[]|error defs = management:listWorkflowDefinitions();
    if defs is error {
        return <http:InternalServerError>{body: errorBody("Failed to list definitions: " + defs.message())};
    }
    return {definitions: defs.toJson()};
}

// ── Workflow instances ────────────────────────────────────────────────────────

isolated function opListWorkflows(string? status, string? workflowType, string? workflowId,
        string? startedBy, int 'limit, string? pageToken, string? startTimeFrom, string? startTimeTo,
        string? closeTimeFrom, string? closeTimeTo, string? taskQueue) returns json|http:InternalServerError {
    int effectiveLimit = clampLimit('limit, maxPageSize);
    management:WorkflowInstancePage|error page = management:listWorkflowInstances(
        status, workflowType, workflowId, startedBy, effectiveLimit, pageToken,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if page is error {
        return <http:InternalServerError>{body: errorBody("Failed to list workflows: " + page.message())};
    }
    return page.toJson();
}

isolated function opStartWorkflow(map<json> body, string? userId)
        returns http:Created|http:BadRequest|http:InternalServerError {
    json? wfTypeJson = body["workflowType"];
    if wfTypeJson is () {
        return <http:BadRequest>{body: errorBody("workflowType is required")};
    }
    if wfTypeJson !is string {
        return <http:BadRequest>{body: errorBody("workflowType must be a string")};
    }
    string wfType = wfTypeJson;
    json? input = body["input"];
    string? wfId = body["workflowId"] is string ? <string>body["workflowId"] : ();
    int? timeout = body["timeoutSeconds"] is int ? <int>body["timeoutSeconds"] : ();
    management:WorkflowHandle|error wfHandle = management:startWorkflowByType(wfType, input, wfId, timeout, userId);
    if wfHandle is error {
        return <http:InternalServerError>{body: errorBody("Failed to start workflow: " + wfHandle.message())};
    }
    return <http:Created>{body: wfHandle.toJson()};
}

isolated function opGetWorkflow(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    http:Forbidden? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is http:Forbidden {
        return roleErr;
    }
    management:WorkflowExecutionInfo|error info = runId is string
        ? management:getWorkflowInfoForRun(workflowId, runId)
        : management:getWorkflowInfo(workflowId);
    if info is error {
        string msg = info.message();
        if msg.includes("not found") || msg.includes("NOT_FOUND") {
            string target = runId is string ? "Workflow run not found: " + workflowId + "/" + runId
                : "Workflow not found: " + workflowId;
            return <http:NotFound>{body: errorBody(target)};
        }
        return <http:InternalServerError>{body: errorBody(msg)};
    }
    return info.toJson();
}

isolated function opSuspendWorkflow(string workflowId, string? runId)
        returns json|http:NotFound|http:InternalServerError {
    error? result = runId is string
        ? management:suspendWorkflowRun(workflowId, runId)
        : management:suspendWorkflow(workflowId);
    if result is error {
        string msg = result.message();
        return msg.includes("not found")
            ? <http:NotFound>{body: errorBody(msg)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    return {success: true};
}

isolated function opResumeWorkflow(string workflowId, string? runId)
        returns json|http:NotFound|http:InternalServerError {
    error? result = runId is string
        ? management:resumeWorkflowRun(workflowId, runId)
        : management:resumeWorkflow(workflowId);
    if result is error {
        string msg = result.message();
        return msg.includes("not found")
            ? <http:NotFound>{body: errorBody(msg)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    return {success: true};
}

isolated function opWakeWorkflow(string workflowId) returns json|http:NotFound|http:InternalServerError {
    error? result = management:wakeAgent(workflowId);
    if result is error {
        string msg = result.message();
        return msg.includes("not found")
            ? <http:NotFound>{body: errorBody(msg)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    return {success: true};
}

isolated function opTerminateWorkflow(string workflowId, string? runId, string? reason)
        returns json|http:NotFound|http:InternalServerError {
    error? result = management:terminateWorkflow(workflowId, runId ?: "", reason);
    if result is error {
        string msg = result.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody(msg)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    return {success: true};
}

isolated function opCancelWorkflow(string workflowId, string? runId)
        returns json|http:NotFound|http:InternalServerError {
    error? result = management:cancelWorkflow(workflowId, runId ?: "");
    if result is error {
        string msg = result.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody(msg)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    return {success: true};
}

isolated function opWorkflowHistory(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    http:Forbidden? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is http:Forbidden {
        return roleErr;
    }
    management:HistoryEvent[]|error events = management:getWorkflowHistory(workflowId, runId ?: "");
    if events is error {
        string msg = events.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
            : <http:InternalServerError>{body: errorBody("Failed to get history: " + msg)};
    }
    return {events: events.toJson()};
}

isolated function opActivityTree(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    http:Forbidden? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is http:Forbidden {
        return roleErr;
    }
    management:ActivityTreeNode[]|error nodes = management:getActivityTree(workflowId, runId ?: "");
    if nodes is error {
        string msg = nodes.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
            : <http:InternalServerError>{body: errorBody("Failed to get activity tree: " + msg)};
    }
    return {nodes: nodes.toJson()};
}

isolated function opExecutionGraph(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    http:Forbidden? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is http:Forbidden {
        return roleErr;
    }
    management:ExecutionGraph|error graph = management:getExecutionGraph(workflowId, runId ?: "");
    if graph is error {
        string msg = graph.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
            : <http:InternalServerError>{body: errorBody("Failed to get execution graph: " + msg)};
    }
    return graph.toJson();
}

// ── Human tasks ───────────────────────────────────────────────────────────────

isolated function opListHumanTasks(string? status, string? parentWorkflowId, string? parentWorkflowType,
        string? taskName, string? userRole, boolean onlyMyTasks, int 'limit, string? pageToken,
        string? startTimeFrom, string? startTimeTo, string? closeTimeFrom, string? closeTimeTo,
        string? taskQueue, [string, string...]? callerRoles) returns json|http:InternalServerError {
    management:HumanTaskSummary[]|error all = management:listAllHumanTasks(status,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if all is error {
        return <http:InternalServerError>{body: errorBody("Failed to list human tasks: " + all.message())};
    }
    // Apply lambda-safe filters first
    management:HumanTaskSummary[] preFiltered = all
        .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
        .filter(t => parentWorkflowType is () || t.parentWorkflowType == parentWorkflowType)
        .filter(t => taskName is () || t.taskName == taskName)
        .filter(t => userRole is () || t.userRoles.some(r => r == userRole));
    // Apply onlyMyTasks and canComplete in a foreach (avoids lambda isolation constraint
    // on computed local variables in this Ballerina version)
    management:HumanTaskSummary[] enriched = [];
    foreach management:HumanTaskSummary t in preFiltered {
        boolean hasMatchingRole = hasRoleIntersection(t.userRoles, callerRoles);
        if !hasMatchingRole {
            continue;
        }
        if onlyMyTasks {
            // Visibility is already constrained to role-matching tasks.
        }
        t.canComplete = true;
        enriched.push(t);
    }
    return paginateHumanTasks(enriched, clampLimit('limit, maxPageSize), pageToken).toJson();
}

isolated function opPendingHumanTaskCount(string? taskQueue, [string, string...]? callerRoles)
        returns json|http:InternalServerError {
    management:HumanTaskSummary[]|error pending = management:listAllHumanTasks("PENDING", taskQueue = taskQueue);
    if pending is error {
        return <http:InternalServerError>{body: errorBody("Failed to count pending tasks: " + pending.message())};
    }
    int visibleCount = 0;
    foreach management:HumanTaskSummary t in pending {
        if hasRoleIntersection(t.userRoles, callerRoles) {
            visibleCount += 1;
        }
    }
    return {count: visibleCount};
}

isolated function opGetHumanTask(string taskId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    management:HumanTaskInfo|error info = management:getHumanTaskInfo(taskId);
    if info is error {
        string msg = info.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody("Human task not found: " + taskId)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    if !hasRoleIntersection(info.userRoles, callerRoles) {
        return <http:Forbidden>{body: errorBody("Unauthorized: caller is not allowed to access this task")};
    }
    return info.toJson();
}

isolated function opCompleteHumanTask(string taskId, json result, [string, string...]? callerRoles, string? userId)
        returns json|http:NotFound|http:Forbidden|http:Conflict|http:UnprocessableEntity|http:InternalServerError {
    if callerRoles is () {
        return <http:Forbidden>{body: errorBody("Unauthorized: x-user-roles header is required")};
    }
    error? err = management:completeHumanTask(taskId, result, callerRoles, userId);
    if err is error {
        return humanTaskErrorResponse(err);
    }
    return buildCompletionResponse(userId).toJson();
}

isolated function opFailHumanTask(string taskId, json? reason, map<json>? details,
        [string, string...]? callerRoles, string? userId)
        returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:UnprocessableEntity
                |http:InternalServerError {
    if reason is () {
        return <http:BadRequest>{body: errorBody("reason is required")};
    }
    if callerRoles is () {
        return <http:Forbidden>{body: errorBody("Unauthorized: x-user-roles header is required")};
    }
    error? err = management:failHumanTask(taskId, reason.toString(), details, callerRoles, userId);
    if err is error {
        return humanTaskErrorResponse(err);
    }
    return buildCompletionResponse(userId).toJson();
}

// ── Review activities ─────────────────────────────────────────────────────────

isolated function opListReviewActivities(string? status, string? parentWorkflowId, string? taskName,
        int 'limit, string? pageToken, string? startTimeFrom, string? startTimeTo,
        string? closeTimeFrom, string? closeTimeTo, string? taskQueue, [string, string...]? callerRoles)
        returns json|http:InternalServerError {
    management:ReviewActivitySummary[]|error all = management:listAllReviewActivities(status,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if all is error {
        return <http:InternalServerError>{
            body: errorBody("Failed to list review activities: " + all.message())};
    }
    management:ReviewActivitySummary[] preFiltered = all
        .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
        .filter(t => taskName is () || t.taskName == taskName);
    // Role visibility in a foreach (avoids the lambda isolation constraint on
    // computed local variables in this Ballerina version — see the human-task op).
    management:ReviewActivitySummary[] filtered = [];
    foreach management:ReviewActivitySummary t in preFiltered {
        if canAccessReviewActivity(t.userRoles, callerRoles) {
            filtered.push(t);
        }
    }
    return paginateReviewActivities(filtered, clampLimit('limit, maxPageSize), pageToken).toJson();
}

isolated function opGetReviewActivity(string taskId, [string, string...]? callerRoles)
        returns json|http:NotFound|http:Forbidden|http:InternalServerError {
    management:ReviewActivityInfo|error info = management:getReviewActivityInfo(taskId);
    if info is error {
        string msg = info.message();
        return msg.includes("not found") || msg.includes("NOT_FOUND")
            ? <http:NotFound>{body: errorBody("Review activity not found: " + taskId)}
            : <http:InternalServerError>{body: errorBody(msg)};
    }
    if !canAccessReviewActivity(info.userRoles, callerRoles) {
        return <http:Forbidden>{body: errorBody(
                "Unauthorized: caller is not allowed to access this review activity")};
    }
    return info.toJson();
}

isolated function opDecideReviewActivity(string taskId, string action, map<json>? input, string? feedback,
        [string, string...]? callerRoles, string? userId)
        returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
    http:Forbidden? roleErr = reviewDecisionRoleError(callerRoles);
    if roleErr is http:Forbidden {
        return roleErr;
    }
    management:ReviewDecision decision;
    if action == "proceed" {
        decision = {action: "proceed"};
    } else if action == "proceed-with-input" {
        if input !is map<json> {
            return <http:BadRequest>{body: errorBody("input must be a JSON object")};
        }
        decision = {action: "proceed-with-input", input: <map<anydata>>input};
    } else if action == "reject" {
        decision = {action: "reject", feedback: feedback};
    } else {
        return <http:BadRequest>{body: errorBody("Unknown review decision action: " + action)};
    }
    error? err = management:completeReviewActivity(taskId, decision, callerRoles, userId);
    if err is error {
        return reviewActivityErrorResponse(err);
    }
    return buildReviewDecisionResponse(action, userId).toJson();
}

// ================================================================================
// SHARED HELPERS
// ================================================================================

# Builds a `CompletionInfo` record stamped with the current UTC time and the
# caller's user ID (falls back to `"unknown"` when the header is absent).
# + userId - Optional caller identity; used as the `completedBy` field.
# + return - A `CompletionInfo` record with `success`, `completedBy`, and `completedAt` fields.
isolated function buildCompletionResponse(string? userId) returns CompletionInfo {
    time:Utc now = time:utcNow();
    return {success: true, completedBy: userId ?: "unknown", completedAt: time:utcToString(now)};
}

# Builds a `ReviewDecisionInfo` record stamped with the current UTC time and the
# caller's user ID (falls back to `"unknown"` when the header is absent).
# + decision - The review decision taken: `"proceed"`, `"proceed-with-input"`, or `"reject"`.
# + userId - Optional caller identity; used as the `decidedBy` field.
# + return - A `ReviewDecisionInfo` record with `success`, `decision`, `decidedBy`, and `decidedAt` fields.
isolated function buildReviewDecisionResponse(string decision, string? userId) returns ReviewDecisionInfo {
    time:Utc now = time:utcNow();
    return {success: true, decision: decision, decidedBy: userId ?: "unknown", decidedAt: time:utcToString(now)};
}

isolated function errorBody(string message) returns map<json> {
    return {"error": {"message": message}};
}

isolated function clampLimit(int requested, int maxAllowed) returns int {
    if requested < 1 { return 20; }
    return requested > maxAllowed ? maxAllowed : requested;
}

isolated function parseRolesHeader(string? rolesHeader) returns [string, string...]? {
    if rolesHeader is () || rolesHeader.trim().length() == 0 { return (); }
    string[] parts = re`,`.split(rolesHeader).map(r => r.trim()).filter(r => r.length() > 0);
    if parts.length() == 0 { return (); }
    return [parts[0], ...parts.slice(1)];
}

isolated function ensureWorkflowDetailAccess([string, string...]? callerRoles) returns http:Forbidden? {
    if callerRoles is () {
        return <http:Forbidden>{body: errorBody("Unauthorized: x-user-roles header is required to view workflow details")};
    }
    return ();
}

# A review activity with declared roles requires a matching caller role (same rule as human
# tasks). A review activity with no declared roles is visible to any caller by default;
# when `reviewActivityAccessRole` is configured, the caller must hold that role instead.
isolated function canAccessReviewActivity(string[] taskRoles, [string, string...]? callerRoles) returns boolean {
    if taskRoles.length() > 0 {
        return hasRoleIntersection(taskRoles, callerRoles);
    }
    string? requiredRole = reviewActivityAccessRole;
    if requiredRole is string && requiredRole.trim().length() > 0 {
        return callerRoles !is () && callerRoles.indexOf(requiredRole) != ();
    }
    return true;
}

# Guard for review activity decision routes: when `reviewActivityAccessRole` is configured,
# the caller must hold it. Task-declared roles are enforced separately by the native
# completion path against the task's memo.
isolated function reviewDecisionRoleError([string, string...]? callerRoles) returns http:Forbidden? {
    string? requiredRole = reviewActivityAccessRole;
    if requiredRole is string && requiredRole.trim().length() > 0 {
        if callerRoles is () || callerRoles.indexOf(requiredRole) is () {
            return <http:Forbidden>{body: errorBody(
                    "Unauthorized: the '" + requiredRole + "' role is required to decide review activities")};
        }
    }
    return ();
}

isolated function hasRoleIntersection(string[] taskRoles, [string, string...]? callerRoles) returns boolean {
    if callerRoles is () {
        return false;
    }
    foreach string role in taskRoles {
        if callerRoles.indexOf(role) != () {
            return true;
        }
    }
    return false;
}

isolated function paginateHumanTasks(management:HumanTaskSummary[] items, int 'limit, string? pageToken)
        returns HumanTaskPage {
    // Sort by (startTime asc, taskId asc) for a deterministic, stable order.
    management:HumanTaskSummary[] sorted = from management:HumanTaskSummary t in items
        order by t.startTime ascending, t.taskId ascending select t;
    // Seek past the cursor item so page N+1 starts after the last item on page N.
    management:HumanTaskSummary[] remaining = sorted;
    if pageToken is string {
        [string, string] cursor = decodeCursorToken(pageToken);
        string cursorTime = cursor[0];
        string cursorId = cursor[1];
        if cursorTime != "" {
            remaining = from management:HumanTaskSummary t in sorted
                where t.startTime > cursorTime
                    || (t.startTime == cursorTime && t.taskId > cursorId)
                select t;
        }
    }
    int count = remaining.length();
    boolean hasMore = count > 'limit;
    management:HumanTaskSummary[] pageItems = hasMore ? remaining.slice(0, 'limit) : remaining;
    string? nextToken = ();
    if hasMore {
        management:HumanTaskSummary last = pageItems[pageItems.length() - 1];
        nextToken = encodeCursorToken(last.startTime, last.taskId);
    }
    return {items: pageItems, nextPageToken: nextToken, hasMore: hasMore};
}

isolated function paginateReviewActivities(management:ReviewActivitySummary[] items, int 'limit, string? pageToken)
        returns ReviewActivityPage {
    management:ReviewActivitySummary[] sorted = from management:ReviewActivitySummary t in items
        order by t.startTime ascending, t.taskId ascending select t;
    management:ReviewActivitySummary[] remaining = sorted;
    if pageToken is string {
        [string, string] cursor = decodeCursorToken(pageToken);
        string cursorTime = cursor[0];
        string cursorId = cursor[1];
        if cursorTime != "" {
            remaining = from management:ReviewActivitySummary t in sorted
                where t.startTime > cursorTime
                    || (t.startTime == cursorTime && t.taskId > cursorId)
                select t;
        }
    }
    int count = remaining.length();
    boolean hasMore = count > 'limit;
    management:ReviewActivitySummary[] pageItems = hasMore ? remaining.slice(0, 'limit) : remaining;
    string? nextToken = ();
    if hasMore {
        management:ReviewActivitySummary last = pageItems[pageItems.length() - 1];
        nextToken = encodeCursorToken(last.startTime, last.taskId);
    }
    return {items: pageItems, nextPageToken: nextToken, hasMore: hasMore};
}

// Cursor format: "<ISO-8601 startTime>~<taskId>" — split on the FIRST "~" so a taskId
// that contains "~" (e.g., when a parent workflow ID has one) is still decoded correctly.
isolated function encodeCursorToken(string startTime, string taskId) returns string =>
    startTime + "~" + taskId;

isolated function decodeCursorToken(string token) returns [string, string] {
    int? sep = token.indexOf("~");
    if sep is int {
        return [token.substring(0, sep), token.substring(sep + 1)];
    }
    return ["", ""];
}

isolated function humanTaskErrorResponse(error err)
        returns http:NotFound|http:Forbidden|http:Conflict|http:UnprocessableEntity|http:InternalServerError {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return <http:NotFound>{body: errorBody(msg)};
    }
    if msg.includes("Unauthorized") || msg.includes("not authorized") {
        return <http:Forbidden>{body: errorBody(msg)};
    }
    if msg.includes("not running") || msg.includes("already completed") {
        return <http:Conflict>{body: errorBody(msg)};
    }
    // A well-formed request whose payload does not match the task's expected type is semantically
    // invalid → 422 Unprocessable Entity (ballerina-library#8866).
    if msg.includes("Invalid payload") {
        return <http:UnprocessableEntity>{body: errorBody(msg)};
    }
    return <http:InternalServerError>{body: errorBody(msg)};
}

isolated function reviewActivityErrorResponse(error err)
        returns http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return <http:NotFound>{body: errorBody(msg)};
    }
    if msg.includes("Unauthorized") || msg.includes("not authorized") {
        return <http:Forbidden>{body: errorBody(msg)};
    }
    if msg.includes("not running") || msg.includes("already completed") {
        return <http:Conflict>{body: errorBody(msg)};
    }
    return <http:InternalServerError>{body: errorBody(msg)};
}
