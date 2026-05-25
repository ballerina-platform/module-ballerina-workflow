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
