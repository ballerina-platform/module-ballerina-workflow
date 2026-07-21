/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native support for the object-model durable agent ({@code workflow:DurableAgent}).
 *
 * <p>Phase 1 (declaration surface): holds the per-agent declaration registry populated by the
 * compiler-plugin-generated module-init registration — the agent's model, system prompt, reasoning
 * limits, and capability declarations (activities, events, human tasks). The runner workflow
 * (next phase) resolves an agent's declaration by name from here and drives the existing ReAct
 * loop with it.
 *
 * <p>The typed read methods ({@code getResult}/{@code waitForResult}/...) are declared on the
 * Ballerina class as dependently-typed externals; until the runner lands they return a
 * descriptive error.
 *
 * @since 0.9.0
 */
public final class DurableAgentNative {

    private static final String NOT_SUPPORTED_YET =
            " is not supported yet: the object-model runner lands in a later phase";

    private DurableAgentNative() {
    }

    /**
     * A declared durable agent: identity, model, prompt, limits, and capability declarations.
     * Capability maps are insertion-ordered so tool advertisement order matches the declaration.
     */
    public static final class AgentDecl {
        private final String agentName;
        private final BObject model;
        private final Object systemPrompt;
        private final long maxIter;
        private final Map<String, ActivityDecl> activities = new LinkedHashMap<>();
        private final Map<String, EventDecl> events = new LinkedHashMap<>();
        private final Map<String, Object> humanTasks = new LinkedHashMap<>();

        AgentDecl(String agentName, BObject model, Object systemPrompt, long maxIter) {
            this.agentName = agentName;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.maxIter = maxIter;
        }

        public String agentName() {
            return agentName;
        }

        public BObject model() {
            return model;
        }

        public Object systemPrompt() {
            return systemPrompt;
        }

        public long maxIter() {
            return maxIter;
        }

        public Map<String, ActivityDecl> activities() {
            return activities;
        }

        public Map<String, EventDecl> events() {
            return events;
        }

        public Map<String, Object> humanTasks() {
            return humanTasks;
        }
    }

    /**
     * A declared activity capability.
     *
     * @param toolName the tool name advertised to the model
     * @param function the @workflow:Activity function
     * @param meta     the declaration metadata (description, bindings, gating, retry policy) as
     *                 a Ballerina value
     */
    public record ActivityDecl(String toolName, BFunctionPointer function, Object meta) { }

    /**
     * A declared event channel.
     *
     * @param name        the channel name
     * @param request     the request typedesc
     * @param response    the response typedesc, or null for one-way channels
     * @param cardinality "SINGLE_EVENT" or "MULTI_EVENT"
     */
    public record EventDecl(String name, BTypedesc request, Object response, String cardinality) { }

    /**
     * Declared durable agents keyed by agent name (the module-level variable name).
     */
    private static final Map<String, AgentDecl> AGENT_DECL_REGISTRY = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------------------------
    // Module-init registration (called by the compiler-plugin-generated code via wfInternal)
    // -----------------------------------------------------------------------------------------

