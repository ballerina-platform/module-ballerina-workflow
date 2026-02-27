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

# Configuration for connecting to a local development server.
# Defaults are tuned for a locally running workflow server (e.g., `temporal server start-dev`).
#
# + mode - Deployment mode identifier (always "LOCAL")
# + url - Server URL (default: "localhost:7233")
# + namespace - Workflow namespace (default: "default")
# + params - Worker configuration parameters
public type LocalConfig record {|
    "LOCAL" mode = "LOCAL";
    string url = "localhost:7233";
    string namespace = "default";
    WorkerConfig params = {};
|};

# Configuration for connecting to a managed cloud deployment.
# All connection and authentication parameters are mandatory.
#
# + mode - Deployment mode identifier (always "CLOUD")
# + url - Cloud server URL (e.g., "<namespace>.<account>.tmprl.cloud:7233")
# + namespace - Cloud namespace (e.g., "<namespace>.<account>")
# + auth - Authentication configuration (required for cloud)
# + params - Worker configuration parameters
public type CloudConfig record {|
    "CLOUD" mode;
    string url;
    string namespace;
    AuthConfig auth;
    WorkerConfig params = {};
|};

# Configuration for connecting to a self-hosted server deployment.
# Supports optional authentication for secured installations.
#
# + mode - Deployment mode identifier (always "SELF_HOSTED")
# + url - Server URL (e.g., "temporal.mycompany.com:7233")
# + namespace - Workflow namespace (default: "default")
# + auth - Optional authentication configuration
# + params - Worker configuration parameters
public type SelfHostedConfig record {|
    "SELF_HOSTED" mode;
    string url;
    string namespace = "default";
    AuthConfig? auth = ();
    WorkerConfig params = {};
|};

# Configuration for an in-memory workflow engine.
# No external server is required. Workflows are not persisted and will be lost on restart.
# Signal-based communication via `sendData` is supported when the workflow ID is known.
#
# + mode - Deployment mode identifier (always "IN_MEMORY")
public type InMemoryConfig record {|
    "IN_MEMORY" mode = "IN_MEMORY";
|};

# Workflow module configuration.
# A union of deployment-specific configuration records, discriminated by the `mode` field.
#
# Supported modes:
# - `LOCAL` - Local development server (default)
# - `CLOUD` - Managed cloud deployment with mandatory authentication
# - `SELF_HOSTED` - Self-hosted server with optional authentication
# - `IN_MEMORY` - Lightweight in-memory engine (no persistence)
public type WorkflowConfig LocalConfig|CloudConfig|SelfHostedConfig|InMemoryConfig;

# Worker configuration parameters.
#
# + taskQueue - The task queue for workflow execution (default: "BALLERINA_WORKFLOW_TASK_QUEUE")
# + maxConcurrentWorkflows - Maximum number of concurrent workflow executions (default: 100)
# + maxConcurrentActivities - Maximum number of concurrent activity executions (default: 100)
public type WorkerConfig record {|
    string taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";
    int maxConcurrentWorkflows = 100;
    int maxConcurrentActivities = 100;
|};

# Authentication configuration for workflow server connections.
#
# Supports API key authentication and mutual TLS (mTLS).
# For cloud deployments, provide either an API key or mTLS certificate/key pair.
# For self-hosted deployments, configure based on your server's security setup.
#
# + apiKey - API key for bearer token authentication
# + mtlsCert - Path to the mTLS client certificate file
# + mtlsKey - Path to the mTLS client private key file
public type AuthConfig record {|
    string? apiKey = ();
    string? mtlsCert = ();
    string? mtlsKey = ();
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

# Information about an activity invocation (for testing/introspection).
# + activityName - The name of the activity that was invoked
# + input - The arguments passed to the activity
# + output - The result returned by the activity (nil if not yet completed or failed)
# + status - The status of the activity execution ("COMPLETED", "FAILED", "RUNNING", "PENDING")
# + errorMessage - Error message if the activity failed
public type ActivityInvocation record {
    string activityName;
    anydata[] input;
    anydata? output;
    string status;
    string? errorMessage;
};

# Information about a workflow execution (for testing/introspection).
# + workflowId - The unique identifier for the workflow instance
# + workflowType - The type (process name) of the workflow
# + status - The execution status ("RUNNING", "COMPLETED", "FAILED", "CANCELED", "TERMINATED")
# + result - The workflow result if completed successfully
# + errorMessage - Error message if the workflow failed
# + activityInvocations - List of activities invoked by this workflow
public type WorkflowExecutionInfo record {
    string workflowId;
    string workflowType;
    string status;
    anydata? result;
    string? errorMessage;
    ActivityInvocation[] activityInvocations;
};

