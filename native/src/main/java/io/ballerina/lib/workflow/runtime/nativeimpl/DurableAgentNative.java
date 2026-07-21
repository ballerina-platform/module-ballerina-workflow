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

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        private final Map<String, BFunctionPointer> tools = new LinkedHashMap<>();
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

        public Map<String, BFunctionPointer> tools() {
            return tools;
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
    public static Object registerDurableAgentTool(BString agentName, BString toolName, BFunctionPointer tool) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        decl.tools().put(toolName.getValue(), tool);
        WorkflowWorkerNative.putAgentTool(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + agentName.getValue(),
                toolName.getValue(), tool);
        return true;
    }

    /**
     * Registers the shared object-model runner as the agent's workflow: the agent gets its own
     * workflow type ({@code workflow-<agentName>}), whose activities are the agent's declared
     * activity functions plus the built-in agent activities (llmChat/generate/executeAgentTool).
     * This reuses the whole function-based agent substrate — adapter dispatch, model/tool
     * registries, and management views key on the same workflow type.
     *
     * @param env               the Ballerina runtime environment
     * @param agentName         the agent name
     * @param runner            the shared runner function (workflow:runDurableAgentObject)
     * @param builtinActivities the built-in agent activities keyed by activity name
     * @return true on success, or a BError
     */
    public static Object registerDurableAgentRunner(Environment env, BString agentName, BFunctionPointer runner,
                                                    BMap<BString, Object> builtinActivities) {
        AgentDecl decl = AGENT_DECL_REGISTRY.get(agentName.getValue());
        if (decl == null) {
            return unknownAgentError(agentName.getValue());
        }
        BMap<BString, Object> activities = ValueCreator.createMapValue();
        for (Map.Entry<BString, Object> builtin : builtinActivities.entrySet()) {
            activities.put(builtin.getKey(), builtin.getValue());
        }
        for (ActivityDecl activity : decl.activities().values()) {
            activities.put(StringUtils.fromString(activity.toolName()), activity.function());
        }
        return WorkflowWorkerNative.registerWorkflow(env, runner, agentName, activities);
    }

    /**
     * Returns the run spec of a declared agent as a {@code DurableAgentRunSpec} record: everything
     * the object-model runner needs to register capabilities on its AgentContext and start the
     * ReAct loop.
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
                activities.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), ACTIVITY_SPEC_RECORD, fields));
            }

            BMap<BString, Object> toolProbe = ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), TOOL_SPEC_RECORD);
            BArray tools = ValueCreator.createArrayValue(TypeCreator.createArrayType(toolProbe.getType()));
            for (Map.Entry<String, BFunctionPointer> tool : decl.tools().entrySet()) {
                Map<String, Object> fields = new HashMap<>();
                fields.put("toolName", StringUtils.fromString(tool.getKey()));
                fields.put("tool", tool.getValue());
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
                fields.put("meta", task.getValue());
                humanTasks.append(ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), HUMAN_TASK_SPEC_RECORD, fields));
            }

            Map<String, Object> spec = new HashMap<>();
            spec.put("systemPrompt", decl.systemPrompt());
            spec.put("maxIter", decl.maxIter());
            spec.put("model", decl.model());
            spec.put("activities", activities);
            spec.put("tools", tools);
            spec.put("events", events);
            spec.put("humanTasks", humanTasks);
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
        if (AGENT_DECL_REGISTRY.get(agentName) == null) {
            return unknownAgentError(agentName);
        }
        String workflowType = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + agentName;
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("agentName", agentName);
        runInput.put("query", query.getValue());
        runInput.put("input", input == null ? null : TypesUtil.convertBallerinaToJavaType(input));

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
                    try {
                        raw = stub.getResult(1, TimeUnit.MILLISECONDS, Object.class);
                    } catch (java.util.concurrent.TimeoutException e) {
                        return createAgentBusyError(instanceId);
                    }
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
     * Non-blocking event-turn read ({@code DurableAgent.getEventResult}): the turn's response if
     * it is ready, or a {@code workflow:AgentBusyError} while unanswered.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendEvent correlation token
     * @param typedesc   the expected response type descriptor
     * @return the typed response, an AgentBusyError, or a BError
     */
    public static Object getEventResult(Environment env, BObject self, BString instanceId, BString token,
                                        BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return readEventReplyInWorkflow(instanceId.getValue(), token.getValue(), typedesc, false);
        }
        return readEventResultFromClient(env, instanceId.getValue(), token.getValue(), typedesc, false);
    }

    /**
     * Blocking event-turn read ({@code DurableAgent.waitForEventResult}): waits until the turn is
     * answered. Inside a workflow this durably suspends on the reply signal; from a service it
     * blocks on the update result, which lives in history and is re-fetchable after a crash.
     *
     * @param env        the Ballerina runtime environment
     * @param self       the DurableAgent object
     * @param instanceId the agent instance ID
     * @param token      the sendEvent correlation token
     * @param typedesc   the expected response type descriptor
     * @return the typed response, or a BError
     */
    public static Object waitForEventResult(Environment env, BObject self, BString instanceId, BString token,
                                            BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return readEventReplyInWorkflow(instanceId.getValue(), token.getValue(), typedesc, true);
        }
        return readEventResultFromClient(env, instanceId.getValue(), token.getValue(), typedesc, true);
    }

    // -----------------------------------------------------------------------------------------
    // Event turns (sendEvent / getEventResult / waitForEventResult)
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
     * Sends an event turn to a running agent instance ({@code DurableAgent.sendEvent}) and
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
    public static Object sendEvent(Environment env, BObject self, BString instanceId, BString eventName,
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
                UpdateOptions<Object> options = UpdateOptions.newBuilder(Object.class)
                        .setUpdateName(WorkflowWorkerNative.AGENT_UPDATE_NAME)
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
}
