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

import ballerina/time;

// ================================================================================
// MANAGEMENT OPERATIONS
// ================================================================================
// The single implementation of every management operation. Each returns the
// operation's `json` payload, or an `Error` saying why it could not be carried
// out — never a transport-specific value. `executeCommand` in command.bal
// dispatches to these, and transport adapters (the HTTP API in
// `workflow.management.rest`, and any other consumer) map the same values into
// their own vocabulary, so every caller observes identical payloads.

# Maximum number of items returned per page in list operations.
configurable int maxPageSize = 100;

# Maximum number of review activities one bulk decision may address. A selector that
# resolves to more is rejected rather than truncated, so a caller is never told a
# decision was applied to a set larger than the one it was applied to.
configurable int maxBulkRetrySize = 100;

# Optional role required to view or decide review activities that declare no roles of
# their own. A failure review declares the roles its `retryPolicy` names — a role
# string, or a list of them — and declares none only when the policy is the legacy
# `"MANUAL_RETRY"` sentinel, which opts out of role restriction. By default (`()`),
# those unrestricted reviews are visible to any caller; set a role name to restrict
# them to callers holding that role. Review activities that do declare roles always
# require a matching caller role, regardless of this setting.
configurable string? reviewActivityAccessRole = ();

// ── Definitions ───────────────────────────────────────────────────────────────

isolated function opListDefinitions() returns json|Error {
    WorkflowDefinition[]|error defs = listWorkflowDefinitions();
    if defs is error {
        return executionFailed("Failed to list definitions: " + defs.message());
    }
    return {definitions: defs.toJson()};
}

// ── Workflow instances ────────────────────────────────────────────────────────

