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

function 'start() returns error? {
    check startWorker();
}

function stop() returns error? {
    check stopWorker();
}

# Initializes the workflow module.
# This captures the module reference for creating Ballerina record values in native code.
function initModule() = @java:Method {
    'class: "io.ballerina.stdlib.workflow.ModuleUtils",
    name: "setModule"
} external;

# Initializes the singleton workflow worker with the configured settings.
# This creates the Temporal client and worker that will be shared across
# all workflow executions in this runtime.
#
# + return - An error if initialization fails, otherwise nil
isolated function initSingletonWorker() returns error? {
    lock {
        if workerStarted {
            return;
        }
        check initWorkerNative(
                workflowConfig.url,
                workflowConfig.namespace,
                workflowConfig.params.taskQueue,
                workflowConfig.params.maxConcurrentWorkflows,
                workflowConfig.params.maxConcurrentActivities
        );
        workerStarted = true;
    }
}

# Native function to initialize the singleton worker.
#
# + url - The Temporal server URL
# + namespace - The Temporal namespace
# + taskQueue - The task queue name
# + maxConcurrentWorkflows - Maximum concurrent workflow executions
# + maxConcurrentActivities - Maximum concurrent activity executions
# + return - An error if initialization fails, otherwise nil
isolated function initWorkerNative(
        string url,
        string namespace,
        string taskQueue,
        int maxConcurrentWorkflows,
        int maxConcurrentActivities
) returns error? = @java:Method {
    'class: "io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative",
    name: "initSingletonWorker"
} external;
