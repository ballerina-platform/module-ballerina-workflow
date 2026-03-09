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
# + return - An error if initialization fails, otherwise nil
isolated function initWorkflowRuntime() returns error? {
    lock {
        if programStarted {
            return;
        }
        WorkflowConfig config = workflowConfig;
        if config is InMemoryConfig {
            // In-memory mode: use embedded test server, no external server needed
            check initInMemoryProgramNative();
            programStarted = true;
            return;
        }
        // Extract connection parameters based on deployment mode
        string url;
        string namespace;
        SchedulerConfig schedulerCfg;
        string apiKey = "";
        string mtlsCert = "";
        string mtlsKey = "";
        if config is CloudConfig {
            url = config.url;
            namespace = config.namespace;
            schedulerCfg = config.scheduler;
            apiKey = config.auth.apiKey ?: "";
            mtlsCert = config.auth.mtlsCert ?: "";
            mtlsKey = config.auth.mtlsKey ?: "";
        } else if config is SelfHostedConfig {
            url = config.url;
            namespace = config.namespace;
            schedulerCfg = config.scheduler;
            if config.auth is AuthConfig {
                AuthConfig auth = <AuthConfig>config.auth;
                apiKey = auth.apiKey ?: "";
                mtlsCert = auth.mtlsCert ?: "";
                mtlsKey = auth.mtlsKey ?: "";
            }
        } else {
            // LocalConfig
            LocalConfig localCfg = <LocalConfig>config;
            url = localCfg.url;
            namespace = localCfg.namespace;
            schedulerCfg = localCfg.scheduler;
        }
        check initProgramNative(url, namespace, schedulerCfg.taskQueue,
                schedulerCfg.maxConcurrentWorkflows, schedulerCfg.maxConcurrentActivities,
                apiKey, mtlsCert, mtlsKey,
                schedulerCfg.defaultActivityRetryPolicy);
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
