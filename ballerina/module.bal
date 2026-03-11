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

# Flag indicating whether the singleton program has been started.
isolated boolean programStarted = false;

# Module initialization function.
# This captures the module reference and initializes the program.
#
# + return - An error if initialization fails, otherwise nil
function init() returns error? {
    initModule();
    check initWorkflowRuntime();
}

listener WorkflowListener _workflowListener = new;

class WorkflowListener {

    public function attach(service object {} svc, string attachPoint) returns error? {
    }

    public function detach(service object {} svc) returns error? {
    }

    public function 'start() returns error? {
        check startWorkflowRuntime();
    }

    public function gracefulStop() returns error? {
        check stopWorkflowRuntime();
    }

    public function immediateStop() returns error? {
        check stopWorkflowRuntime();
    }
}

# Initializes the workflow module.
# This captures the module reference for creating Ballerina record values in native code.
function initModule() = @java:Method {
    'class: "io.ballerina.stdlib.workflow.ModuleUtils",
    name: "setModule"
} external;

# Initializes the workflow program with the configured settings.
# This creates the workflow client and program that will be shared across
# all workflow executions in this runtime.
#
# Performs mode-based validation:
# - IN_MEMORY: No server connection needed; all other config fields are ignored.
# - LOCAL: Connects to a local dev server; auth fields are ignored.
# - CLOUD: Requires url, namespace, and authentication (apiKey or mTLS).
# - SELF_HOSTED: Requires url; authentication is optional.
#
# + return - An error if initialization or validation fails, otherwise nil
isolated function initWorkflowRuntime() returns error? {
    lock {
        if programStarted {
            return;
        }
        Mode currentMode = mode;
        if currentMode == IN_MEMORY {
            check initInMemoryProgramNative();
            programStarted = true;
            return;
        }

        // Validate url for CLOUD and SELF_HOSTED (must be explicitly provided)
        string currentUrl = url;
        if currentUrl == "" {
            return error("'url' is required for " + currentMode + " mode");
        }

        // Validate authentication for CLOUD (must have apiKey or mTLS)
        string? currentApiKey = authApiKey;
        string? currentMtlsCert = authMtlsCert;
        string? currentMtlsKey = authMtlsKey;
        if currentMode == CLOUD {
            if currentApiKey is () && (currentMtlsCert is () || currentMtlsKey is ()) {
                return error("CLOUD mode requires authentication: "
                        + "provide either 'authApiKey' or both 'authMtlsCert' and 'authMtlsKey'");
            }
        }

        // Validate mTLS pair completeness (both or neither)
        if (currentMtlsCert is string && currentMtlsKey is ())
                || (currentMtlsCert is () && currentMtlsKey is string) {
            return error("Both 'authMtlsCert' and 'authMtlsKey' must be provided together");
        }

        // Validate scheduler fields
        int concurrentWorkflows = maxConcurrentWorkflows;
        int concurrentActivities = maxConcurrentActivities;
        if concurrentWorkflows <= 0 {
            return error("'maxConcurrentWorkflows' must be a positive integer, got "
                    + concurrentWorkflows.toString());
        }
        if concurrentActivities <= 0 {
            return error("'maxConcurrentActivities' must be a positive integer, got "
                    + concurrentActivities.toString());
        }

        // Validate default retry policy fields
        int retryInitialInterval = activityRetryInitialInterval;
        decimal retryBackoff = activityRetryBackoffCoefficient;
        int retryMaxInterval = activityRetryMaximumInterval;
        int retryMaxAttempts = activityRetryMaximumAttempts;
        if retryInitialInterval <= 0 {
            return error("'activityRetryInitialInterval' must be a positive integer, got "
                    + retryInitialInterval.toString());
        }
        if retryBackoff < 1.0d {
            return error("'activityRetryBackoffCoefficient' must be >= 1.0, got "
                    + retryBackoff.toString());
        }
        if retryMaxInterval < 0 {
            return error("'activityRetryMaximumInterval' must be a non-negative integer, got "
                    + retryMaxInterval.toString());
        }
        if retryMaxAttempts < 0 {
            return error("'activityRetryMaximumAttempts' must be a non-negative integer, got "
                    + retryMaxAttempts.toString());
        }

        // Build the ActivityRetryPolicy record for the native layer
        ActivityRetryPolicy defaultRetryPolicy;
        if retryMaxInterval > 0 {
            defaultRetryPolicy = {
                initialIntervalInSeconds: retryInitialInterval,
                backoffCoefficient: retryBackoff,
                maximumIntervalInSeconds: retryMaxInterval,
                maximumAttempts: retryMaxAttempts
            };
        } else {
            defaultRetryPolicy = {
                initialIntervalInSeconds: retryInitialInterval,
                backoffCoefficient: retryBackoff,
                maximumAttempts: retryMaxAttempts
            };
        }

        // For LOCAL mode, ignore auth fields; coalesce nil → "" for native layer
        string effectiveApiKey = currentMode == LOCAL ? "" : (currentApiKey ?: "");
        string effectiveMtlsCert = currentMode == LOCAL ? "" : (currentMtlsCert ?: "");
        string effectiveMtlsKey = currentMode == LOCAL ? "" : (currentMtlsKey ?: "");
        string effectiveCaCert = currentMode == LOCAL ? "" : (authCaCert ?: "");

        check initProgramNative(currentUrl, namespace, taskQueue,
                concurrentWorkflows, concurrentActivities,
                effectiveApiKey, effectiveMtlsCert, effectiveMtlsKey, effectiveCaCert,
                defaultRetryPolicy);
        programStarted = true;
    }
}

# Native function to initialize the singleton program.
#
# + url - The workflow server URL
# + namespace - The workflow namespace
# + taskQueue - The task queue name
# + maxConcurrentWorkflows - Maximum concurrent workflow executions
# + maxConcurrentActivities - Maximum concurrent activity executions
# + apiKey - API key for authentication (empty string if not used)
# + mtlsCert - Path to mTLS certificate file (empty string if not used)
# + mtlsKey - Path to mTLS private key file (empty string if not used)
# + caCert - Path to CA certificate for server trust (empty string to use JVM default trust store)
# + defaultRetryPolicy - Default activity retry policy
# + return - An error if initialization fails, otherwise nil
isolated function initProgramNative(
        string url,
        string namespace,
        string taskQueue,
        int maxConcurrentWorkflows,
        int maxConcurrentActivities,
        string apiKey,
        string mtlsCert,
        string mtlsKey,
        string caCert,
        ActivityRetryPolicy defaultRetryPolicy
) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "initSingletonWorker"
} external;

# Initializes an in-memory workflow program using an embedded test server.
# No external server connection is required. Workflows are not persisted
# and are lost on restart. Signal-based communication is supported when
# the workflow ID is known.
#
# + return - An error if initialization fails, otherwise nil
isolated function initInMemoryProgramNative() returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "initInMemoryWorker"
} external;
