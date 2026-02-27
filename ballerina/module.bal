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

# Flag indicating whether the singleton worker has been started.
isolated boolean workerStarted = false;

# Module initialization function.
# This captures the module reference and initializes the singleton worker.
#
# + return - An error if initialization fails, otherwise nil
function init() returns error? {
    initModule();
    check initSingletonWorker();
}

listener WorkflowListener _workflowListener = new;

class WorkflowListener {

    public function attach(service object {} svc, string attachPoint) returns error? {
    }

    public function detach(service object {} svc) returns error? {
    }

    public function 'start() returns error? {
        check startWorker();
    }

    public function gracefulStop() returns error? {
        check stopWorker();
    }

    public function immediateStop() returns error? {
        check stopWorker();
    }
}

# Initializes the workflow module.
# This captures the module reference for creating Ballerina record values in native code.
function initModule() = @java:Method {
    'class: "io.ballerina.stdlib.workflow.ModuleUtils",
    name: "setModule"
} external;

# Initializes the singleton workflow worker with the configured settings.
# This creates the workflow client and worker that will be shared across
# all workflow executions in this runtime.
#
# + return - An error if initialization fails, otherwise nil
isolated function initSingletonWorker() returns error? {
    lock {
        if workerStarted {
            return;
        }
        WorkflowConfig config = workflowConfig;
        if config is InMemoryConfig {
            // In-memory mode: use embedded test server, no external server needed
            check initInMemoryWorkerNative();
            workerStarted = true;
            return;
        }
        // Extract connection parameters based on deployment mode
        string url;
        string namespace;
        WorkerConfig workerCfg;
        string apiKey = "";
        string mtlsCert = "";
        string mtlsKey = "";
        if config is CloudConfig {
            url = config.url;
            namespace = config.namespace;
            workerCfg = config.params;
            apiKey = config.auth.apiKey ?: "";
            mtlsCert = config.auth.mtlsCert ?: "";
            mtlsKey = config.auth.mtlsKey ?: "";
        } else if config is SelfHostedConfig {
            url = config.url;
            namespace = config.namespace;
            workerCfg = config.params;
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
            workerCfg = localCfg.params;
        }
        check initWorkerNative(url, namespace, workerCfg.taskQueue,
                workerCfg.maxConcurrentWorkflows, workerCfg.maxConcurrentActivities,
                apiKey, mtlsCert, mtlsKey);
        workerStarted = true;
    }
}

# Native function to initialize the singleton worker.
#
# + url - The workflow server URL
# + namespace - The workflow namespace
# + taskQueue - The task queue name
# + maxConcurrentWorkflows - Maximum concurrent workflow executions
# + maxConcurrentActivities - Maximum concurrent activity executions
# + apiKey - API key for authentication (empty string if not used)
# + mtlsCert - Path to mTLS certificate file (empty string if not used)
# + mtlsKey - Path to mTLS private key file (empty string if not used)
# + return - An error if initialization fails, otherwise nil
isolated function initWorkerNative(
        string url,
        string namespace,
        string taskQueue,
        int maxConcurrentWorkflows,
        int maxConcurrentActivities,
        string apiKey,
        string mtlsCert,
        string mtlsKey
) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "initSingletonWorker"
} external;

# Initializes an in-memory workflow worker using an embedded test server.
# No external server connection is required. Workflows are not persisted
# and signal-based communication is not supported.
#
# + return - An error if initialization fails, otherwise nil
isolated function initInMemoryWorkerNative() returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "initInMemoryWorker"
} external;
