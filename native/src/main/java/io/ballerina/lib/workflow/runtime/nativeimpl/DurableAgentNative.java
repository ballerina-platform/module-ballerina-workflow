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

import io.ballerina.lib.workflow.ModuleUtils;
import io.ballerina.lib.workflow.context.WorkflowContextNative;
import io.ballerina.lib.workflow.runtime.WorkflowRuntime;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowLocal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private static final String AGENT_NAME_FIELD = "agentName";
    private static final String AGENT_BUSY_ERROR = "AgentBusyError";
    private static final String RUN_SPEC_RECORD = "DurableAgentRunSpec";
    private static final String ACTIVITY_SPEC_RECORD = "DurableAgentActivitySpec";
    private static final String TOOL_SPEC_RECORD = "DurableAgentToolSpec";
    private static final String EVENT_SPEC_RECORD = "DurableAgentEventSpec";
    private static final String HUMAN_TASK_SPEC_RECORD = "DurableAgentHumanTaskSpec";
    private static final String PEER_SPEC_RECORD = "DurableAgentPeerSpec";

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
        // The agent's workflow input typedesc: string (the query text is the input, the
        // default), another data type (structured run input), or null (no-input agent).
        private final BTypedesc inputType;
        private final BTypedesc resultType;
        private final Map<String, ActivityDecl> activities = new LinkedHashMap<>();
        private final Map<String, ToolDeclEntry> tools = new LinkedHashMap<>();
        private final Map<String, EventDecl> events = new LinkedHashMap<>();
        private final Map<String, Object> humanTasks = new LinkedHashMap<>();
        private final Map<String, PeerDecl> peers = new LinkedHashMap<>();
        // Every capability name claimed on this agent, mapped to the kind that claimed it.
        // Capabilities are keyed by name in their own maps, so an unchecked duplicate would
        // silently replace the earlier declaration instead of failing.
        private final Map<String, String> capabilityKinds = new LinkedHashMap<>();

        /**
         * Claims {@code name} in this agent's flat capability namespace.
         *
         * @param name the capability name
         * @param kind the capability kind claiming it, for the error message
         * @return the kind already holding the name, or null when the claim succeeds
         */
        String claimCapabilityName(String name, String kind) {
            return capabilityKinds.putIfAbsent(name, kind);
        }

        AgentDecl(String agentName, BObject model, Object systemPrompt, long maxIter, BTypedesc inputType,
                  BTypedesc resultType) {
            this.agentName = agentName;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.maxIter = maxIter;
            this.inputType = inputType;
            this.resultType = resultType;
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

        /**
         * The agent's workflow input typedesc, or {@code null} for a no-input agent.
         *
         * @return the declared input typedesc
         */
        public BTypedesc inputType() {
            return inputType;
        }

        public BTypedesc resultType() {
            return resultType;
        }

        public Map<String, ActivityDecl> activities() {
            return activities;
        }

        public Map<String, ToolDeclEntry> tools() {
            return tools;
        }

        public Map<String, EventDecl> events() {
            return events;
        }

        public Map<String, Object> humanTasks() {
            return humanTasks;
        }

        public Map<String, PeerDecl> peers() {
            return peers;
        }
    }

    /**
     * A declared peer agent.
     *
     * @param name        the tool name advertised to the model
     * @param targetAgent the peer agent's name
     * @param meta        declaration metadata (description, wait, callbackChannel, gating)
     */
    public record PeerDecl(String name, String targetAgent, Object meta) { }

    /**
     * A declared activity capability.
     *
     * @param toolName the tool name advertised to the model
     * @param function the @workflow:Activity function
     * @param meta     the declaration metadata (description, gating, retry policy) as
     *                 a Ballerina value
     * @param bindings the arguments fixed at registration (e.g. a client), or nil
     */
    public record ActivityDecl(String toolName, BFunctionPointer function, Object meta,
                               Object bindings) { }

    /**
     * A declared AI tool of a durable agent.
     *
     * @param toolName the tool name advertised to the model
     * @param tool     the tool function
     * @param meta     the declaration metadata (description, parameters schema, gating) as a
     *                 json value, decoded by the runner
     */
    public record ToolDeclEntry(String toolName, BFunctionPointer tool, Object meta) { }

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
     * @param inputType    the typedesc of the structured JSON payload accepted alongside the
     *                     query: json (any payload), a narrower type (validated payload), or
     *                     null (query-only agent)
     * @return true on success, or a BError when the name is already registered
     */
    public static Object registerDurableAgentDecl(BString agentName, BObject model, Object systemPrompt,
                                                  long maxIter, Object inputType, Object resultType) {
        String name = agentName.getValue();
        AgentDecl existing = AGENT_DECL_REGISTRY.putIfAbsent(name,
                new AgentDecl(name, model, systemPrompt, maxIter,
                        inputType instanceof BTypedesc typedesc ? typedesc : null,
                        resultType instanceof BTypedesc resultTypedesc ? resultTypedesc : null));
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
                                                      BFunctionPointer function, Object meta,
                                                      Object bindings) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        Object duplicate = duplicateCapabilityError(decl, toolName.getValue(), "an activity");
        if (duplicate != null) {
            return duplicate;
        }
        decl.activities().put(toolName.getValue(),
                new ActivityDecl(toolName.getValue(), function, meta, bindings));
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
        Object duplicate = duplicateCapabilityError(decl, eventName.getValue(), "a data-event channel");
        if (duplicate != null) {
            return duplicate;
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
    public static Object registerDurableAgentHumanTask(BString agentName, BString taskName, Object meta,
                                                       BTypedesc resultType) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        Object duplicate = duplicateCapabilityError(decl, taskName.getValue(), "a human task");
        if (duplicate != null) {
            return duplicate;
        }
        decl.humanTasks().put(taskName.getValue(), new HumanTaskDeclEntry(meta, resultType));
        return true;
    }

    /**
     * A declared human task capability.
     *
     * @param meta       declaration metadata (roles, title, description)
     * @param resultType the expected result typedesc
     */
    public record HumanTaskDeclEntry(Object meta, BTypedesc resultType) { }

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

    /**
     * Returns an unmodifiable view of every declared durable agent, keyed by agent name.
     * Used by {@link WorkflowMetadataNative} to publish agent declarations as workflow metadata.
     *
     * @return unmodifiable map of agent name to declaration
     */
    public static java.util.Map<String, AgentDecl> getAgentDeclRegistry() {
        return java.util.Collections.unmodifiableMap(AGENT_DECL_REGISTRY);
    }

    // -----------------------------------------------------------------------------------------
    // Runner registration and driving (run / result reads)
    // -----------------------------------------------------------------------------------------

    /**
     * Registers an AI tool of a durable agent declaration: stored on the declaration (so the
     * runner can advertise it) and published to the agent tool registry (so the built-in
     * executeAgentTool activity resolves it on any worker).
     *
     * @param agentName the agent name
     * @param toolName  the tool's advertised name (from @ai:AgentTool)
     * @param tool      the tool function
     * @return true on success, or a BError when the agent is unknown
     */
    public static Object registerDurableAgentTool(BString agentName, BString toolName, BFunctionPointer tool,
                                                  Object meta) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        Object duplicate = duplicateCapabilityError(decl, toolName.getValue(), "a tool");
        if (duplicate != null) {
            return duplicate;
        }
        decl.tools().put(toolName.getValue(), new ToolDeclEntry(toolName.getValue(), tool, meta));
        boolean mcpTool = meta instanceof io.ballerina.runtime.api.values.BMap<?, ?> metaMap
                && Boolean.TRUE.equals(metaMap.get(io.ballerina.runtime.api.utils.StringUtils.fromString("isMcp")));
        WorkflowWorkerNative.putAgentTool(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + agentName.getValue(),
                toolName.getValue(), tool, mcpTool);
        return true;
    }

    // The shared object-model runner and the built-in agent activities, handed over once at
    // workflow-module init (setObjectRunner). Captured natively so that generated user code
    // wires an agent by name alone and no runner machinery appears in the public API.
    private static volatile BFunctionPointer objectRunner;
    private static volatile Map<BString, Object> builtinAgentActivities;

    /**
     * Captures the shared object-model runner function and the built-in agent activities
     * (llmChat/generate/executeAgentTool). Called exactly once from the workflow module's own
     * {@code init()}, which always runs before any user-module registration.
     *
     * @param runner            the shared runner function
     * @param builtinActivities the built-in agent activities keyed by activity name
     */
    public static void setObjectRunner(BFunctionPointer runner, BMap<BString, Object> builtinActivities) {
        Map<BString, Object> builtins = new LinkedHashMap<>();
        for (Map.Entry<BString, Object> builtin : builtinActivities.entrySet()) {
            builtins.put(builtin.getKey(), builtin.getValue());
        }
        objectRunner = runner;
        builtinAgentActivities = Collections.unmodifiableMap(builtins);
    }

    /**
     * Registers the shared object-model runner as the agent's workflow: the agent gets its own
     * workflow type ({@code workflow-<agentName>}), whose activities are the agent's declared
     * activity functions plus the built-in agent activities (llmChat/generate/executeAgentTool).
     * Adapter dispatch, model/tool registries, and management views key on the same workflow
     * type; the type is flagged so the adapter injects the native agent context handle. The
     * runner and built-ins were captured at workflow-module init ({@link #setObjectRunner}).
     *
     * @param env       the Ballerina runtime environment
     * @param agentName the agent name
     * @return true on success, or a BError
     */
    public static Object registerDurableAgentRunner(Environment env, BString agentName) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        BFunctionPointer runner = objectRunner;
        Map<BString, Object> builtins = builtinAgentActivities;
        if (runner == null || builtins == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "The durable agent runner is not initialized. The workflow module failed to initialize."));
        }
        BMap<BString, Object> activities = ValueCreator.createMapValue();
        for (Map.Entry<BString, Object> builtin : builtins.entrySet()) {
            activities.put(builtin.getKey(), builtin.getValue());
        }
        for (ActivityDecl activity : decl.activities().values()) {
            BString toolName = StringUtils.fromString(activity.toolName());
            // A declared activity must not shadow a built-in agent activity
            // (llmChat/generate/executeAgentTool) — overwriting the entry would make the
            // loop's internal calls invoke the user function instead.
            if (activities.containsKey(toolName)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Agent '" + agentName.getValue() + "' declares an activity named '"
                                + activity.toolName() + "', which collides with a built-in agent activity."
                                + " Rename the function or give the activity a different tool name."));
            }
            activities.put(toolName, activity.function());
        }
        return WorkflowWorkerNative.registerAgentWorkflow(env, runner, agentName, activities);
    }

    /**
     * Returns the run spec of a declared agent as a {@code DurableAgentRunSpec} record: everything
     * the object-model runner needs to register capabilities on the native agent context and
     * start the ReAct loop.
     *
     * @param agentName the agent name
     * @return the DurableAgentRunSpec record, or a BError when the agent is unknown
     */
    public static Object getRunSpec(BString agentName) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        try {
            Map<String, Object> activitySample = Map.of();
            BMap<BString, Object> typeProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), ACTIVITY_SPEC_RECORD);
            BArray activities = ValueCreator.createArrayValue(TypeCreator.createArrayType(typeProbe.getType()));
            for (ActivityDecl activity : decl.activities().values()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("toolName", StringUtils.fromString(activity.toolName()));
                fields.put("activity", activity.function());
                fields.put("meta", activity.meta());
                fields.put("bindings", activity.bindings());
                activities.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), ACTIVITY_SPEC_RECORD, fields));
            }

            BMap<BString, Object> toolProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), TOOL_SPEC_RECORD);
            BArray tools = ValueCreator.createArrayValue(TypeCreator.createArrayType(toolProbe.getType()));
            for (Map.Entry<String, ToolDeclEntry> tool : decl.tools().entrySet()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("toolName", StringUtils.fromString(tool.getKey()));
                fields.put("tool", tool.getValue().tool());
                fields.put("meta", tool.getValue().meta());
                tools.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), TOOL_SPEC_RECORD, fields));
            }

            BMap<BString, Object> eventProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), EVENT_SPEC_RECORD);
            BArray events = ValueCreator.createArrayValue(TypeCreator.createArrayType(eventProbe.getType()));
            for (EventDecl event : decl.events().values()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("name", StringUtils.fromString(event.name()));
                fields.put("request", event.request());
                fields.put("response", event.response());
                fields.put("cardinality", StringUtils.fromString(event.cardinality()));
                events.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), EVENT_SPEC_RECORD, fields));
            }

            BMap<BString, Object> taskProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), HUMAN_TASK_SPEC_RECORD);
            BArray humanTasks = ValueCreator.createArrayValue(TypeCreator.createArrayType(taskProbe.getType()));
            for (Map.Entry<String, Object> task : decl.humanTasks().entrySet()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("name", StringUtils.fromString(task.getKey()));
                if (task.getValue() instanceof HumanTaskDeclEntry entry) {
                    fields.put("meta", entry.meta());
                    fields.put("resultType", entry.resultType());
                } else {
                    fields.put("meta", task.getValue());
                }
                humanTasks.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), HUMAN_TASK_SPEC_RECORD, fields));
            }

            BMap<BString, Object> peerProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), PEER_SPEC_RECORD);
            BArray peers = ValueCreator.createArrayValue(TypeCreator.createArrayType(peerProbe.getType()));
            for (PeerDecl peer : decl.peers().values()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("name", StringUtils.fromString(peer.name()));
                fields.put("targetAgent", StringUtils.fromString(peer.targetAgent()));
                fields.put("meta", peer.meta());
                peers.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), PEER_SPEC_RECORD, fields));
            }

            Map<String, Object> spec = new HashMap<>();
            spec.put("systemPrompt", decl.systemPrompt());
            spec.put("maxIter", decl.maxIter());
            spec.put("model", decl.model());
            if (decl.resultType() != null) {
                spec.put("resultType", decl.resultType());
            }
            spec.put("activities", activities);
            spec.put("tools", tools);
            spec.put("events", events);
            spec.put("humanTasks", humanTasks);
            spec.put("peers", peers);
            return ValueCreator.createRecordValue(ModuleUtils.getModule(), RUN_SPEC_RECORD, spec);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to build the run spec for durable agent '" + agentName.getValue() + "': "
                            + e.getMessage()));
        }
    }

    /**
     * Starts a durable agent instance ({@code DurableAgent.run}): from a service this is a
     * top-level client start of the agent's workflow type; from inside a workflow the agent runs
     * as a true Temporal child workflow via the child-workflow substrate, so its lifecycle is
     * tied to the caller.
     *
     * @param env   the Ballerina runtime environment
     * @param self  the DurableAgent object (carries the bound agent name)
     * @param query the user turn appended to the agent's system prompt
     * @param input optional structured input for the run
     * @return the new agent instance ID as a Ballerina string, or a BError
     */
    public static Object runAgent(Environment env, BObject self, BString query, Object input) {
        String agentName = boundAgentName(self);
        if (agentName == null) {
            return unboundAgentError("run");
        }
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName);
        if (decl == null) {
            return unknownAgentError(agentName);
        }
        Object validatedInput = validateRunInput(decl, input);
        if (validatedInput instanceof BError) {
            return validatedInput;
        }
        String workflowType = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + agentName;
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("agentName", agentName);
        runInput.put("query", query.getValue());
        runInput.put("input", validatedInput == null ? null
                : TypesUtil.convertBallerinaToJavaType(validatedInput));

        if (isInsideWorkflow()) {
            return WorkflowContextNative.startDurableAgentChild(agentName, runInput);
        }
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();
            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    String workflowId = WorkflowRuntime.getInstance().createInstance(workflowType, runInput);
                    balFuture.complete(StringUtils.fromString(workflowId));
                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                            "Failed to start durable agent '" + agentName + "': " + e.getMessage())));
                }
            });
            try {
                return balFuture.get();
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to start durable agent '" + agentName + "': " + e.getMessage()));
            }
        });
    }

    /**
     * Validates the {@code run} input payload against the agent's declared {@code inputType} and
     * returns the converted value, so declared record defaults are filled exactly as on the
     * management-API start path. The compiler plugin rejects statically decidable mismatches;
     * this covers dynamic values.
     *
     * @param decl  the agent declaration
     * @param input the run input payload (a Ballerina value, or null)
     * @return the input converted to the declared type ({@code null} when omitted), or a BError
     *         describing the mismatch
     */
    private static Object validateRunInput(AgentDecl decl, Object input) {
        BTypedesc inputType = decl.inputType();
        if (input == null) {
            return null; // Omitting the payload is always allowed; the query alone starts the run.
        }
        if (inputType == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Durable agent '" + decl.agentName() + "' takes no input payload (inputType is ()): "
                            + "pass the text in the 'query' argument, or declare an inputType"));
        }
        Type describing = io.ballerina.runtime.api.utils.TypeUtils.getImpliedType(
                inputType.getDescribingType());
        try {
            return io.ballerina.runtime.api.utils.ValueUtils.convert(input, describing);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "The 'input' argument does not match durable agent '" + decl.agentName()
                            + "'s declared inputType '" + describing + "': " + e.getMessage()));
        }
    }

    /** Field of the management-start envelope carrying the agent's user turn. */
    private static final String START_QUERY_FIELD = "query";
    /** Field of the management-start envelope carrying the structured payload. */
    private static final String START_INPUT_FIELD = "input";

    /**
     * Builds the runner envelope for a management-API start of a durable agent from the posted
     * {@code {query, input}} envelope. Every agent is started the same way — the query is the
     * user turn, and {@code input} is the structured payload validated against the declared
     * {@code inputType} (absent for a query-only agent, whose {@code inputType} is {@code ()}).
     *
     * @param agentName the agent name (the unprefixed workflow type)
     * @param input     the posted start envelope (a Ballerina mapping value, or null)
     * @return the runner envelope as a Java map, or a BError for an input mismatch
     */
    @SuppressWarnings("unchecked")
    public static Object buildStartRunInput(String agentName, Object input) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName);
        if (decl == null) {
            return unknownAgentError(agentName);
        }
        if (input != null && !(input instanceof BMap)) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Durable agent '" + agentName + "' is started with a '{query, input}' object: "
                            + "put the user turn in 'query'"
                            + (decl.inputType() == null ? "" : " and the payload in 'input'")));
        }
        BMap<BString, Object> envelope = (BMap<BString, Object>) input;
        boolean takesPayload = decl.inputType() != null;
        BError unknownField = rejectUnknownEnvelopeFields(agentName, envelope, takesPayload);
        if (unknownField != null) {
            return unknownField;
        }
        Object queryValue = envelope == null ? null : envelope.get(StringUtils.fromString(START_QUERY_FIELD));
        // The published schema lists 'query' as required, so a start that omits it is
        // malformed rather than a start on an empty turn — an agent that reasons from its
        // events alone still says so explicitly, with '"query": ""'.
        if (queryValue == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Durable agent '" + agentName + "' is started with a '{query, input}' object: "
                            + "the 'query' field is required (pass \"\" for an agent whose run is "
                            + "driven by its events)"));
        }
        if (!(queryValue instanceof BString)) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "The 'query' field of durable agent '" + agentName + "'s start input must be a string"));
        }
        String query = ((BString) queryValue).getValue();

        // A required query means the envelope itself is present by this point, and the field
        // check above means a present 'input' belongs to an agent that declares a payload. A
        // nil value is how JSON spells "no payload", so it reads the same as omitting the field.
        Object posted = envelope.get(StringUtils.fromString(START_INPUT_FIELD));
        Object payload = null;
        if (posted != null) {
            Type describing = io.ballerina.runtime.api.utils.TypeUtils.getImpliedType(
                    decl.inputType().getDescribingType());
            Object converted;
            try {
                converted = io.ballerina.runtime.api.utils.ValueUtils.convert(posted, describing);
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "The 'input' field does not match durable agent '" + agentName
                                + "'s declared inputType '" + describing + "': " + e.getMessage()));
            }
            payload = TypesUtil.convertBallerinaToJavaType(converted);
        }
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("agentName", agentName);
        runInput.put("query", query);
        runInput.put("input", payload);
        return runInput;
    }

    /**
     * Rejects any field the start envelope does not define, so a misspelled key fails loudly
     * instead of dropping the payload it was meant to carry — the envelope is closed, and the
     * published schema says so with {@code additionalProperties: false}. A query-only agent
     * defines no {@code input} field at all, so a posted one is reported as the payload it is
     * rather than as an anonymous unknown key, whether its value is nil or not.
     *
     * @param agentName    the agent name, for the diagnostic
     * @param envelope     the posted envelope, or null
     * @param takesPayload whether the agent declares an {@code inputType}
     * @return a BError naming the offending fields, or null when every field is known
     */
    private static BError rejectUnknownEnvelopeFields(String agentName, BMap<BString, Object> envelope,
                                                      boolean takesPayload) {
        if (envelope == null) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        for (BString key : envelope.getKeys()) {
            String field = key.getValue();
            if (START_QUERY_FIELD.equals(field) || (takesPayload && START_INPUT_FIELD.equals(field))) {
                continue;
            }
            if (START_INPUT_FIELD.equals(field)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Durable agent '" + agentName + "' takes no input payload (inputType is ()): "
                                + "start it with the 'query' field alone"));
            }
            unknown.add(field);
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return ErrorCreator.createError(StringUtils.fromString(
                "Durable agent '" + agentName + "'s start input has no field "
                        + String.join(", ", unknown.stream().map(f -> "'" + f + "'").toList())
                        + ": the envelope takes 'query'"
                        + (takesPayload ? " and 'input'" : " only")));
    }

    /**
     * Returns the JSON schema of a declared agent's management-start input: the uniform
     * {@code {query, input}} envelope, where {@code input} carries the schema of the declared
     * {@code inputType} and is omitted entirely for a query-only agent. Only {@code query} is
     * required — an omitted payload starts the agent on the query alone, as {@code run(query)}
     * does — so what the schema advertises is exactly what {@link #buildStartRunInput} accepts.
     *
     * @param agentName the agent name (the unprefixed workflow type)
     * @return the start-envelope JSON schema, or null when the agent is unknown
     */
    public static String startInputSchema(String agentName) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName);
        if (decl == null) {
            return null;
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "The user turn the agent reasons over");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(START_QUERY_FIELD, query);
        // Only the query is required. Omitting the payload runs the agent on the query alone,
        // exactly as `run(query)` does, so listing it as required would advertise a stricter
        // contract than either surface applies.
        List<Object> required = new ArrayList<>();
        required.add(START_QUERY_FIELD);

        BTypedesc inputType = decl.inputType();
        if (inputType != null) {
            Type describing = io.ballerina.runtime.api.utils.TypeUtils.getImpliedType(
                    inputType.getDescribingType());
            properties.put(START_INPUT_FIELD, TypesUtil.toJsonSchemaValue(describing));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // The envelope is closed: the runtime rejects any other field, so the schema must not
        // leave callers thinking one would be carried.
        schema.put("additionalProperties", Boolean.FALSE);
        return TypesUtil.toJsonString(schema);
    }

    // -----------------------------------------------------------------------------------------
    // Typed read methods (dependently-typed externals on workflow:DurableAgent)
    // -----------------------------------------------------------------------------------------

    /**
     * Non-blocking result read ({@code DurableAgent.getResult}): the agent's final response if
     * the instance has finished, or a {@code workflow:AgentBusyError} while it is still working.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param typedesc   the expected result type descriptor
     * @return the typed result, an AgentBusyError, or a BError
     */
    public static Object getResult(Environment env, BObject self, BString instanceId, BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return WorkflowContextNative.readDurableAgentChildResult(instanceId.getValue(), typedesc, false);
        }
        return clientRead(env, instanceId.getValue(), typedesc, false);
    }

    /**
     * Blocking, crash-resumable result read ({@code DurableAgent.waitForResult}): waits until the
     * instance finishes. Inside a workflow this is a durable suspend on the child's result;
     * from a service the calling thread blocks but the wait can be re-issued after a crash —
     * the result lives in workflow history.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param typedesc   the expected result type descriptor
     * @return the typed result, or a BError
     */
    public static Object waitForResult(Environment env, BObject self, BString instanceId, BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return WorkflowContextNative.readDurableAgentChildResult(instanceId.getValue(), typedesc, true);
        }
        return clientRead(env, instanceId.getValue(), typedesc, true);
    }

    /**
     * Client-side (outside-workflow) result read shared by getResult/waitForResult.
     */
    private static Object clientRead(Environment env, String instanceId, BTypedesc typedesc, boolean blocking) {
        return env.yieldAndRun(() -> {
            try {
                WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                if (client == null) {
                    return ErrorCreator.createError(StringUtils.fromString("Workflow client not initialized"));
                }
                WorkflowStub stub = client.newUntypedWorkflowStub(instanceId);
                Object raw;
                if (blocking) {
                    raw = stub.getResult(Object.class);
                } else {
                    // A tiny getResult timeout is unreliable for completed runs (the server
                    // round trip alone exceeds it), so check the execution status instead:
                    // still running means busy, any closed status has its result available.
                    io.temporal.api.enums.v1.WorkflowExecutionStatus status = stub.describe().getStatus();
                    if (status == io.temporal.api.enums.v1.WorkflowExecutionStatus
                                .WORKFLOW_EXECUTION_STATUS_RUNNING
                            || status == io.temporal.api.enums.v1.WorkflowExecutionStatus
                                .WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW) {
                        return createAgentBusyError(instanceId);
                    }
                    raw = stub.getResult(Object.class);
                }
                Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(raw);
                return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());
            } catch (io.temporal.client.WorkflowFailedException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return ErrorCreator.createError(StringUtils.fromString(
                        "Durable agent instance '" + instanceId + "' failed: " + message));
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to read the result of durable agent instance '" + instanceId + "': "
                                + e.getMessage()));
            }
        });
    }

    /**
     * Non-blocking event-turn read ({@code DurableAgent.getDataResult}): the turn's response if
     * it is ready, or a {@code workflow:AgentBusyError} while unanswered.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendData correlation token
     * @param typedesc   the expected response type descriptor
     * @return the typed response, an AgentBusyError, or a BError
     */
    public static Object getDataResult(Environment env, BObject self, BString instanceId, BString token,
                                        BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return readEventReplyInWorkflow(instanceId.getValue(), token.getValue(), typedesc, false);
        }
        return readEventResultFromClient(env, instanceId.getValue(), token.getValue(), typedesc, false);
    }

    /**
     * Blocking event-turn read ({@code DurableAgent.waitForDataResult}): waits until the turn is
     * answered. Inside a workflow this durably suspends on the reply signal; from a service it
     * blocks on the update result, which lives in history and is re-fetchable after a crash.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendData correlation token
     * @param typedesc   the expected response type descriptor
     * @return the typed response, or a BError
     */
    public static Object waitForDataResult(Environment env, BObject self, BString instanceId, BString token,
                                            BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return readEventReplyInWorkflow(instanceId.getValue(), token.getValue(), typedesc, true);
        }
        return readEventResultFromClient(env, instanceId.getValue(), token.getValue(), typedesc, true);
    }

    /**
     * Registers a peer-agent declaration of a durable agent.
     *
     * @param agentName   the declaring agent's name
     * @param peerName    the tool name advertised to the model
     * @param targetAgent the peer agent's name (its module-level variable name)
     * @param meta        declaration metadata (description, wait, callbackChannel, gating)
     * @return true on success, or a BError when the agent is unknown
     */
    public static Object registerDurableAgentPeer(BString agentName, BString peerName, BString targetAgent,
                                                  Object meta) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        Object duplicate = duplicateCapabilityError(decl, peerName.getValue(), "a peer agent");
        if (duplicate != null) {
            return duplicate;
        }
        decl.peers().put(peerName.getValue(),
                new PeerDecl(peerName.getValue(), targetAgent.getValue(), meta));
        return true;
    }

    /**
     * Starts a peer durable agent as a true Temporal child workflow of the calling agent.
     * Used by the ReAct loop's peer-agent dispatch.
     *
     * @param targetAgent the peer agent's name
     * @param query       the delegated task or question
     * @return the child instance ID as a Ballerina string, or a BError
     */
    public static Object runPeerAgent(BString targetAgent, BString query) {
        String target = targetAgent.getValue();
        if (AGENT_DECL_REGISTRY.get(target) == null) {
            return unknownAgentError(target);
        }
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("agentName", target);
        runInput.put("query", query.getValue());
        runInput.put("input", null);
        return WorkflowContextNative.startDurableAgentChild(target, runInput);
    }

    /**
     * Durably waits for a peer agent child started by {@code runPeerAgent} and returns its final
     * response as anydata.
     *
     * @param childId the peer child instance ID
     * @return the peer's final response, or a BError
     */
    public static Object waitForPeerAgentResult(BString childId) {
        return WorkflowContextNative.readDurableAgentChildRaw(childId.getValue(), true);
    }

    // -----------------------------------------------------------------------------------------
    // Event turns (sendData / getDataResult / waitForDataResult)
    // -----------------------------------------------------------------------------------------

    /**
     * Replies to event turns this workflow execution sent to agents via the reply-signal path,
     * keyed by correlation token. {@link WorkflowLocal} scopes the store to the workflow
     * execution; on replay the reply signals are re-delivered from history in the same order,
     * so reads are deterministic.
     */
    private static final WorkflowLocal<Map<String, Object>> AGENT_EVENT_REPLIES =
            WorkflowLocal.withCachedInitial(HashMap::new);

    /**
     * Records a reply-signal envelope ({token, response} or {token, error}) for an event turn
     * this workflow sent. Called by the workflow adapter's signal handler.
     *
     * @param token the correlation token
     * @param reply the reply envelope
     */
    public static void recordAgentEventReply(String token, Map<?, ?> reply) {
        AGENT_EVENT_REPLIES.get().put(token, reply);
    }

    /**
     * Sends an event turn to a running agent instance ({@code DurableAgent.sendData}) and
     * returns a correlation token. From a service the turn rides a Temporal Update (the token is
     * the update ID — durable, crash-recoverable via the pending-updates query). From inside a
     * workflow updates are unavailable, so the turn is delivered as a deterministic external
     * signal carrying a reply-to address; the agent answers with a reply signal correlated by
     * the token.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID returned by run()
     * @param eventName  a channel declared in the agent's events
     * @param data       the payload
     * @return the correlation token as a Ballerina string, or a BError
     */
    public static Object sendData(Environment env, BObject self, BString instanceId, BString eventName,
                                   Object data) {
        Object javaData = data == null ? null : TypesUtil.convertBallerinaToJavaType(data);
        String instance = instanceId.getValue();
        String event = eventName.getValue();

        if (isInsideWorkflow()) {
            try {
                WorkflowWorkerNative.awaitWhileSuspended();
                String token = "evt-" + Workflow.randomUUID();
                Map<String, Object> envelope = new HashMap<>();
                envelope.put("token", token);
                envelope.put("eventName", event);
                envelope.put("data", javaData);
                envelope.put("replyTo", Workflow.getInfo().getWorkflowId());
                Workflow.newUntypedExternalWorkflowStub(instance)
                        .signal(WorkflowWorkerNative.AGENT_EVENT_SIGNAL_NAME, envelope);
                return StringUtils.fromString(token);
            } catch (io.temporal.worker.NonDeterministicException e) {
                throw e;
            } catch (io.temporal.failure.CanceledFailure e) {
                throw e;
            } catch (io.temporal.workflow.SignalExternalWorkflowException e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to send event to agent instance '" + instance + "': "
                                + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())));
            } catch (io.temporal.failure.TemporalFailure e) {
                throw e;
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to send event to agent instance '" + instance + "': " + e.getMessage()));
            }
        }

        return env.yieldAndRun(() -> {
            try {
                WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                if (client == null) {
                    return ErrorCreator.createError(StringUtils.fromString("Workflow client not initialized"));
                }
                WorkflowStub stub = client.newUntypedWorkflowStub(instance);
                // Reject non-agent targets before sending: the update handler's validator also
                // rejects them, but the embedded test server can report acceptance-stage
                // rejections only at result-read time, which would surface as a confusing
                // "update not found" much later.
                try {
                    String targetType = stub.describe().getWorkflowType();
                    if (WorkflowWorkerNative.isRegisteredWorkflowType(targetType)
                            && !WorkflowWorkerNative.isAgentWorkflowType(targetType)) {
                        return ErrorCreator.createError(StringUtils.fromString(
                                "sendData turns are only supported for workflow:DurableAgent instances; '"
                                        + instance + "' is a regular workflow — use workflow:sendData instead"));
                    }
                } catch (Exception ignore) {
                    // The target may live on another worker; the handler-side validator decides.
                }
                UpdateOptions<Object> options = UpdateOptions.newBuilder(Object.class)
                        .setUpdateName(WorkflowWorkerNative.AGENT_SEND_DATA_UPDATE)
                        .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                        .build();
                WorkflowUpdateHandle<Object> handle = stub.startUpdate(options, event, javaData);
                return StringUtils.fromString(handle.getId());
            } catch (Exception e) {
                Throwable cause = e.getCause();
                String message = cause != null && cause.getMessage() != null ? cause.getMessage()
                        : e.getMessage();
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to send event to agent instance '" + instance + "': " + message));
            }
        });
    }

    /**
     * Reads one event turn's reply inside a workflow (reply-signal correlation store).
     */
    private static Object readEventReplyInWorkflow(String instanceId, String token, BTypedesc typedesc,
                                                   boolean blocking) {
        try {
            if (blocking) {
                WorkflowWorkerNative.awaitWhileSuspended();
                Workflow.await(() -> AGENT_EVENT_REPLIES.get().containsKey(token));
            }
            Object reply = AGENT_EVENT_REPLIES.get().get(token);
            if (reply == null) {
                return createAgentBusyError(instanceId);
            }
            if (reply instanceof Map<?, ?> replyMap) {
                Object error = replyMap.get("error");
                if (error != null) {
                    return ErrorCreator.createError(StringUtils.fromString(String.valueOf(error)));
                }
                Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(replyMap.get("response"));
                return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());
            }
            return ErrorCreator.createError(StringUtils.fromString(
                    "Malformed agent event reply for token '" + token + "'"));
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read the event result for token '" + token + "': " + e.getMessage()));
        }
    }

    /**
     * Reads one event turn's result from a service (Temporal Update handle).
     */
    private static Object readEventResultFromClient(Environment env, String instanceId, String token,
                                                    BTypedesc typedesc, boolean blocking) {
        return env.yieldAndRun(() -> {
            try {
                WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                if (client == null) {
                    return ErrorCreator.createError(StringUtils.fromString("Workflow client not initialized"));
                }
                WorkflowStub stub = client.newUntypedWorkflowStub(instanceId);
                WorkflowUpdateHandle<Object> handle = stub.getUpdateHandle(token, Object.class);
                Object raw;
                if (blocking) {
                    raw = handle.getResultAsync().get();
                } else {
                    try {
                        raw = handle.getResultAsync(1, TimeUnit.MILLISECONDS).get();
                    } catch (Exception e) {
                        if (isTimeout(e)) {
                            return createAgentBusyError(instanceId);
                        }
                        throw e;
                    }
                }
                Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(raw);
                return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());
            } catch (Exception e) {
                if (isTimeout(e)) {
                    return createAgentBusyError(instanceId);
                }
                Throwable cause = e.getCause();
                String message = cause != null && cause.getMessage() != null ? cause.getMessage()
                        : e.getMessage();
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to read the event result for token '" + token + "' of agent instance '"
                                + instanceId + "': " + message));
            }
        });
    }

    private static boolean isTimeout(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String boundAgentName(BObject self) {
        Object value = self.get(StringUtils.fromString(AGENT_NAME_FIELD));
        if (value instanceof BString name && !name.getValue().isEmpty()) {
            return name.getValue();
        }
        return null;
    }

    private static boolean isInsideWorkflow() {
        try {
            io.temporal.workflow.Workflow.getInfo();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Builds a Ballerina {@code workflow:AgentBusyError} indicating the instance/turn is still
     * in progress.
     */
    public static BError createAgentBusyError(String instanceId) {
        String message = "Durable agent instance '" + instanceId + "' is still working";
        try {
            return ErrorCreator.createError(ModuleUtils.getModule(), AGENT_BUSY_ERROR,
                    StringUtils.fromString(message), null, null);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(AGENT_BUSY_ERROR + ": " + message));
        }
    }

    private static Object unboundAgentError(String method) {
        return ErrorCreator.createError(StringUtils.fromString(
                "workflow:DurableAgent." + method + " requires the agent to be a module-level 'final' "
                        + "declaration: no agent name is bound to this object (is the workflow compiler "
                        + "plugin active?)"));
    }

    private static Object unknownAgentError(String agentName) {
        return ErrorCreator.createError(StringUtils.fromString(
                "Unknown durable agent '" + agentName + "': the agent declaration was not registered"));
    }

    /**
     * Claims a capability name on the agent, or returns the error to fail startup with.
     *
     * <p>Events, tools, activities, human tasks, and peers share one flat namespace per agent:
     * the name is what the model calls, what dispatch keys on, and — for a human task — the
     * Temporal workflow type of the task. These registrations run from module init, so rejecting
     * a duplicate here fails the program at startup rather than letting one declaration silently
     * replace the other. The compiler plugin reports the same conflict as WORKFLOW_150 wherever
     * it can see it; this check also covers what it cannot (a declaration compiled elsewhere, or
     * a plugin that did not run).
     *
     * @param decl the agent declaration
     * @param name the capability name being registered
     * @param kind the capability kind, for the error message
     * @return an error when the name is already claimed, otherwise null
     */
    private static Object duplicateCapabilityError(AgentDecl decl, String name, String kind) {
        String claimedBy = decl.claimCapabilityName(name, kind);
        if (claimedBy == null) {
            return null;
        }
        return ErrorCreator.createError(StringUtils.fromString(
                "Duplicate capability name '" + name + "' in durable agent '" + decl.agentName()
                        + "': declared as " + kind + " and as " + claimedBy + ". Events, tools, activities, "
                        + "human tasks, and peers share one flat namespace — give one of them a different name."));
    }
}
