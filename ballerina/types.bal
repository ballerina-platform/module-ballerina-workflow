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

# Human-review retry policy: the role(s) permitted to decide the retry review.
# Passing a role name (or list of role names) as the `retryPolicy` creates a
# review task on activity failure so a matching human can decide to retry,
# retry with different input, or permanently fail. The task name is derived
# automatically from the activity being called.
public type HumanReview string|string[];

# Deprecated alias of `HumanReview`.
# # Deprecated
# Use `HumanReview` instead.
@deprecated
public type ManualRetry HumanReview;

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
# Catch with `on fail workflow:HumanTaskTimeoutError e` to run compensation logic.
public type HumanTaskTimeoutError distinct error<HumanTaskTimeoutDetail>;

# A turn a durable agent has accepted but not yet answered. Returned by
# `getPendingAgentUpdates` so callers can rediscover in-flight event turns after
# a crash and fetch their answers via `DurableAgent.getEventResult` /
# `waitForEventResult` (the update ID is the turn's correlation token).
#
# + updateId - The turn's correlation token
# + eventName - The event channel the turn was sent on
public type PendingAgentUpdate record {|
    string updateId;
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
