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
# - `LOCAL` — Connects to a locally running server (e.g., `temporal server start-dev`).
#   This is the default mode.
# - `CLOUD` — Managed cloud deployment. Requires `url`, `namespace`, and authentication
#   (`authApiKey` or mTLS certificate/key pair).
# - `SELF_HOSTED` — Self-hosted server. Requires `url`; authentication is optional.
# - `IN_MEMORY` — Lightweight in-memory engine with no external server. Workflows are
#   not persisted and will be lost on restart. All other connection/scheduler
#   fields are ignored in this mode.
configurable Mode mode = LOCAL;

# Server URL for the workflow runtime.
# For LOCAL mode, defaults to "localhost:7233".
# For CLOUD mode, use the cloud endpoint (e.g., "<namespace>.<account>.tmprl.cloud:7233").
# For SELF_HOSTED mode, use your server address (e.g., "temporal.mycompany.com:7233").
# Ignored in IN_MEMORY mode.
configurable string url = "localhost:7233";

# Workflow namespace.
# For LOCAL and SELF_HOSTED modes, defaults to "default".
# For CLOUD mode, use your cloud namespace (e.g., "<namespace>.<account>").
# Ignored in IN_MEMORY mode.
configurable string namespace = "default";

# API key for bearer-token authentication.
# Required for CLOUD mode (unless mTLS is configured). Optional for SELF_HOSTED mode.
# Ignored in LOCAL and IN_MEMORY modes.
configurable string? authApiKey = ();

# Path to the mTLS client certificate file (PEM format).
# Used for CLOUD or SELF_HOSTED modes with mutual TLS authentication.
# Must be provided together with `authMtlsKey`.
# Ignored in LOCAL and IN_MEMORY modes.
configurable string? authMtlsCert = ();

# Path to the mTLS client private key file (PEM format).
# Used together with `authMtlsCert` for mutual TLS authentication.
# Ignored in LOCAL and IN_MEMORY modes.
configurable string? authMtlsKey = ();

# Path to the CA certificate file (PEM format) used to verify the server's TLS certificate.
# Set this when the Temporal server uses a certificate from a private or self-signed CA
# that is not in the JVM's default trust store.
# Applicable to CLOUD mode (API key over TLS) and SELF_HOSTED mode (TLS or mTLS).
# Ignored in LOCAL and IN_MEMORY modes.
configurable string? authCaCert = ();

# Task queue name for workflow and activity polling.
# Each workflow program should use a unique task queue to avoid conflicts.
# Ignored in IN_MEMORY mode.
configurable string taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";

# Maximum number of concurrent workflow task executions.
# Controls how many workflow tasks the workflow scheduler processes in parallel.
# Must be a positive integer. Ignored in IN_MEMORY mode.
configurable int maxConcurrentWorkflows = 100;

# Maximum number of concurrent activity executions.
# Controls how many activities the workflow scheduler processes in parallel.
# Must be a positive integer. Ignored in IN_MEMORY mode.
configurable int maxConcurrentActivities = 100;

# Initial delay (in seconds) before the first activity retry attempt.
# Part of the default retry policy applied to all activities unless
# overridden per-call via `ActivityOptions.retryPolicy`.
# Must be a positive integer.
configurable int activityRetryInitialInterval = 1;

# Backoff multiplier applied to the activity retry interval after each attempt.
# For example, with an initial interval of 1s and coefficient of 2.0,
# retries occur at 1s, 2s, 4s, 8s, etc.
# Must be >= 1.0.
configurable decimal activityRetryBackoffCoefficient = 2.0;

# Maximum delay (in seconds) between activity retries.
# Caps the exponential backoff to prevent excessively long waits.
# When not set (0), there is no upper limit on the retry interval.
# Must be a positive integer when set (> 0).
configurable int activityRetryMaximumInterval = 0;

# Maximum number of activity retry attempts.
# - 1 means no retries (execute once only, the default).
# - 0 means unlimited retries.
# - Any positive value sets the retry cap.
configurable int activityRetryMaximumAttempts = 1;
