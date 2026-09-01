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

# Deployment mode for the workflow runtime.
#
# + LOCAL - Local development server (e.g., `temporal server start-dev`)
# + CLOUD - Managed cloud deployment (requires authentication)
# + SELF_HOSTED - Self-hosted server (authentication is optional)
# + IN_MEMORY - Lightweight in-memory engine (no persistence, no external server)
public enum Mode {
    LOCAL,
    CLOUD,
    SELF_HOSTED,
    IN_MEMORY
}

# Internal retry policy used to pass module-level defaults to the native layer.
# + initialIntervalInSeconds - Initial delay before the first retry attempt in seconds
# + backoffCoefficient - Multiplier applied to the interval after each retry
# + maximumIntervalInSeconds - Optional cap on the delay between retries in seconds
# + maximumAttempts - Maximum number of retry attempts (1 = no retries)
type ActivityRetryPolicy record {|
    int initialIntervalInSeconds = 1;
    decimal backoffCoefficient = 2.0;
    int maximumIntervalInSeconds?;
    int maximumAttempts = 1;
|};

// ---------------------------------------------------------------------------
// Activity retry policy types
// ---------------------------------------------------------------------------

# No automatic retry by the engine. Errors from the activity are returned
# directly to the caller. This is the default behaviour when no `retryPolicy`
# is specified. Note that an AI agent may still decide to call the activity
# again from its own reasoning — this policy only disables engine-driven
# retries.
public const NoAutomaticRetry = ();

# Deprecated alias of `NoAutomaticRetry`.
# # Deprecated
# Use `NoAutomaticRetry` instead: it makes explicit that only engine-driven
# retries are disabled (an AI agent may still re-invoke the activity).
@deprecated
public const NoRetry = ();

# Automatic retry configuration. When the activity fails, it is automatically
# retried according to the configured backoff policy.
#
# + maxRetries - Maximum retry attempts (default: 3)
# + retryDelay - Initial delay in seconds before the first retry (default: 1.0)
# + retryBackoff - Multiplier applied to delay after each retry (default: 2.0)
# + maxRetryDelay - Cap on the delay between retries, in seconds
public type AutoRetry record {|
    int maxRetries = 3;
    decimal retryDelay = 1.0;
    decimal retryBackoff = 2.0;
    decimal maxRetryDelay?;
|};


# Options for activity execution via `callActivity`.
#
# + retryOnError - Enable automatic retries on failure (default: `false`)
# + maxRetries - Maximum retry attempts (default: 0, no retries)
# + retryDelay - Initial delay in seconds before the first retry (default: 1.0)
# + retryBackoff - Multiplier applied to delay after each retry (default: 2.0)
# + maxRetryDelay - Cap on the delay between retries, in seconds
public type ActivityOptions record {|
    boolean retryOnError = false;
    int maxRetries = 0;
    decimal retryDelay = 1.0;
    decimal retryBackoff = 2.0;
    decimal maxRetryDelay?;
|};

# Information about a registered workflow process.
#
# + name - The name of the registered process
# + activities - Array of activity names associated with this process
# + events - Array of event names (signals) this process can receive
type ProcessRegistration record {
    string name;
    string[] activities;
    string[] events;
};

# Information about all registered workflows.
# This is a map where keys are process names and values are their registration info.
type WorkflowRegistry map<ProcessRegistration>;

// ---------------------------------------------------------------------------
// HumanTask types
// ---------------------------------------------------------------------------

# Detail fields carried by a `HumanTaskTimeoutError`.
#
# + taskName - The `taskName` value passed to `awaitHumanTask`
# + taskWorkflowId - Temporal child workflow ID of the timed-out task instance
# + timedOutAfter - Configured deadline as an ISO-8601 duration (e.g. `"PT24H"`)
# + timedOutAt - ISO-8601 timestamp at which the timeout was recorded
public type HumanTaskTimeoutDetail record {|
    string taskName;
    string taskWorkflowId;
    string timedOutAfter;
    string timedOutAt;
|};

# Returned by `awaitHumanTask` when no human acts within the configured deadline.
# Catch the whole family with `on fail workflow:HumanTaskError e` and narrow with
# `if e is workflow:HumanTaskTimeoutError` to run compensation logic for a timeout.
public type HumanTaskTimeoutError distinct error<HumanTaskTimeoutDetail>;

