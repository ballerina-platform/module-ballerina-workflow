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

// Unit tests for executeCommand: parameter validation, identity mapping, and the
// error type each failure produces. These exercise the same operation functions
// the REST resources call, so the error a command reports is exactly the error
// the REST layer maps to a status code.

@test:Config {groups: ["unit"]}
function testCommandMissingRequiredParam() {
    json|Error result = executeCommand({operation: GET_INSTANCE});
    test:assertTrue(result is InvalidRequestError,
        "A missing required parameter must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "workflowId is required");
}

@test:Config {groups: ["unit"]}
function testCommandListDefinitionsReturnsPayloadShape() returns error? {
    json|Error result = executeCommand({operation: LIST_DEFINITIONS});
    test:assertFalse(result is Error, "definitions.list must succeed on a running worker");
    json body = check result.ensureType();
    test:assertTrue(body is map<json> && (<map<json>>body).hasKey("definitions"),
        "definitions.list must return the payload shape {definitions: [...]}");
}

@test:Config {groups: ["unit"]}
function testCommandCompleteHumanTaskRequiresRoles() {
    // An empty identity.roles array means the caller holds no roles — the same
    // denial the REST layer reports when the caller presents none.
    json|Error result = executeCommand({
        operation: COMPLETE_HUMAN_TASK,
        params: {taskId: "humantask-x", result: {}}
    });
    test:assertTrue(result is AccessDeniedError,
        "Completing without roles must be an AccessDeniedError");
    test:assertEquals((<Error>result).message(), "Unauthorized: caller roles are required");
}

@test:Config {groups: ["unit"]}
function testCommandFailHumanTaskRequiresReason() {
    json|Error result = executeCommand({
        operation: FAIL_HUMAN_TASK,
        params: {taskId: "humantask-x"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A missing reason must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "reason is required");
}

@test:Config {groups: ["unit"]}
function testCommandDecideRequiresInputForProceedWithInput() {
    json|Error result = executeCommand({
        operation: DECIDE_REVIEW_ACTIVITY,
        params: {taskId: "reviewactivity-x", action: "proceed-with-input"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A missing decision input must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "input must be a JSON object");
}

@test:Config {groups: ["unit"]}
function testCommandDecideRejectsUnknownAction() {
    json|Error result = executeCommand({
        operation: DECIDE_REVIEW_ACTIVITY,
        params: {taskId: "reviewactivity-x", action: "escalate"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "An unknown action must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "Unknown review decision action: escalate");
}

// ── Bulk retry selection ──────────────────────────────────────────────────────
// A bulk decision addresses many tasks, so every way of naming the wrong set is
// reported before any task is decided — a partially applied batch is worse than a
// rejected one.

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsUnknownAction() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "escalate", taskIds: ["reviewactivity-x"]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "An unknown bulk action must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(),
        "Unknown bulk retry action: escalate (expected \"retry\" or \"fail\")");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRequiresAction() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {taskIds: ["reviewactivity-x"]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A missing action must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "action is required");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRequiresASelector() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A bulk decision with no selector must be rejected");
    test:assertEquals((<Error>result).message(), "Either taskIds or parentWorkflowId is required");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsBothSelectors() {
    // Neither selector wins by precedence: naming both is ambiguous about which set
    // of tasks the caller meant, so it is reported rather than resolved.
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: ["reviewactivity-x"], parentWorkflowId: "wf-1"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "Naming both selectors must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "Specify either taskIds or parentWorkflowId, not both");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsNonArrayTaskIds() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: "reviewactivity-x"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A scalar taskIds must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "taskIds must be a JSON array");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsEmptyTaskIds() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: []},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "An empty taskIds must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "taskIds must not be empty");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsNonStringTaskId() {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: ["reviewactivity-x", 42]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "A non-string task ID must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(), "taskIds must contain non-empty strings");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsActivityNameWithTaskIds() {
    // activityName narrows the set a parent workflow resolves to; with an explicit
    // list the caller has already chosen, so accepting it would silently drop tasks
    // the caller named.
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: ["reviewactivity-x"], activityName: "chargeCard"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "activityName with taskIds must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(),
        "activityName narrows a parentWorkflowId selection; it cannot be combined with taskIds");
}

@test:Config {groups: ["unit"]}
function testBulkRetryRejectsOversizedBatch() {
    // Rejected during resolution, so an oversized selection never gets materialized
    // or looked up task by task.
    string[] ids = [];
    int i = 0;
    while i <= maxBulkRetrySize {
        ids.push("reviewactivity-" + i.toString());
        i += 1;
    }
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "fail", taskIds: ids.toJson()},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "An oversized batch must be an InvalidRequestError");
    test:assertEquals((<Error>result).message(),
        "Too many review activities in one bulk decision (maximum is " + maxBulkRetrySize.toString() + ")");
}

@test:Config {groups: ["unit"]}
function testBulkRetryDeduplicatesTaskIds() returns error? {
    // A repeated ID names one task. Without deduplication the second occurrence would
    // be reported as already decided — by this same call.
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "fail", taskIds: ["reviewactivity-dup", "reviewactivity-dup"]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertFalse(result is Error, "Duplicate IDs must not make the request malformed");
    json payload = check result.ensureType();
    BulkRetryResult report = check payload.cloneWithType();
    test:assertEquals(report.requested, 1, "A repeated task ID is addressed once");
    test:assertEquals(report.items.length(), 1);
}

@test:Config {groups: ["unit"]}
function testBulkRetryReportsUnknownTaskAsFailedItem() returns error? {
    // A task that cannot be decided is reported in the batch, not raised: the rest
    // of the batch still runs.
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "fail", taskIds: ["reviewactivity-does-not-exist"]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertFalse(result is Error, "An undecidable task must not fail the whole batch");
    json payload = check result.ensureType();
    BulkRetryResult report = check payload.cloneWithType();
    test:assertEquals(report.requested, 1);
    test:assertEquals(report.applied, 0);
    test:assertEquals(report.failed, 1);
    test:assertEquals(report.items[0].outcome, FAILED);
    test:assertEquals(report.decidedBy, "alice");
}

@test:Config {groups: ["unit"]}
function testBulkRetryWithRetryActionReportsPerTask() returns error? {
    // The retry action takes the same path as fail, so it is exercised too: the two
    // differ only in the decision submitted, and a batch of undecidable tasks still
    // reports rather than raises.
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "retry", taskIds: ["reviewactivity-nope-1", "reviewactivity-nope-2"]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertFalse(result is Error, "A batch of undecidable tasks must still report");
    json payload = check result.ensureType();
    BulkRetryResult report = check payload.cloneWithType();
    test:assertEquals(report.action, "retry");
    test:assertEquals(report.requested, 2);
    test:assertEquals(report.failed, 2);
    test:assertEquals(report.applied, 0);
    foreach BulkItemResult item in report.items {
        test:assertEquals(item.outcome, FAILED);
        test:assertTrue(item.reason is string, "A failed item must say why");
    }
}

@test:Config {groups: ["unit"]}
function testBulkRetryFeedbackIsAcceptedForFail() returns error? {
    json|Error result = executeCommand({
        operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "fail", taskIds: ["reviewactivity-nope"], feedback: "not recoverable"},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertFalse(result is Error, "Feedback must be accepted alongside a fail decision");
    json payload = check result.ensureType();
    BulkRetryResult report = check payload.cloneWithType();
    test:assertEquals(report.action, "fail");
    test:assertEquals(report.requested, 1);
}

// ── Operation names on the wire ───────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testOperationConvertsFromWireName() returns error? {
    // A consumer that receives an operation as text converts it to the enum; the
    // string values are the stable wire vocabulary.
    Operation operation = check "humanTasks.complete".ensureType();
    test:assertEquals(operation, COMPLETE_HUMAN_TASK);
    test:assertEquals(COMPLETE_HUMAN_TASK, "humanTasks.complete");
}

@test:Config {groups: ["unit"]}
function testUnknownWireNameIsRejected() {
    Operation|error operation = "nope".ensureType();
    test:assertTrue(operation is error, "An unknown operation name must not convert to an Operation");
}

// ── Error representation ──────────────────────────────────────────────────────

@test:Config {groups: ["unit"]}
function testErrorJsonRepresentation() {
    test:assertEquals(toErrorJson(error NotFoundError("Workflow not found: wf-1")),
        <json>{"error": {"message": "Workflow not found: wf-1"}},
        "Every transport serializes errors through this one representation");
}

// Adapters everywhere (the REST API, the ICP command tunnel's generated glue) branch
// on these reason values to pick their wire-specific codes; the classification and
// the enum's string values are a contract for all of them.
@test:Config {groups: ["unit"]}
function testErrorCodeClassification() {
    test:assertEquals(errorCodeOf(error NotFoundError("missing")), NOT_FOUND);
    test:assertEquals(errorCodeOf(error AccessDeniedError("no role")), ACCESS_DENIED);
    test:assertEquals(errorCodeOf(error InvalidRequestError("bad param")), INVALID_REQUEST);
    test:assertEquals(errorCodeOf(error ConflictError("already completed")), CONFLICT);
    test:assertEquals(errorCodeOf(error InvalidPayloadError("wrong shape")), INVALID_PAYLOAD);
    test:assertEquals(errorCodeOf(error ExecutionError("runtime failed")), EXECUTION_ERROR);
    test:assertEquals(errorCodeOf(error Error("unclassified")), EXECUTION_ERROR,
        "An error outside the named subtypes must classify as an execution failure");
    // The string values are wire-visible to consumers that carry the reason as text.
    test:assertEquals(<string>NOT_FOUND, "NOT_FOUND");
}

// ── Parameter typing ──────────────────────────────────────────────────────────
// A command may arrive from a channel that encodes scalars as text, so numeric
// parameters accept their string form; anything that cannot be coerced is
// reported instead of silently falling back to a default.

@test:Config {groups: ["unit"]}
function testNumericParamAcceptsStringForm() returns error? {
    map<json> normalized = check normalizeParams({"limit": "50", "status": "PENDING"});
    test:assertEquals(normalized["limit"], 50, "A string-encoded number must be coerced");
    test:assertEquals(normalized["status"], "PENDING");
}

@test:Config {groups: ["unit"]}
function testUncoercibleNumericParamIsReported() {
    map<json>|Error normalized = normalizeParams({"limit": "many"});
    test:assertTrue(normalized is InvalidRequestError,
        "A limit that is not a number must be reported, not replaced by the default");
    test:assertEquals((<Error>normalized).message(), "limit must be an integer");
}

@test:Config {groups: ["unit"]}
function testWrongTypedStringParamIsReported() {
    json|Error result = executeCommand({operation: GET_INSTANCE, params: {"workflowId": 42}});
    test:assertTrue(result is InvalidRequestError, "A non-string workflowId must be reported");
    test:assertEquals((<Error>result).message(), "workflowId must be a string",
        "The message must name the real cause, not report the parameter as missing");
}

// A workflow's input is bound to its declared parameter, which need not be a record,
// so `instances.start` must carry a scalar or an array through untouched. Rejecting
// them would leave every workflow with a non-record input unstartable.
@test:Config {groups: ["unit"]}
function testStartInputAcceptsAnyJson() returns error? {
    map<json> asObject = check normalizeParams({"input": {"orderId": "ORD-1"}});
    test:assertEquals(asObject["input"], <json>{"orderId": "ORD-1"});
    map<json> asScalar = check normalizeParams({"input": "ORD-1"});
    test:assertEquals(asScalar["input"], "ORD-1");
    map<json> asArray = check normalizeParams({"input": [1, 2]});
    test:assertEquals(asArray["input"], <json>[1, 2]);
}

// A human task's completion value is whatever the task declared, so `result` takes any
// json; `details` is documented as an object and is checked as such.
@test:Config {groups: ["unit"]}
function testCompletionResultAcceptsAnyJson() returns error? {
    map<json> asObject = check normalizeParams({"result": {"approved": true}});
    test:assertEquals(asObject["result"], <json>{"approved": true});
    map<json> asScalar = check normalizeParams({"result": "approved"});
    test:assertEquals(asScalar["result"], "approved");
}

@test:Config {groups: ["unit"]}
function testNonObjectDetailsIsReported() {
    // Silently dropping these made humanTasks.fail report success having lost them.
    json|Error result = executeCommand({
        operation: FAIL_HUMAN_TASK,
        params: {taskId: "humantask-x", reason: "boom", details: [1, 2]},
        identity: {userId: "alice", roles: ["approver"]}
    });
    test:assertTrue(result is InvalidRequestError, "Non-object details must be reported");
    test:assertEquals((<Error>result).message(), "details must be a JSON object");
}

// ── Every operation: dispatch and required-parameter contract ─────────────────
// One case per `Operation` member. Two tests drive the table: every operation must
// dispatch when given valid parameters (never fall through as unsupported, never
// report a parameter missing that was supplied), and every required parameter must
// be reported by name when absent. Operations that need a live workflow server fail
// with an ExecutionError here — that is still a dispatch, which is what these cover;
// the end-to-end behavior is exercised by the integration tests that run against the
// in-memory server.

type CommandCase record {|
    Operation operation;
    map<json> params = {};
    string[] required = [];
|};

// A caller with roles, so role-gated operations reach their implementation rather
// than stopping at the visibility check.
final Identity & readonly caseIdentity = {userId: "alice", roles: ["APPROVER", "OPS"]};

isolated function commandCases() returns CommandCase[] => [
    {operation: LIST_DEFINITIONS},
    {operation: LIST_INSTANCES, params: {status: "RUNNING", 'limit: 10}},
    {operation: START_INSTANCE, params: {workflowType: "someWorkflow", input: {}},
        required: ["workflowType"]},
    {operation: GET_INSTANCE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: SUSPEND_INSTANCE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: RESUME_INSTANCE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: WAKE_INSTANCE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: TERMINATE_INSTANCE, params: {workflowId: "wf-1", reason: "cleanup"},
        required: ["workflowId"]},
    {operation: CANCEL_INSTANCE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: GET_INSTANCE_HISTORY, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: GET_INSTANCE_ACTIVITY_TREE, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: GET_INSTANCE_EXECUTION_GRAPH, params: {workflowId: "wf-1"}, required: ["workflowId"]},
    {operation: LIST_HUMAN_TASKS, params: {status: "PENDING", 'limit: 10}},
    {operation: COUNT_PENDING_HUMAN_TASKS},
    {operation: GET_HUMAN_TASK, params: {taskId: "humantask-x"}, required: ["taskId"]},
    {operation: COMPLETE_HUMAN_TASK, params: {taskId: "humantask-x", result: {approved: true}},
        required: ["taskId"]},
    {operation: FAIL_HUMAN_TASK, params: {taskId: "humantask-x", reason: "boom"},
        required: ["taskId", "reason"]},
    {operation: LIST_REVIEW_ACTIVITIES, params: {status: "PENDING", 'limit: 10}},
    {operation: GET_REVIEW_ACTIVITY, params: {taskId: "reviewactivity-x"}, required: ["taskId"]},
    {operation: DECIDE_REVIEW_ACTIVITY, params: {taskId: "reviewactivity-x", action: "proceed"},
        required: ["taskId", "action"]},
    {operation: BULK_RETRY_REVIEW_ACTIVITIES,
        params: {action: "fail", taskIds: ["reviewactivity-x"]}, required: ["action"]}
];

// Every member of the enum. Kept explicit because Ballerina cannot enumerate an enum
// at run time; the coverage test below fails if the case table falls behind it.
isolated function allOperations() returns Operation[] => [
    LIST_DEFINITIONS, LIST_INSTANCES, START_INSTANCE, GET_INSTANCE, SUSPEND_INSTANCE,
    RESUME_INSTANCE, WAKE_INSTANCE, TERMINATE_INSTANCE, CANCEL_INSTANCE, GET_INSTANCE_HISTORY,
    GET_INSTANCE_ACTIVITY_TREE, GET_INSTANCE_EXECUTION_GRAPH, LIST_HUMAN_TASKS,
    COUNT_PENDING_HUMAN_TASKS, GET_HUMAN_TASK, COMPLETE_HUMAN_TASK, FAIL_HUMAN_TASK,
    LIST_REVIEW_ACTIVITIES, GET_REVIEW_ACTIVITY, DECIDE_REVIEW_ACTIVITY,
    BULK_RETRY_REVIEW_ACTIVITIES
];

@test:Config {groups: ["unit"]}
function testEveryOperationHasACase() {
    CommandCase[] cases = commandCases();
    foreach Operation operation in allOperations() {
        CommandCase[] matching = cases.filter(c => c.operation == operation);
        test:assertEquals(matching.length(), 1,
            string `Operation '${operation}' must have exactly one command case`);
    }
    test:assertEquals(cases.length(), allOperations().length(),
        "The case table must not carry operations the enum does not declare");
}

@test:Config {groups: ["unit"]}
function testEveryCommandDispatchesWithValidParams() {
    foreach CommandCase commandCase in commandCases() {
        json|Error result = executeCommand({
            operation: commandCase.operation,
            params: commandCase.params.clone(),
            identity: caseIdentity
        });
        if result is Error {
            // Reaching the implementation is what matters; a live-server failure is fine.
            // A parameter complaint is not: it means the dispatch rejected valid input.
            test:assertFalse(result is InvalidRequestError,
                string `Operation '${commandCase.operation}' rejected valid parameters: ${result.message()}`);
        }
    }
}

@test:Config {groups: ["unit"]}
function testEveryRequiredParamIsReportedByName() {
    foreach CommandCase commandCase in commandCases() {
        foreach string name in commandCase.required {
            map<json> params = commandCase.params.clone();
            json _ = params.remove(name);
            json|Error result = executeCommand({
                operation: commandCase.operation,
                params: params,
                identity: caseIdentity
            });
            test:assertTrue(result is InvalidRequestError,
                string `Operation '${commandCase.operation}' must report a missing '${name}'`);
            test:assertEquals((<Error>result).message(), name + " is required",
                string `Operation '${commandCase.operation}' must name the missing parameter`);
        }
    }
}
