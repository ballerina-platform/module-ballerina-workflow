/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.compiler;

/**
 * Constants used by the workflow compiler plugin.
 *
 * @since 0.1.0
 */
public final class WorkflowConstants {

    private WorkflowConstants() {
        // Private constructor to prevent instantiation
    }

    // Package information
    public static final String PACKAGE_ORG = "ballerina";
    public static final String PACKAGE_NAME = "workflow";

    // Submodule names and aliases
    public static final String INTERNAL_MODULE_NAME = "internal";
    public static final String INTERNAL_MODULE_ALIAS = "wfInternal";

    // Annotation names
    public static final String PROCESS_ANNOTATION = "Workflow";
    public static final String ACTIVITY_ANNOTATION = "Activity";

    /** The ai module providing the ModelProvider type used by agents. */
    public static final String AI_PACKAGE_ORG = "ballerina";
    public static final String AI_PACKAGE_NAME = "ai";
    public static final String MODEL_PROVIDER_TYPE = "ModelProvider";

    // ── workflow.internal functions the source modifier emits ────────────────
    /** Hands the build-time descriptor to the runtime as data. */
    public static final String REGISTER_DESCRIPTOR_FUNCTION = "registerWorkflowDescriptor";
    /** Registers a module-level final client so activities can resolve it by name. */
    public static final String REGISTER_CONNECTION_FUNCTION = "registerConnection";
    /** Starts the worker, after everything else has registered. */
    public static final String START_RUNTIME_FUNCTION = "startWorkflowRuntime";
    /** Registers a durable agent's identity and model. */
    public static final String REGISTER_AGENT_DECL_FUNCTION = "registerDurableAgentDecl";
    /** Registers an agent's activity capability. */
    public static final String REGISTER_AGENT_ACTIVITY_FUNCTION = "registerDurableAgentActivity";
    /** Registers an agent's AI tool. */
    public static final String REGISTER_AGENT_TOOL_FUNCTION = "registerDurableAgentTool";
    /** Registers an agent's event channel. */
    public static final String REGISTER_AGENT_EVENT_FUNCTION = "registerDurableAgentEvent";
    /** Registers an agent's human task. */
    public static final String REGISTER_AGENT_HUMAN_TASK_FUNCTION = "registerDurableAgentHumanTask";
    /** Registers a peer agent advertised as a delegable tool. */
    public static final String REGISTER_AGENT_PEER_FUNCTION = "registerDurableAgentPeer";
    /** Registers the shared runner as an agent's workflow. */
    public static final String REGISTER_AGENT_RUNNER_FUNCTION = "registerDurableAgentRunner";

    // Function names
    public static final String CALL_ACTIVITY_FUNCTION = "callActivity";
    public static final String CALL_HUMAN_TASK_METHOD = "awaitHumanTask";

    // Type names
    public static final String CONTEXT_TYPE = "Context";
    /** The object type a durable agent declaration is assigned to. */
    public static final String DURABLE_AGENT_TYPE = "DurableAgent";
    /** The retry-policy type that asks for a human review; its value is the reviewer roles. */
    public static final String HUMAN_REVIEW_TYPE = "HumanReview";

    // ── Names read from user source ───────────────────────────────────────────
    // Argument names on the workflow API, and the fields of DurableAgentConfig and
    // its capability records. These are the *Ballerina API* vocabulary, matched
    // against what the user wrote; where a name coincides with a descriptor field
    // (`name`, `events`, …) the two are still distinct concepts, so the descriptor
    // vocabulary keeps its own constants in
    // {@code descriptor.DescriptorFields}.

    /** {@code awaitHumanTask(taskName = ...)}. */
    public static final String ARG_TASK_NAME = "taskName";
    /** {@code callActivity(retryPolicy = ...)}. */
    public static final String ARG_RETRY_POLICY = "retryPolicy";
    /** {@code callActivity(stepId = ...)} — the compiler-injected call-site identity. */
    public static final String ARG_STEP_ID = "stepId";

    /** {@code DurableAgentConfig.model}. */
    public static final String AGENT_CONFIG_MODEL = "model";
    /** {@code DurableAgentConfig.inputType}. */
    public static final String AGENT_CONFIG_INPUT_TYPE = "inputType";
    /** {@code DurableAgentConfig.resultType}, also {@code HumanTaskDecl.resultType}. */
    public static final String AGENT_CONFIG_RESULT_TYPE = "resultType";
    /** {@code DurableAgentConfig.activities}. */
    public static final String AGENT_CONFIG_ACTIVITIES = "activities";
    /** {@code DurableAgentConfig.tools}. */
    public static final String AGENT_CONFIG_TOOLS = "tools";
    /** {@code DurableAgentConfig.events}. */
    public static final String AGENT_CONFIG_EVENTS = "events";
    /** {@code DurableAgentConfig.humanTasks}. */
    public static final String AGENT_CONFIG_HUMAN_TASKS = "humanTasks";
    /** {@code DurableAgentConfig.peers}. */
    public static final String AGENT_CONFIG_PEERS = "peers";

    /** The {@code name} field shared by the capability records. */
    public static final String DECL_NAME = "name";
    /** {@code ActivityDecl.activity} — the activity function. */
    public static final String DECL_ACTIVITY = "activity";
    /** {@code ActivityDecl.bindings} — arguments fixed at registration. */
    public static final String DECL_BINDINGS = "bindings";
    /** {@code ToolDecl.tool} — the tool reference. */
    public static final String DECL_TOOL = "tool";
    /** {@code EventDecl.request}. */
    public static final String DECL_REQUEST = "request";
    /** {@code EventDecl.response}. */
    public static final String DECL_RESPONSE = "response";
    /** {@code EventDecl.cardinality}. */
    public static final String DECL_CARDINALITY = "cardinality";
    /** The {@code SINGLE_EVENT} cardinality, as written in user source. */
    public static final String CARDINALITY_SINGLE_EVENT = "SINGLE";

    // User data keys
    public static final String MODIFIER_CONTEXT_MAP = "workflow.modifier.context.map";
    public static final String IS_ANALYSIS_COMPLETED = "workflow.analysis.completed";
    /** Map of module-id plus source-set -> visible module-level final client variable names. */
    public static final String CONNECTION_VAR_NAMES = "workflow.connection.var.names";

    // Function names for validation
    public static final String SEND_DATA_FUNCTION = "sendData";
    public static final String RUN_FUNCTION = "run";
    public static final String AWAIT_METHOD = "await";
    public static final String RUN_CHILD_WORKFLOW_METHOD = "runChildWorkflow";
    public static final String CALL_WORKFLOW_METHOD = "callWorkflow";
    public static final String SEND_DATA_TO_CHILD_WORKFLOW_METHOD = "sendDataToChildWorkflow";
    public static final String SLEEP_METHOD = "sleep";
    /** {@code workflow:getWorkflowResult} — awaits another workflow's result. */
    public static final String GET_WORKFLOW_RESULT_FUNCTION = "getWorkflowResult";
}