# Detail fields carried by a `HumanTaskRejectedError`.
#
# + taskName - The `taskName` value passed to `awaitHumanTask`
# + taskWorkflowId - Temporal child workflow ID of the rejected task instance
# + reason - The reason submitted with the rejection
# + details - Structured data submitted with the rejection, or `()` if none was given
# + rejectedBy - The user who rejected the task, when the rejection recorded one
public type HumanTaskRejectedDetail record {|
    string taskName;
    string taskWorkflowId;
    string reason;
    map<json>? details = ();
    string? rejectedBy = ();
|};

# Returned by `awaitHumanTask` when the task is rejected instead of completed — the
# `fail` management operation, which records a reason rather than a result. The reason
# and any structured details submitted with the rejection are on the error detail, so a
# workflow can compensate on what the rejecting user said:
#
# ```ballerina
# Approval|workflow:HumanTaskError approval = ctx->awaitHumanTask("approve", userRoles = "FINANCE");
# if approval is workflow:HumanTaskRejectedError {
#     _ = check ctx->callActivity(notifyRejected, args = {"reason": approval.detail().reason});
# }
# ```
public type HumanTaskRejectedError distinct error<HumanTaskRejectedDetail>;

# Returned by `awaitHumanTask` when the task neither completed nor closed with a reason
# it can report — the task workflow failed, was terminated by an administrator, or the
# submitted value did not match the expected result type.
public type HumanTaskFailedError distinct error;

# Every failure `awaitHumanTask` can report: nobody acted in time
# (`HumanTaskTimeoutError`), someone rejected the task (`HumanTaskRejectedError`), or the
# task could not produce a result at all (`HumanTaskFailedError`).
public type HumanTaskError HumanTaskTimeoutError|HumanTaskRejectedError|HumanTaskFailedError;

# A data-event turn a durable agent has accepted but not yet answered. Returned
# by `getPendingAgentEvents` so callers can rediscover in-flight event turns
# after a crash and fetch their answers via `DurableAgent.getDataResult` /
# `waitForDataResult`.
#
# + token - The turn's correlation token (as returned by `DurableAgent.sendData`)
# + eventName - The event channel the turn was sent on
public type PendingAgentEvent record {|
    string token;
    string eventName;
|};

// ---------------------------------------------------------------------------
// Child workflow types
// ---------------------------------------------------------------------------

# Returned by the non-blocking `ctx->getChildWorkflowResult` read when the child
# workflow is still running (e.g. suspended on a human task). Check back later, or
# use the blocking `ctx->waitForChildWorkflow` form, which durably suspends until
# the child completes.
public type WorkflowBusyError distinct error;

# What every human decision says, whoever is asked: who may answer it, and how it reads.
# Shared because a workflow's human task, a durable agent's task capability and the review
# a gated activity raises are one idea — someone with the right role is asked something and
# the process waits for the answer.
#
# This is the whole of a REVIEW's definition, which is why it carries the review's name: a
# review's answer is the fixed three-way decision (proceed, proceed with edited input,
# reject) and its payload is the reviewed activity's proposed input, so neither is declared.
# A human task adds both — see `HumanTaskDefinition`.
#
# What is deliberately absent is the task's NAME: it is `awaitHumanTask`'s first argument,
# the key of an agent's `humanTasks` mapping, or derived from the reviewed activity — a
# compile-time constant BY CONSTRUCTION in all three, which is worth more than a field that
# has to be validated to be one.
#
# Deliberately an OPEN record: forward compatibility is the point. A new option is a new
# field here — never a new parameter and never a new type — so today's code keeps compiling
# against tomorrow's module, and an option written for a newer module still compiles against
# this one. People-assignment is expected to grow this way: `userRoles` names the potential
# owners today, and richer WS-HumanTask-style assignments (actual owner, business
# administrators, four-eye constraints) arrive as new fields. Tooling that renders decision
# forms should derive its fields from this record rather than a fixed list.
#
# + userRoles - One or more roles permitted to answer this decision (its potential owners).
#               Required: a decision must say who may make it
# + title - Short summary shown in the inbox. Defaults to the task name, or for a review to
#           a phrase naming the activity being reviewed
# + description - Additional context shown alongside the form or decision. Optional
# + timeout - Maximum time to wait. Omit (or pass `()`) to wait indefinitely
# Any JSON object — the shape a human task's payload has when its definition names no
# narrower one. A named type because a typedesc value must be written as a type
# reference: `payloadType = map<json>` is not an expression Ballerina accepts.
public type JsonObject map<json>;

