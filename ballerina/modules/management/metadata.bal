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

// ================================================================================
// WORKFLOW METADATA
// ================================================================================
// A startup-complete description of everything this program registered with the
// workflow runtime: definitions, human tasks, activities, and durable agents,
// with their JSON schemas. A control plane publishes this document so workflow
// tooling can render forms and launchers without calling into a live instance. All schemas are JSON Schema documents
// serialized as strings — the same convention as `WorkflowDefinition.inputSchema`
// and `HumanTaskInfo.formSchema`.

# Describes one registered workflow definition for metadata publishing.
#
# + workflowType - Registered workflow function (or durable agent) name
# + kind - `WORKFLOW` for a `@workflow:Workflow` function, `AGENT` for a durable agent
# + inputSchema - JSON Schema of the workflow's input type as a string, or `()` when
#                 the workflow takes no data input
public type WorkflowDefinitionMeta record {|
    string workflowType;
    string kind;
    string? inputSchema;
|};

# Describes one human task type for metadata publishing.
#
# + name - Qualified task name (`<workflowType>.<taskName>`)
# + resultSchema - JSON Schema of the task's completion form as a string, or `()` when
#                  the result type is not yet known in this process (it is registered at
#                  module init when the compiler plugin can determine it statically, and
#                  lazily at first execution otherwise)
public type HumanTaskMeta record {|
    string name;
    string? resultSchema;
|};

# Describes one registered activity for metadata publishing. The input schema backs
# review-activity `proceed-with-input` forms.
#
# + workflowType - The workflow definition the activity is registered under
# + name - The activity function name
# + inputSchema - JSON Schema of the activity's data parameters as a string, or `()`
#                 when it cannot be derived
public type ActivityMeta record {|
    string workflowType;
    string name;
    string? inputSchema;
|};

# Describes one declared durable agent for metadata publishing.
#
# + name - The agent name (its module-level variable name)
# + events - Declared event channel names
# + tools - Tool names advertised to the model (declared activities and tools)
# + humanTasks - Qualified names (`<agent>.<task>`) of the agent's declared human tasks
public type AgentMeta record {|
    string name;
    string[] events;
    string[] tools;
    string[] humanTasks;
|};

# The workflow metadata document: everything the program registered with the
# workflow runtime, complete at module init.
#
# + metadataVersion - Version of this document's shape (currently `"1.0"`)
# + definitions - Registered workflow definitions
# + humanTasks - Human task types with completion-form schemas
# + activities - Registered activities with input schemas
# + reviewActions - The static review-activity decision vocabulary
# + agents - Declared durable agents
# + descriptor - The build-time Workflow Definition Descriptor (`workflow.def.json`) packed
#                into the running program by the compiler plugin: the canonical, versioned,
#                checksummed description of the same structures with embedded JSON Schemas.
#                `()` when the program was built without one (older plugin, or a run that
#                never produced an executable JAR, such as `bal test`)
public type WorkflowMetadata record {|
    string metadataVersion;
    # The Temporal task queue this program's worker serves. Integrations in one project share a
    # namespace, so this is what scopes a control plane's listings to one integration. Nil until
    # the worker has registered.
    string? taskQueue = ();
    WorkflowDefinitionMeta[] definitions;
    HumanTaskMeta[] humanTasks;
    ActivityMeta[] activities;
    string[] reviewActions;
    AgentMeta[] agents;
    json? descriptor = ();
|};

# Returns the workflow metadata document for this program: registered workflow
# definitions, human tasks, activities, and durable agents, with their JSON schemas.
# The document is complete at module init — before any workflow has executed — so it
# is safe to read once at startup and publish to a control plane.
#
# ```ballerina
# import ballerina/workflow.management;
#
# management:WorkflowMetadata meta = check management:getWorkflowMetadata();
# ```
#
# + return - The metadata document, or an error
public isolated function getWorkflowMetadata() returns WorkflowMetadata|error {
    json raw = check getWorkflowMetadataJson();
    return raw.cloneWithType();
}

isolated function getWorkflowMetadataJson() returns json|error = @java:Method {
    'class: "io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowMetadataNative",
    name: "getWorkflowMetadata"
} external;
