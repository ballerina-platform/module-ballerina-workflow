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
import ballerina/time;

# Workflow execution context providing activity execution, durable sleep,
# deterministic time, and multi-future await APIs.
public client class Context {
    private handle nativeContext;

    # Creates a workflow execution context wrapping the native context handle.
    # This constructor is called by the workflow runtime; do not instantiate `Context` directly.
    # + nativeContext - Native context handle from the workflow engine
    public isolated function init(handle nativeContext) {
        self.nativeContext = nativeContext;
    }

    # Executes an activity function. The activity runs exactly once, even if the process crashes and restarts.
    #
    # ```ballerina
    # PaymentResult result = check ctx->callActivity(processPayment, args = {"orderId": orderId});
    # ```
    #
    # + activityFunction - The activity function (must have `@Activity`)
    # + args - Arguments to pass to the activity. Values are normally `anydata`.
    #          Module-level `final` `client object` variables may also be passed for
    #          activity parameters whose declared type is a client object; the
    #          compiler plugin validates the call site and substitutes a
    #          `"connection:<name>"` marker for transport across the workflow
    #          execution boundary.
    # + T - Expected return type (inferred from context)
    # + retryPolicy - Retry behaviour on failure:
    #   - `()` / `NoAutomaticRetry` (default) — error is returned as-is, no retry.
    #   - `AutoRetry` — automatic backoff retry with configurable attempts and delays.
    #   - `HumanReview` — on failure a review task is created for a human to decide
    #     whether to retry (optionally with new input) or permanently fail the activity.
    # + return - The activity result as `T`, or an error
    remote isolated function callActivity(function activityFunction,
            map<anydata|object {}> args = {},
            typedesc<anydata> T = <>, AutoRetry|HumanReview|NoAutomaticRetry retryPolicy = NoAutomaticRetry)
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "callActivity"
    } external;

    # Durable sleep that survives process crashes and restarts. Do not use runtime:sleep() in workflows.
    #
    # ```ballerina
    # check ctx.sleep({seconds: 30});
    # ```
    #
    # + duration - The duration to sleep
    # + return - An error if the sleep fails, otherwise nil
    public isolated function sleep(time:Duration duration) returns error? {
        decimal totalSeconds = <decimal>duration.hours * 3600 +
                               <decimal>duration.minutes * 60 +
                               duration.seconds;
        int millis = <int>(totalSeconds * 1000);
        return sleepContextNative(self.nativeContext, millis);
    }

    # Returns the deterministic workflow time. Use instead of `time:utcNow()` inside workflows.
    #
    # ```ballerina
    # time:Utc now = ctx.currentTime();
    # ```
    #
    # + return - The current workflow time as `time:Utc`
    public isolated function currentTime() returns time:Utc {
        int millis = currentTimeMillisContextNative(self.nativeContext);
        int seconds = millis / 1000;
        decimal fraction = <decimal>(millis % 1000) / 1000d;
        return [seconds, fraction];
    }

    # Checks whether the workflow is recovering from a failure (re-executing recorded history).
    #
    # + return - `true` if recovering, `false` on first execution
    public isolated function isReplaying() returns boolean {
        return isReplayingNative(self.nativeContext);
    }

    # Get the unique workflow ID.
    #
    # + return - The workflow ID
    public isolated function getWorkflowId() returns string|error {
        return getWorkflowIdNative(self.nativeContext);
    }

    # Get the workflow type name.
    #
    # + return - The workflow type
    public isolated function getWorkflowType() returns string|error {
        return getWorkflowTypeNative(self.nativeContext);
    }

    # Waits for at least `minCount` data futures to complete. Results are a positional tuple
    # aligned to input order. Use nullable types (`T?`) for partial waits.
    #
    # The result can be captured in several ways:
    #
    # ```ballerina
    # // Wait for all (tuple binding pattern)
    # [Approval, Payment] [approval, payment] = check ctx->await([events.approval, events.payment]);
    # // Capture the whole result, including a possible timeout, without `check`
    # [Approval, Payment]|error result = ctx->await([events.approval, events.payment]);
    # if result is error { /* handle timeout */ }
    # // Handle each position independently (a slot is a value or an error)
    # [Approval|error, Payment|error] [a, p] = check ctx->await([events.approval, events.payment]);
    # // Wait for any (1 of 2) — use nilable members for partial waits
    # [Approval?, Payment?] result = check ctx->await([events.approval, events.payment], minCount = 1);
    # ```
    #
    # + futures - Data futures from the workflow's events record
    # + minCount - Minimum completions required (default: all)
    # + timeout - Maximum wait duration; returns an error on timeout
    # + T - Expected return type, inferred from how the result is assigned
    # + return - Positional tuple of values (`nil` for an incomplete position), or an error
    remote isolated function await(future<anydata>[] futures,
            int:Unsigned32 minCount = <int:Unsigned32>futures.length(),
            time:Duration? timeout = (),
            typedesc<anydata|error|(anydata|error)[]> T = <>) returns T = @java:Method {
        'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WaitUtils",
        name: "awaitFutures"
    } external;

    # Creates a human task and blocks until a human completes it or the optional timeout elapses.
    # Internally, the task is modelled as a durable Temporal child workflow whose type is `taskName`,
    # so the task survives worker restarts.  Register the task name at module init time via
    # `wfInternal:registerHumanTask(taskName)` (the compiler plugin generates this call automatically).
    #
    # ```ballerina
    # ApprovalDecision d = check ctx->awaitHumanTask("approveExpense", "FINANCE_APPROVER",
    #     payload = {"amount": 1200, "currency": "USD"},
    #     title = "Approve order",
    #     timeout = {hours: 24}
    # ) on fail workflow:HumanTaskTimeoutError e {
    #     check ctx->callActivity(notifyEscalation, args = {"taskName": e.detail().taskName});
    #     return e;
    # };
    # ```
    #
    # + taskName - Identifies the task type; used as the Temporal workflow type and child workflow ID
    # + userRoles - One or more roles permitted to complete this task
    # + payload - Read-only JSON object rendered as key-value pairs next to the form
    # + title - Short summary shown in the inbox. Defaults to `taskName` when omitted
    # + description - Additional context shown alongside the form. Optional
    # + timeout - Maximum time to wait. Omit (or pass `()`) to wait indefinitely
    # + T - Expected result type; drives form schema generation and runtime validation
    # + return - The typed value submitted by the human, or a `HumanTaskTimeoutError`
    remote isolated function awaitHumanTask(
            string taskName,
            string|string[] userRoles,
            map<json> payload = {},
            string? title = (),
            string? description = (),
            time:Duration? timeout = (),
            typedesc<anydata> T = <>)
            returns T|HumanTaskTimeoutError = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "awaitHumanTask"
    } external;

    # Starts a child workflow and returns its instance ID without waiting for the result.
    # The child is a true Temporal child workflow: its lifecycle is tied to this workflow,
    # so when this workflow closes, in-flight children are cancelled with it.
    #
    # Use `getChildWorkflowResult` (non-blocking) or `waitForChildWorkflow` (durable wait)
    # to read the child's result later — this enables fan-out/fan-in orchestration:
    #
    # ```ballerina
    # string kycId = check ctx->runChildWorkflow(kycWorkflow, input = customer);   // fan out
    # string scoreId = check ctx->runChildWorkflow(scoreWorkflow, input = customer);
    # Kyc kyc = check ctx->waitForChildWorkflow(kycId);                            // gather
    # Score score = check ctx->waitForChildWorkflow(scoreId);
    # ```
    #
    # + childWorkflow - The child workflow function (must have `@Workflow`)
    # + input - Optional input for the child workflow. Must match the child workflow
    #           function's declared input parameter type (any `anydata` subtype)
    # + return - The child workflow instance ID, or an error if the child could not start
    remote isolated function runChildWorkflow(function childWorkflow, anydata input = ())
            returns string|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "runChildWorkflow"
    } external;

    # Returns the result of a child workflow started with `runChildWorkflow` if it has
    # already completed, without waiting. While the child is still running (e.g. suspended
    # on a human task) a `workflow:WorkflowBusyError` is returned — check back later, or
    # switch to the blocking `waitForChildWorkflow` form.
    #
    # ```ballerina
    # Kyc|error result = ctx->getChildWorkflowResult(kycId);
    # if result is workflow:WorkflowBusyError { /* still running — do other work */ }
    # ```
    #
    # + childWorkflowId - The child workflow instance ID returned by `runChildWorkflow`
    # + T - Expected result type (inferred from context)
    # + return - The child's result as `T`, a `workflow:WorkflowBusyError` while the child
    #            is still running, or an error if the child failed
    remote isolated function getChildWorkflowResult(string childWorkflowId, typedesc<anydata> T = <>)
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "getChildWorkflowResult"
    } external;

    # Waits durably until a child workflow started with `runChildWorkflow` completes and
    # returns its result. The wait is a durable suspend — no thread is held, and the wait
    # survives worker crashes and restarts (on replay the result is served from history).
    #
    # + childWorkflowId - The child workflow instance ID returned by `runChildWorkflow`
    # + T - Expected result type (inferred from context)
    # + return - The child's result as `T`, or an error if the child failed
    remote isolated function waitForChildWorkflow(string childWorkflowId, typedesc<anydata> T = <>)
            returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "waitForChildWorkflow"
    } external;

    # Starts a child workflow and durably waits for its result — `runChildWorkflow`
    # followed by `waitForChildWorkflow` fused into one call. "Blocking" here is a durable
    # suspend, not a held thread, so this is safe for long-running children.
    #
    # ```ballerina
    # Receipt receipt = check ctx->callWorkflow(billingWorkflow, input = order);
    # ```
    #
    # + childWorkflow - The child workflow function (must have `@Workflow`)
    # + input - Optional input for the child workflow. Must match the child workflow
    #           function's declared input parameter type (any `anydata` subtype)
    # + T - Expected result type (inferred from context)
    # + return - The child's result as `T`, or an error if the child failed
    remote isolated function callWorkflow(function childWorkflow, anydata input = (),
            typedesc<anydata> T = <>) returns T|error = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "callWorkflow"
    } external;

    # Sends data to a running workflow instance's events record from inside a workflow.
    # This is the in-workflow counterpart of `workflow:sendData` and is typically used to
    # signal a child workflow started with `runChildWorkflow`, but accepts any workflow
    # instance ID.
    #
    # ```ballerina
    # check ctx->sendDataToChildWorkflow(childId, "approval", {approved: true});
    # ```
    #
    # + childWorkflowId - Target workflow instance ID (usually from `runChildWorkflow`)
    # + dataName - Field name in the target workflow's events record
    # + data - The data payload
    # + return - An error if sending fails
    remote isolated function sendDataToChildWorkflow(string childWorkflowId, string dataName,
            anydata data) returns error? = @java:Method {
        'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
        name: "sendDataToChildWorkflow"
    } external;
}

// Native function declarations

isolated function sleepContextNative(handle contextHandle, int millis) returns error? = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "sleepMillis"
} external;

isolated function currentTimeMillisContextNative(handle contextHandle) returns int = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "currentTimeMillis"
} external;

isolated function isReplayingNative(handle contextHandle) returns boolean = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "isReplaying"
} external;

isolated function getWorkflowIdNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "getWorkflowId"
} external;

isolated function getWorkflowTypeNative(handle contextHandle) returns string|error = @java:Method {
    'class: "io.ballerina.lib.workflow.context.WorkflowContextNative",
    name: "getWorkflowType"
} external;