    /**
     * Registers a durable agent declaration: its identity, model, system prompt, and reasoning
     * limit. The model is also published to the agent model registry under the agent's workflow
     * type so the existing {@code llmChat}/{@code generate} activities resolve it.
     *
     * @param agentName    the agent name (module-level variable name)
     * @param model        the ai:ModelProvider
     * @param systemPrompt the system prompt value (role + instructions)
     * @param maxIter      the per-turn reasoning iteration cap
     * @return true on success, or a BError when the name is already registered
     */
    public static Object registerDurableAgentDecl(BString agentName, BObject model, Object systemPrompt,
                                                  long maxIter) {
        String name = agentName.getValue();
        AgentDecl existing = AGENT_DECL_REGISTRY.putIfAbsent(name,
                new AgentDecl(name, model, systemPrompt, maxIter));
        if (existing != null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "A durable agent named '" + name + "' is already registered"));
        }
        WorkflowWorkerNative.putAgentModel(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + name, model);
        return true;
    }

    /**
     * Registers an activity capability declaration of a durable agent.
     *
     * @param agentName the agent name
     * @param toolName  the tool name advertised to the model
     * @param function  the @workflow:Activity function
     * @param meta      declaration metadata (description, bindings, gating, retry policy)
     * @return true on success, or a BError when the agent is unknown
     */
    public static Object registerDurableAgentActivity(BString agentName, BString toolName,
                                                      BFunctionPointer function, Object meta) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        decl.activities().put(toolName.getValue(),
                new ActivityDecl(toolName.getValue(), function, meta));
        return true;
    }

    /**
     * Registers an event channel declaration of a durable agent.
     *
     * @param agentName   the agent name
     * @param eventName   the channel name
     * @param request     the request typedesc
     * @param response    the response typedesc, or nil for one-way channels
     * @param cardinality "SINGLE_EVENT" or "MULTI_EVENT"
     * @return true on success, or a BError when the agent is unknown
     */
    public static Object registerDurableAgentEvent(BString agentName, BString eventName, BTypedesc request,
                                                   Object response, BString cardinality) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        decl.events().put(eventName.getValue(),
                new EventDecl(eventName.getValue(), request, response, cardinality.getValue()));
        return true;
    }

    /**
     * Registers a human task capability declaration of a durable agent.
     *
     * @param agentName the agent name
     * @param taskName  the task name
     * @param meta      declaration metadata (roles, result type, title, description, timeout)
     * @return true on success, or a BError when the agent is unknown
     */
    public static Object registerDurableAgentHumanTask(BString agentName, BString taskName, Object meta) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        decl.humanTasks().put(taskName.getValue(), meta);
        return true;
    }

    /**
     * Resolves a declared durable agent by name, or null when not registered. Used by the runner
     * workflow (next phase) and tests.
     *
     * @param agentName the agent name
     * @return the declaration, or null
     */
    public static AgentDecl getAgentDecl(String agentName) {
        return AGENT_DECL_REGISTRY.get(agentName);
    }

    // -----------------------------------------------------------------------------------------
    // Typed read methods (dependently-typed externals on workflow:DurableAgent)
    // -----------------------------------------------------------------------------------------

    /**
     * Placeholder for {@code DurableAgent.getResult} until the object-model runner lands.
     *
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param typedesc   the expected result type descriptor
     * @return a BError describing that the operation is not supported yet
     */
    public static Object getResult(BObject self, BString instanceId, BTypedesc typedesc) {
        return notSupportedYet("getResult");
    }

    /**
     * Placeholder for {@code DurableAgent.getEventResult} until the object-model runner lands.
     *
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendEvent correlation token
     * @param typedesc   the expected response type descriptor
     * @return a BError describing that the operation is not supported yet
     */
    public static Object getEventResult(BObject self, BString instanceId, BString token, BTypedesc typedesc) {
        return notSupportedYet("getEventResult");
    }

    /**
     * Placeholder for {@code DurableAgent.waitForResult} until the object-model runner lands.
     *
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param typedesc   the expected result type descriptor
     * @return a BError describing that the operation is not supported yet
     */
    public static Object waitForResult(BObject self, BString instanceId, BTypedesc typedesc) {
        return notSupportedYet("waitForResult");
    }

    /**
     * Placeholder for {@code DurableAgent.waitForEventResult} until the object-model runner lands.
     *
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendEvent correlation token
     * @param typedesc   the expected response type descriptor
     * @return a BError describing that the operation is not supported yet
     */
    public static Object waitForEventResult(BObject self, BString instanceId, BString token,
                                            BTypedesc typedesc) {
        return notSupportedYet("waitForEventResult");
    }

    private static Object notSupportedYet(String method) {
        return ErrorCreator.createError(StringUtils.fromString(
                "workflow:DurableAgent." + method + NOT_SUPPORTED_YET));
    }

    private static Object unknownAgentError(String agentName) {
        return ErrorCreator.createError(StringUtils.fromString(
                "Unknown durable agent '" + agentName + "': the agent declaration was not registered"));
    }
}