isolated function opListWorkflows(string? status, string? workflowType, string? workflowId,
        string? startedBy, int 'limit, string? pageToken, string? startTimeFrom, string? startTimeTo,
        string? closeTimeFrom, string? closeTimeTo, string? taskQueue) returns json|Error {
    int effectiveLimit = clampLimit('limit, maxPageSize);
    WorkflowInstancePage|error page = listWorkflowInstances(
        status, workflowType, workflowId, startedBy, effectiveLimit, pageToken,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if page is error {
        return executionFailed("Failed to list workflows: " + page.message());
    }
    return page.toJson();
}

isolated function opStartWorkflow(map<json> body, string? userId)
        returns json|Error {
    json? wfTypeJson = body["workflowType"];
    if wfTypeJson is () {
        return invalidRequest("workflowType is required");
    }
    if wfTypeJson !is string {
        return invalidRequest("workflowType must be a string");
    }
    string wfType = wfTypeJson;
    json? input = body["input"];
    string? wfId = body["workflowId"] is string ? <string>body["workflowId"] : ();
    int? timeout = body["timeoutSeconds"] is int ? <int>body["timeoutSeconds"] : ();
    WorkflowHandle|error wfHandle = startWorkflowByType(wfType, input, wfId, timeout, userId);
    if wfHandle is error {
        return executionFailed("Failed to start workflow: " + wfHandle.message());
    }
    return wfHandle.toJson();
}

isolated function opGetWorkflow(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|Error {
    AccessDeniedError? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    WorkflowExecutionInfo|error info = runId is string
        ? getWorkflowInfoForRun(workflowId, runId)
        : getWorkflowInfo(workflowId);
    if info is error {
        string target = runId is string ? "Workflow run not found: " + workflowId + "/" + runId
            : "Workflow not found: " + workflowId;
        return notFoundOrExecutionError(info, target);
    }
    return info.toJson();
}

isolated function opSuspendWorkflow(string workflowId, string? runId)
        returns json|Error {
    error? result = runId is string
        ? suspendWorkflowRun(workflowId, runId)
        : suspendWorkflow(workflowId);
    if result is error {
        return notFoundOrExecutionError(result);
    }
    return {success: true};
}

isolated function opResumeWorkflow(string workflowId, string? runId)
        returns json|Error {
    error? result = runId is string
        ? resumeWorkflowRun(workflowId, runId)
        : resumeWorkflow(workflowId);
    if result is error {
        return notFoundOrExecutionError(result);
    }
    return {success: true};
}

isolated function opWakeWorkflow(string workflowId) returns json|Error {
    error? result = wakeAgent(workflowId);
    if result is error {
        return notFoundOrExecutionError(result);
    }
    return {success: true};
}

isolated function opTerminateWorkflow(string workflowId, string? runId, string? reason)
        returns json|Error {
    error? result = terminateWorkflow(workflowId, runId ?: "", reason);
    if result is error {
        return notFoundOrExecutionError(result);
    }
    return {success: true};
}

isolated function opCancelWorkflow(string workflowId, string? runId)
        returns json|Error {
    error? result = cancelWorkflow(workflowId, runId ?: "");
    if result is error {
        return notFoundOrExecutionError(result);
    }
    return {success: true};
}

isolated function opWorkflowHistory(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|Error {
    AccessDeniedError? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    HistoryEvent[]|error events = getWorkflowHistory(workflowId, runId ?: "");
    if events is error {
        return notFoundOrExecutionError(events, "Workflow not found: " + workflowId,
                "Failed to get history: ");
    }
    return {events: events.toJson()};
}

isolated function opActivityTree(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|Error {
    AccessDeniedError? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    ActivityTreeNode[]|error nodes = getActivityTree(workflowId, runId ?: "");
    if nodes is error {
        return notFoundOrExecutionError(nodes, "Workflow not found: " + workflowId,
                "Failed to get activity tree: ");
    }
    return {nodes: nodes.toJson()};
}

isolated function opExecutionGraph(string workflowId, string? runId, [string, string...]? callerRoles)
        returns json|Error {
    AccessDeniedError? roleErr = ensureWorkflowDetailAccess(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    ExecutionGraph|error graph = getExecutionGraph(workflowId, runId ?: "");
    if graph is error {
        return notFoundOrExecutionError(graph, "Workflow not found: " + workflowId,
                "Failed to get execution graph: ");
    }
    return graph.toJson();
}

// ── Human tasks ───────────────────────────────────────────────────────────────

isolated function opListHumanTasks(string? status, string? parentWorkflowId, string? parentWorkflowType,
        string? taskName, string? userRole, int 'limit, string? pageToken,
        string? startTimeFrom, string? startTimeTo, string? closeTimeFrom, string? closeTimeTo,
        string? taskQueue, [string, string...]? callerRoles) returns json|Error {
    HumanTaskSummary[]|error all = listAllHumanTasks(status,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if all is error {
        return executionFailed("Failed to list human tasks: " + all.message());
    }
    // Apply lambda-safe filters first
    HumanTaskSummary[] preFiltered = all
        .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
        .filter(t => parentWorkflowType is () || t.parentWorkflowType == parentWorkflowType)
        .filter(t => taskName is () || t.taskName == taskName)
        .filter(t => userRole is () || t.userRoles.some(r => r == userRole));
    // Role visibility in a foreach (avoids the lambda isolation constraint on
    // computed local variables in this Ballerina version). A caller only ever sees
    // tasks their roles match, so every listed task is one they can complete.
    HumanTaskSummary[] enriched = [];
    foreach HumanTaskSummary t in preFiltered {
        if !hasRoleIntersection(t.userRoles, callerRoles) {
            continue;
        }
        t.canComplete = true;
        enriched.push(t);
    }
    return paginateHumanTasks(enriched, clampLimit('limit, maxPageSize), pageToken).toJson();
}

isolated function opPendingHumanTaskCount(string? taskQueue, [string, string...]? callerRoles)
        returns json|Error {
    HumanTaskSummary[]|error pending = listAllHumanTasks("PENDING", taskQueue = taskQueue);
    if pending is error {
        return executionFailed("Failed to count pending tasks: " + pending.message());
    }
    int visibleCount = 0;
    foreach HumanTaskSummary t in pending {
        if hasRoleIntersection(t.userRoles, callerRoles) {
            visibleCount += 1;
        }
    }
    return {count: visibleCount};
}

isolated function opGetHumanTask(string taskId, [string, string...]? callerRoles)
        returns json|Error {
    HumanTaskInfo|error info = getHumanTaskInfo(taskId);
    if info is error {
        return notFoundOrExecutionError(info, "Human task not found: " + taskId);
    }
    if !hasRoleIntersection(info.userRoles, callerRoles) {
        return accessDenied("Unauthorized: caller is not allowed to access this task");
    }
    return info.toJson();
}

isolated function opCompleteHumanTask(string taskId, json result, [string, string...]? callerRoles, string? userId)
        returns json|Error {
    if callerRoles is () {
        return accessDenied("Unauthorized: caller roles are required");
    }
    error? err = completeHumanTask(taskId, result, callerRoles, userId);
    if err is error {
        return classifyRuntimeError(err);
    }
    return buildCompletionResponse(userId).toJson();
}

isolated function opFailHumanTask(string taskId, json? reason, map<json>? details,
        [string, string...]? callerRoles, string? userId)
        returns json|Error {
    if reason is () {
        return invalidRequest("reason is required");
    }
    if callerRoles is () {
        return accessDenied("Unauthorized: caller roles are required");
    }
    error? err = failHumanTask(taskId, reason.toString(), details, callerRoles, userId);
    if err is error {
        return classifyRuntimeError(err);
    }
    return buildCompletionResponse(userId).toJson();
}

// ── Review activities ─────────────────────────────────────────────────────────

isolated function opListReviewActivities(string? status, string? parentWorkflowId, string? taskName,
        int 'limit, string? pageToken, string? startTimeFrom, string? startTimeTo,
        string? closeTimeFrom, string? closeTimeTo, string? taskQueue, [string, string...]? callerRoles)
        returns json|Error {
    ReviewActivitySummary[]|error all = listAllReviewActivities(status,
            startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, taskQueue);
    if all is error {
        return executionFailed("Failed to list review activities: " + all.message());
    }
    ReviewActivitySummary[] preFiltered = all
        .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
        .filter(t => taskName is () || t.taskName == taskName);
    // Role visibility in a foreach (avoids the lambda isolation constraint on
    // computed local variables in this Ballerina version — see the human-task op).
    ReviewActivitySummary[] filtered = [];
    foreach ReviewActivitySummary t in preFiltered {
        if canAccessReviewActivity(t.userRoles, callerRoles) {
            filtered.push(t);
        }
    }
    return paginateReviewActivities(filtered, clampLimit('limit, maxPageSize), pageToken).toJson();
}

isolated function opGetReviewActivity(string taskId, [string, string...]? callerRoles)
        returns json|Error {
    ReviewActivityInfo|error info = getReviewActivityInfo(taskId);
    if info is error {
        return notFoundOrExecutionError(info, "Review activity not found: " + taskId);
    }
    if !canAccessReviewActivity(info.userRoles, callerRoles) {
        return accessDenied("Unauthorized: caller is not allowed to access this review activity");
    }
    return info.toJson();
}

isolated function opDecideReviewActivity(string taskId, string action, map<json>? input, string? feedback,
        [string, string...]? callerRoles, string? userId)
        returns json|Error {
    AccessDeniedError? roleErr = reviewDecisionRoleError(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    ReviewDecision decision;
    if action == "proceed" {
        decision = {action: "proceed"};
    } else if action == "proceed-with-input" {
        if input !is map<json> {
            return invalidRequest("input must be a JSON object");
        }
        decision = {action: "proceed-with-input", input: <map<anydata>>input};
    } else if action == "reject" {
        decision = {action: "reject", feedback: feedback};
    } else {
        return invalidRequest("Unknown review decision action: " + action);
    }
    error? err = completeReviewActivity(taskId, decision, callerRoles, userId);
    if err is error {
        return classifyRuntimeError(err);
    }
    return buildReviewDecisionResponse(action, userId).toJson();
}

// The review-activity states a bulk decision reads. Both are values the runtime
// reports on a summary or info record, not values this module chooses.

# Trigger value marking a review that was created because an activity failed, as
# opposed to one gating a proposed call (`PRE_RUN`).
const ON_FAILURE_TRIGGER = "ON_FAILURE";

# Status of a review activity that is still awaiting a decision.
const PENDING_STATUS = "PENDING";

# Applies one decision — retry or fail — to every review activity the selector
# resolves to. Reports a per-task outcome instead of failing as a whole, because a
# bulk decision races other operators by nature: a task decided in between is a
# skip, not a reason to abandon the rest.
#
# + action - `retry` to rerun each activity, `fail` to surface its original failure
# + taskIds - Explicit review activity IDs, or `()` when selecting by parent
# + parentWorkflowId - Parent whose pending failure reviews are selected, or `()`
# + activityName - Narrows a parent selection to one activity, or `()`
# + feedback - Relayed to the caller on `fail`
# + callerRoles - Roles held by the caller
# + userId - Caller identity recorded in the report
# + return - The batch report as `json`, or the reason the batch was refused
isolated function opBulkRetryReviewActivities(string action, json? taskIds, string? parentWorkflowId,
        string? activityName, string? feedback, [string, string...]? callerRoles, string? userId)
        returns json|Error {
    if action != "retry" && action != "fail" {
        return invalidRequest("Unknown bulk retry action: " + action
                + " (expected \"retry\" or \"fail\")");
    }
    AccessDeniedError? roleErr = reviewDecisionRoleError(callerRoles);
    if roleErr is AccessDeniedError {
        return roleErr;
    }
    BulkCandidate[]|Error resolved = resolveBulkCandidates(taskIds, parentWorkflowId, activityName);
    if resolved is Error {
        return resolved;
    }
    ReviewDecision decision = action == "retry"
        ? {action: "proceed"}
        : {action: "reject", feedback: feedback};
    BulkItemResult[] items = [];
    int applied = 0;
    int skipped = 0;
    int failed = 0;
    foreach BulkCandidate candidate in resolved {
        BulkItemResult item = decideOneInBulk(candidate, decision, callerRoles, userId);
        items.push(item);
        match item.outcome {
            APPLIED => {
                applied += 1;
            }
            SKIPPED => {
                skipped += 1;
            }
            _ => {
                failed += 1;
            }
        }
    }
    BulkRetryResult result = {
        action: action == "retry" ? "retry" : "fail",
        requested: resolved.length(),
        applied: applied,
        skipped: skipped,
        failed: failed,
        items: items,
        decidedBy: userId ?: "unknown",
        decidedAt: time:utcToString(time:utcNow())
    };
    return result.toJson();
}

# One review activity a bulk decision will act on, with the state the eligibility
# check needs. Resolving through a parent workflow already yields that state, so it
# is carried here rather than re-read per task.
#
# + taskId - The review activity's workflow ID
# + summary - Its already-read state, or `()` when it came from an explicit ID list
type BulkCandidate record {|
    string taskId;
    ReviewActivitySummary? summary;
|};

# Resolves a bulk selector to the tasks it addresses. Exactly one of `taskIds` and
# `parentWorkflowId` selects; naming both, or neither, is a malformed request rather
# than a silent precedence rule.
#
# + taskIds - Explicit review activity IDs, or `()`
# + parentWorkflowId - Parent whose pending failure reviews are selected, or `()`
# + activityName - Narrows a parent selection to one activity, or `()`
# + return - The tasks to decide, or the reason the selector is malformed
isolated function resolveBulkCandidates(json? taskIds, string? parentWorkflowId, string? activityName)
        returns BulkCandidate[]|Error {
    boolean byIds = taskIds !is ();
    boolean byParent = parentWorkflowId is string && parentWorkflowId.trim().length() > 0;
    if byIds && byParent {
        return invalidRequest("Specify either taskIds or parentWorkflowId, not both");
    }
    if !byIds && !byParent {
        return invalidRequest("Either taskIds or parentWorkflowId is required");
    }
    if byIds {
        if activityName is string {
            return invalidRequest("activityName narrows a parentWorkflowId selection; "
                    + "it cannot be combined with taskIds");
        }
        // normalizeParams has already established that taskIds is an array.
        json[] ids = <json[]>taskIds;
        if ids.length() == 0 {
            return invalidRequest("taskIds must not be empty");
        }
        BulkCandidate[] candidates = [];
        map<()> seen = {};
        foreach json id in ids {
            if id !is string || id.trim().length() == 0 {
                return invalidRequest("taskIds must contain non-empty strings");
            }
            // A repeated id names one task, so it is decided once. Reporting it twice
            // would show the second as already decided — by this very call.
            if seen.hasKey(id) {
                continue;
            }
            if candidates.length() >= maxBulkRetrySize {
                return oversizedBulk();
            }
            seen[id] = ();
            candidates.push({taskId: id, summary: ()});
        }
        return candidates;
    }

    ReviewActivitySummary[]|error pending = listPendingReviewActivities(<string>parentWorkflowId);
    if pending is error {
        return notFoundOrExecutionError(pending, (), "Failed to list review activities: ");
    }
    BulkCandidate[] candidates = [];
    foreach ReviewActivitySummary summary in pending {
        // Only failure reviews: approving proposed calls in bulk is a different
        // decision from retrying failures, and is deliberately not reachable here.
        if summary.trigger != ON_FAILURE_TRIGGER {
            continue;
        }
        if activityName is string && summary.activityName != activityName {
            continue;
        }
        if candidates.length() >= maxBulkRetrySize {
            return oversizedBulk();
        }
        candidates.push({taskId: summary.taskId, summary: summary});
    }
    return candidates;
}

# Reports a selection larger than one batch may address. Raised as soon as the cap
# is passed, so an oversized request is rejected without first materializing it —
# the count is therefore deliberately absent from the message.
#
# + return - The refusal, naming the cap
isolated function oversizedBulk() returns InvalidRequestError =>
    invalidRequest("Too many review activities in one bulk decision (maximum is "
            + maxBulkRetrySize.toString() + ")");

# Submits the decision for one task, converting every failure into an outcome. A
# task that cannot be decided is reported in the result; it never aborts the batch.
#
# + candidate - The task to decide, with any state already read for it
# + decision - The decision to submit
# + callerRoles - Roles held by the caller
# + userId - Caller identity recorded with the decision
# + return - What happened to this task
isolated function decideOneInBulk(BulkCandidate candidate, ReviewDecision decision,
        [string, string...]? callerRoles, string? userId) returns BulkItemResult {
    ReviewActivitySummary? known = candidate.summary;
    string trigger;
    string status;
    string[] taskRoles;
    if known is ReviewActivitySummary {
        trigger = known.trigger;
        status = known.status;
        taskRoles = known.userRoles;
    } else {
        // The eligibility facts only — one describe. The full info record would add two
        // history scans per task for the audit fields a batch never reads.
        ReviewActivityState|error state = getReviewActivityState(candidate.taskId);
        if state is error {
            return {taskId: candidate.taskId, outcome: FAILED, reason: state.message()};
        }
        trigger = state.trigger;
        status = state.status;
        taskRoles = state.userRoles;
    }
    if !canAccessReviewActivity(taskRoles, callerRoles) {
        return {
            taskId: candidate.taskId,
            outcome: FAILED,
            reason: "Unauthorized: caller is not allowed to decide this review activity"
        };
    }
    if trigger != ON_FAILURE_TRIGGER {
        return {
            taskId: candidate.taskId,
            outcome: SKIPPED,
            reason: "Not a failed-activity review: this review gates a proposed call"
        };
    }
    if status != PENDING_STATUS {
        return {
            taskId: candidate.taskId,
            outcome: SKIPPED,
            reason: "Already decided: the review activity is " + status
        };
    }
    error? err = completeReviewActivity(candidate.taskId, decision, callerRoles, userId);
    if err is error {
        // A task decided between the eligibility check and the submission is a skip,
        // not a failure: the caller asked for it to be decided and it is.
        Error classified = classifyRuntimeError(err);
        return {
            taskId: candidate.taskId,
            outcome: classified is ConflictError ? SKIPPED : FAILED,
            reason: classified.message()
        };
    }
    return {taskId: candidate.taskId, outcome: APPLIED, reason: ()};
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

isolated function clampLimit(int requested, int maxAllowed) returns int {
    // The default applies when nothing usable was requested, but it is still a
    // request: an operator who caps pages below the default gets the cap.
    int effective = requested < 1 ? 20 : requested;
    return effective > maxAllowed ? maxAllowed : effective;
}

isolated function parseRolesHeader(string? rolesHeader) returns [string, string...]? {
    if rolesHeader is () || rolesHeader.trim().length() == 0 { return (); }
    string[] parts = re`,`.split(rolesHeader).map(r => r.trim()).filter(r => r.length() > 0);
    if parts.length() == 0 { return (); }
    return [parts[0], ...parts.slice(1)];
}

isolated function ensureWorkflowDetailAccess([string, string...]? callerRoles) returns AccessDeniedError? {
    if callerRoles is () {
        return accessDenied("Unauthorized: caller roles are required to view workflow details");
    }
    return ();
}

# A review activity with declared roles requires a matching caller role (same rule as human
# tasks). A review activity with no declared roles is visible to any caller by default;
# when `reviewActivityAccessRole` is configured, the caller must hold that role instead.
#
# + taskRoles - Roles the review activity declares
# + callerRoles - Roles held by the caller
# + return - Whether the caller may see or act on it
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
#
# + callerRoles - Roles held by the caller
# + return - The refusal when the configured role is missing, otherwise `()`
isolated function reviewDecisionRoleError([string, string...]? callerRoles) returns AccessDeniedError? {
    string? requiredRole = reviewActivityAccessRole;
    if requiredRole is string && requiredRole.trim().length() > 0 {
        if callerRoles is () || callerRoles.indexOf(requiredRole) is () {
            return accessDenied(
                    "Unauthorized: the '" + requiredRole + "' role is required to decide review activities");
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

isolated function paginateHumanTasks(HumanTaskSummary[] items, int 'limit, string? pageToken)
        returns HumanTaskPage {
    // Sort by (startTime asc, taskId asc) for a deterministic, stable order.
    HumanTaskSummary[] sorted = from HumanTaskSummary t in items
        order by t.startTime ascending, t.taskId ascending select t;
    // Seek past the cursor item so page N+1 starts after the last item on page N.
    HumanTaskSummary[] remaining = sorted;
    if pageToken is string {
        [string, string] cursor = decodeCursorToken(pageToken);
        string cursorTime = cursor[0];
        string cursorId = cursor[1];
        if cursorTime != "" {
            remaining = from HumanTaskSummary t in sorted
                where t.startTime > cursorTime
                    || (t.startTime == cursorTime && t.taskId > cursorId)
                select t;
        }
    }
    int count = remaining.length();
    boolean hasMore = count > 'limit;
    HumanTaskSummary[] pageItems = hasMore ? remaining.slice(0, 'limit) : remaining;
    string? nextToken = ();
    if hasMore {
        HumanTaskSummary last = pageItems[pageItems.length() - 1];
        nextToken = encodeCursorToken(last.startTime, last.taskId);
    }
    return {items: pageItems, nextPageToken: nextToken, hasMore: hasMore};
}

isolated function paginateReviewActivities(ReviewActivitySummary[] items, int 'limit, string? pageToken)
        returns ReviewActivityPage {
    ReviewActivitySummary[] sorted = from ReviewActivitySummary t in items
        order by t.startTime ascending, t.taskId ascending select t;
    ReviewActivitySummary[] remaining = sorted;
    if pageToken is string {
        [string, string] cursor = decodeCursorToken(pageToken);
        string cursorTime = cursor[0];
        string cursorId = cursor[1];
        if cursorTime != "" {
            remaining = from ReviewActivitySummary t in sorted
                where t.startTime > cursorTime
                    || (t.startTime == cursorTime && t.taskId > cursorId)
                select t;
        }
    }
    int count = remaining.length();
    boolean hasMore = count > 'limit;
    ReviewActivitySummary[] pageItems = hasMore ? remaining.slice(0, 'limit) : remaining;
    string? nextToken = ();
    if hasMore {
        ReviewActivitySummary last = pageItems[pageItems.length() - 1];
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