public type ReviewTaskDefinition record {
    string|string[] userRoles;
    string? title = ();
    string? description = ();
    Duration? timeout = ();
};

# What a human TASK is: every decision's fields, plus the two types that make the task
# checkable at its edges — what it shows the decider, and what it accepts back.
#
# A review needs neither (see `ReviewTaskDefinition`): its payload is the reviewed
# activity's proposed input and its answer is the fixed three-way decision. A task needs
# both, because a task shows a form and takes a typed result.
#
# The two types are used differently by the two callers, and both uses are checked:
#
# * A workflow passes the payload VALUE as `awaitHumanTask`'s second argument, and the
#   runtime checks it against `payloadType` before the task is created — a task that
#   would show the wrong form never reaches a person. The RESULT type comes from the
#   call's `T` typedesc, which is what makes the call dependently typed; `resultType`
#   is the agent's way of saying the same thing and is not read on this path.
# * An agent declares both, because it has no parameter list: the model supplies the
#   payload when it asks, so `payloadType` is the only thing standing between a
#   malformed model argument and a person's inbox, and creation fails when they disagree.
#
# + payloadType - The shape of the payload shown to the decider. Checked against the
#                 payload actually supplied, whoever supplies it
# + resultType - The shape of the answer. Drives form schema generation and validation for
#                an agent's task; a workflow states it as the call's `T` instead
public type HumanTaskDefinition record {
    *ReviewTaskDefinition;
    typedesc<map<json>> payloadType = JsonObject;
    typedesc<anydata> resultType = anydata;
};

# Deprecated name of `HumanTaskDefinition`.
#
# # Deprecated
# Use `HumanTaskDefinition`. The payload VALUE is now `awaitHumanTask`'s second argument —
# required, so a task with nothing to show says so with `{}` rather than by omission — and
# its shape is declared here as `payloadType`.
@deprecated
public type HumanTaskOptions HumanTaskDefinition;

# Deprecated name of `ReviewTaskDefinition`.
#
# # Deprecated
# Use `ReviewTaskDefinition`: a review is declared with every field a human task is
# declared with, minus the two a review cannot have.
@deprecated
public type HumanReview ReviewTaskDefinition;

# How a `Context.callActivity` invocation behaves, passed as an included record
# parameter. Like `HumanTaskOptions`, deliberately an OPEN record so a future behaviour
# option — an approval gate, a heartbeat policy, a per-call timeout — is a new field
# here rather than a new parameter, and tooling derives its forms from this record.
# The step identity (`stepId`) is NOT here: it is workflow mechanics, not invocation
# behaviour, and stays a function parameter on every context operation.
#
# + retryPolicy - Failure behaviour: `NoAutomaticRetry` (fail the workflow),
#                 `AutoRetry` (durable backoff retries), or a `ReviewTaskDefinition`
#                 (raise a review on failure so a person decides to rerun, rerun with
#                 edited input, or fail). The two record forms are told apart by
#                 `userRoles`, which only a decision has
public type CallActivityOptions record {
    AutoRetry|ReviewTaskDefinition|NoAutomaticRetry retryPolicy = NoAutomaticRetry;
};

# A time duration, structurally identical to `time:Duration`. Declared in this module so
# timeout fields render as first-class workflow forms without a cross-module type reference;
# `time:Duration` values remain assignable.
public type Duration record {|
    # The duration in years
    int years = 0;
    # The duration in months
    int months = 0;
    # The duration in weeks
    int weeks = 0;
    # The duration in days
    int days = 0;
    # The duration in hours
    int hours = 0;
    # The duration in minutes
    int minutes = 0;
    # The duration in seconds
    decimal seconds = 0.0;
|};
