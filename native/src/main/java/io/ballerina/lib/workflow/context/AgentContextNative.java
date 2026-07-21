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

package io.ballerina.lib.workflow.context;

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BHandle;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.worker.NonDeterministicException;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native implementations backing the {@code workflow:AgentContext} client class and the durable agent loop.
 * <p>
 * The imperative agent body registers capabilities on the context — workflow activities
 * ({@link #recordActivityTool}), AI tools ({@link #recordAiTool}), and human tasks
 * ({@link #recordHumanTaskTool}). The loop advertises them (plus one wait-tool per declared signature event) to the
 * model via {@link #getToolDefs} and dispatches invocations durably: activities and AI tools as Temporal activities
 * ({@link #callActivity}), human tasks as sub-workflows that suspend the agent ({@link #awaitHumanTask}), and events
 * as durable signal waits ({@link #awaitEvent}).
 *
 * @since 0.7.0
 */
public final class AgentContextNative {

    private static final String CALL_CONFIG_MARKER = "__callConfig__";
    private static final String RETRY_ON_ERROR_KEY = "retryOnError";
    private static final String CHAT_EVENT = "chat";

    // Tool dispatch kinds understood by the Ballerina agent loop.
    private static final String KIND_ACTIVITY = "activity";
    private static final String KIND_AI_TOOL = "aitool";
    private static final String KIND_HUMAN_TASK = "humantask";
    private static final String KIND_EVENT_PREFIX = "event:";
    private static final String KIND_END = "end";
    private static final String EVENT_TOOL_PREFIX = "awaitEvent_";
    private static final String END_CONVERSATION_TOOL = "endConversation";

    // Interaction patterns (mirrors workflow:AgentInteractionPattern).
    private static final String MULTI_EVENT = "MULTI_EVENT";

    private AgentContextNative() {
        // Utility class
    }

    /**
     * Per-execution state for an agent context. Holds the workflow identity, the signal wrapper (for event waits),
     * the declared event names, the registered tools, and the agent's final response.
     */
    public static final class AgentContextInfo {
        private final String workflowId;
        private final String workflowType;
        private final SignalAwaitWrapper signalWrapper;
        private final Set<String> eventNames;
        // Update channels declared via registerUpdateEvents: name -> [requestType, responseType?].
        private final Map<String, Object[]> updateEvents = new HashMap<>();
        private final List<ToolMeta> tools = new ArrayList<>();
        private final Map<String, HumanTaskMeta> humanTasks = new HashMap<>();
        private String finalResponse = "";
        // Interaction semantics (configured via ctx.setInteraction; defaults = SINGLE_EVENT).
        private boolean multiEvent = false;
        private Long eventTimeoutMillis = null;
        private long maxEventWaits = 50;
        private long eventWaitCount = 0;
        // Approval policy for gated tools (configured via ctx.buildAndRun approval config).
        private String[] approvalUserRoles = new String[0];
        private Long approvalTimeoutMillis = null;
        // The responder of the updateAgent request whose message the agent most recently
        // consumed; completed with the next recorded response (the turn's answer).
        private CompletablePromise<Object> pendingResponder = null;
        // Set when the agent is finishing: new updates are answered immediately from
        // finalResponse / closingFailure instead of being enqueued (nobody would consume them).
        private boolean closing = false;
        private String closingFailure = null;
        // The model provider configured via ctx.setModelProvider; consumed by runDurableAgent.
        private BObject modelProvider = null;

        public AgentContextInfo(String workflowId, String workflowType, SignalAwaitWrapper signalWrapper,
                                Set<String> eventNames) {
            this.workflowId = workflowId;
            this.workflowType = workflowType;
            this.signalWrapper = signalWrapper;
            this.eventNames = eventNames;
        }

        public String finalResponse() {
            return finalResponse;
        }

        public boolean isClosing() {
            return closing;
        }

        public String closingFailure() {
            return closingFailure;
        }
    }

    /**
     * Metadata for one advertised tool.
     *
     * @param name         the tool name advertised to the model
     * @param description  the tool description advertised to the model
     * @param schema       the model-facing parameter JSON schema
     * @param kind         the dispatch kind ({@code activity}, {@code aitool}, {@code humantask})
     * @param activityName for activity tools, the underlying {@code @workflow:Activity} function name (the advertised
     *                     {@code name} may be overridden at registration); {@code null} for other kinds
     * @param bindings     for activity tools, registration-time fixed arguments with client objects already converted
     *                     to {@code "connection:<name>"} markers; {@code null} when absent or for other kinds
     * @param requiresApproval when {@code true}, a PRE_RUN review activity gates the tool before it runs
     * @param retryPolicy  the activity tool's failure policy: {@code null} (NoRetry), an AutoRetry {@code BMap}, or
     *                     the {@code "MANUAL_RETRY"} {@code BString}; {@code null} for non-activity tools
     */
    private record ToolMeta(String name, String description, Map<String, Object> schema, String kind,
                            String activityName, Map<String, Object> bindings, boolean requiresApproval,
                            Object retryPolicy) {
        ToolMeta(String name, String description, Map<String, Object> schema, String kind) {
            this(name, description, schema, kind, null, null, false, null);
        }

        ToolMeta(String name, String description, Map<String, Object> schema, String kind,
                 String activityName, Map<String, Object> bindings) {
            this(name, description, schema, kind, activityName, bindings, false, null);
        }
    }

    private record HumanTaskMeta(Object userRoles, String title, String description, BTypedesc resultType,
                                 Object timeout) { }

    /**
     * Configures the agent's interaction semantics. {@code MULTI_EVENT} makes event waits FIFO-repeatable
     * (conversational) and requires an event timeout as its safety mechanism; {@code maxEventWaits} caps the total
     * number of event waits per run.
     *
     * @param handle        the agent context handle
     * @param pattern       "SINGLE_EVENT" or "MULTI_EVENT"
     * @param eventTimeout  a {@code time:Duration} map, or null for no per-wait timeout
     * @param maxEventWaits cap on total event waits per run
     * @return null on success, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object setInteraction(BHandle handle, BString pattern, Object eventTimeout, long maxEventWaits) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            boolean multiEvent = MULTI_EVENT.equals(pattern.getValue());
            Long timeoutMillis = eventTimeout instanceof BMap
                    ? WorkflowContextNative.computeTimeoutMillis((BMap<BString, Object>) eventTimeout)
                    : null;
            if (multiEvent && timeoutMillis == null) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "MULTI_EVENT interaction requires an eventTimeout as its safety mechanism"));
            }
            if (maxEventWaits < 1) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "maxEventWaits must be at least 1"));
            }
            info.multiEvent = multiEvent;
            info.eventTimeoutMillis = timeoutMillis;
            info.maxEventWaits = maxEventWaits;
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to configure agent interaction: " + e.getMessage()));
        }
    }

    /**
     * Stores the approval policy (roles allowed to decide a review, and an optional decision timeout) used when a
     * gated tool creates a PRE_RUN review activity.
     *
     * @param handle    the agent context handle
     * @param userRoles a BString or BString[] of roles permitted to decide
     * @param timeout   a {@code time:Duration} map, or null to wait indefinitely
     * @return null on success, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object setAgentApproval(BHandle handle, Object userRoles, Object timeout) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            List<String> roles = new ArrayList<>();
            if (userRoles instanceof BString roleStr) {
                roles.add(roleStr.getValue());
            } else if (userRoles instanceof io.ballerina.runtime.api.values.BArray roleArr) {
                for (int i = 0; i < roleArr.size(); i++) {
                    roles.add(roleArr.get(i).toString());
                }
            }
            info.approvalUserRoles = roles.toArray(new String[0]);
            info.approvalTimeoutMillis = timeout instanceof BMap
                    ? WorkflowContextNative.computeTimeoutMillis((BMap<BString, Object>) timeout) : null;
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to configure agent approval: " + e.getMessage()));
        }
    }

    /**
     * Backs {@code awaitAgentToolReview}: starts a PRE_RUN review activity for a gated tool and blocks until a human
     * decides. Runs inside the agent workflow, so it is replay-safe. Returns the decision as a JSON string
     * ({@code {"action": "...", "input"?: {...}, "feedback"?: "..."}}).
     *
     * @param handle   the agent context handle
     * @param toolName the advertised tool name (mapped to the underlying activity name when applicable)
     * @param argsJson the model-proposed arguments as a JSON string (shown to the reviewer)
     * @return the decision JSON string, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object awaitToolReview(BHandle handle, BString toolName, BString argsJson) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            String name = toolName.getValue();
            // For an activity tool, review under the underlying activity's qualified name so the
            // reviewer/inbox sees the real activity; other tools review under the tool name.
            String activityName = name;
            for (ToolMeta tool : info.tools) {
                if (KIND_ACTIVITY.equals(tool.kind()) && tool.name().equals(name) && tool.activityName() != null) {
                    activityName = tool.activityName();
                    break;
                }
            }
            String qualifiedName = Workflow.getInfo().getWorkflowType() + "." + activityName;

            Object parsedArgs = JsonUtils.parse(argsJson.getValue());
            Map<String, Object> argsMap = new LinkedHashMap<>();
            Object javaArgs = TypesUtil.convertBallerinaToJavaType(parsedArgs);
            if (javaArgs instanceof Map<?, ?> m) {
                argsMap.putAll((Map<String, Object>) m);
            }

            Map<String, Object> decision = WorkflowContextNative.startReviewActivity(
                    "PRE_RUN", qualifiedName, argsMap, "", info.approvalUserRoles, info.approvalTimeoutMillis);
            return StringUtils.fromString(TypesUtil.toJsonString(decision));
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to obtain review decision for tool '" + toolName.getValue() + "': " + e.getMessage()));
        }
    }

    /**
     * Records a {@code @workflow:Activity} tool: derives its name and parameter JSON schema so the loop can advertise
     * it to the model. The function pointer itself is registered as a Temporal activity at module init (by the
     * compiler plugin), so only metadata is stored here.
     * <p>
     * Arguments may be partially applied at registration via {@code bindings}: bound parameters (and client-object /
     * typedesc parameters, which the model can never supply) are excluded from the advertised schema, and the bound
     * values — client objects converted to {@code "connection:<name>"} markers — are merged into the model-supplied
     * arguments at dispatch by {@link #callActivityTool}. This lets built-in activities such as
     * {@code activity:callRestAPI} be registered as-is, without a wrapper function.
     *
     * @param handle         the agent context handle
     * @param fn             the tool function pointer
     * @param nameArg        the advertised tool name (BString), or null for the function name
     * @param descriptionArg the advertised tool description (BString), or null for a default
     * @param bindingsArg    a BMap of arguments fixed at registration, or null
     * @return null on success, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object recordActivityTool(BHandle handle, BFunctionPointer fn, Object nameArg,
                                            Object descriptionArg, Object bindingsArg,
                                            boolean requiresApproval, Object retryPolicy) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            String activityName = fn.getType().getName();
            if (activityName == null || activityName.isBlank()) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Agent tools must be named module-level functions; anonymous functions are not supported."));
            }
            String toolName = nameArg instanceof BString nameB && !nameB.getValue().isBlank()
                    ? nameB.getValue() : activityName;
            String description = descriptionArg instanceof BString descB && !descB.getValue().isBlank()
                    ? descB.getValue() : "Tool " + toolName;
            Map<String, Object> bindings = null;
            if (bindingsArg instanceof BMap<?, ?>) {
                bindings = WorkflowContextNative.convertArgsMapWithConnectionMarkers(
                        (BMap<BString, Object>) bindingsArg);
            }
            Set<String> boundNames = bindings == null ? Set.of() : bindings.keySet();
            Map<String, Object> schema = parameterSchemaOf(fn, boundNames, activityName);
            // NoRetry arrives as nil; AutoRetry as a BMap; ManualRetry as the "MANUAL_RETRY" BString.
            Object policy = retryPolicy instanceof BMap || retryPolicy instanceof BString ? retryPolicy : null;
            info.tools.add(new ToolMeta(toolName, description, schema, KIND_ACTIVITY, activityName, bindings,
                    requiresApproval, policy));
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to register agent activity tool: " + e.getMessage()));
        }
    }

    /**
     * Records an AI tool (from an {@code ai:ToolConfig} or an {@code @ai:AgentTool} function). The tool's function
     * pointer is stored in the worker-wide agent tool registry so the built-in {@code executeAgentTool} activity
     * wrapper can invoke it.
     *
     * @param handle         the agent context handle
     * @param fn             the tool's caller function pointer
     * @param name           the tool's advertised name
     * @param description    the tool's description
     * @param parametersJson the tool's parameter JSON schema (nullable; derived from the function when absent)
     * @return null on success, or a Ballerina error
     */
    public static Object recordAiTool(BHandle handle, BFunctionPointer fn, BString name, BString description,
                                      Object parametersJson, boolean requiresApproval) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            Map<String, Object> schema;
            if (parametersJson instanceof BString schemaJson) {
                schema = parseSchema(schemaJson.getValue());
            } else {
                schema = parameterSchemaOf(fn);
            }
            info.tools.add(new ToolMeta(name.getValue(), description.getValue(), schema, KIND_AI_TOOL,
                    null, null, requiresApproval, null));
            WorkflowWorkerNative.putAgentTool(info.workflowType, name.getValue(), fn);
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to register agent AI tool: " + e.getMessage()));
        }
    }

    /**
     * Records a human task as an agent tool. When the agent invokes it, {@link #awaitHumanTask} starts the human-task
     * sub-workflow and suspends the agent until completion.
     *
     * @param handle      the agent context handle
     * @param taskName    the task name (also the tool name advertised to the model)
     * @param userRoles   role or roles permitted to complete the task
     * @param resultType  the expected completion result type
     * @param title       optional short title
     * @param description optional description (also the tool description)
     * @param timeout     optional {@code time:Duration} after which the task times out
     * @return null on success, or a Ballerina error
     */
    public static Object recordHumanTaskTool(BHandle handle, BString taskName, Object userRoles,
                                             BTypedesc resultType, Object title, Object description,
                                             Object timeout) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            String name = taskName.getValue();
            if (name.isBlank() || name.contains(".") || name.contains("|")) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "HumanTask taskName must be non-blank and must not contain '.' or '|'"));
            }
            String titleStr = title instanceof BString t ? t.getValue() : name;
            String descriptionStr = description instanceof BString d ? d.getValue()
                    : "Creates the human task '" + name + "' and waits for a person to complete it. "
                            + "Pass any details relevant for the person as fields.";
            // The model may pass arbitrary payload fields shown to the person.
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", Boolean.TRUE);
            info.tools.add(new ToolMeta(name, descriptionStr, schema, KIND_HUMAN_TASK));
            info.humanTasks.put(name, new HumanTaskMeta(userRoles, titleStr, descriptionStr, resultType,
                    timeout instanceof BMap ? timeout : null));
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to register agent human task: " + e.getMessage()));
        }
    }

    /**
     * Returns the registered tools — plus one wait-tool per event declared in the agent's signature — as a JSON
     * string of {@code {name, description, parameters, kind}} entries consumed by the agent loop.
     *
     * @param handle the agent context handle
     * @return a JSON array string
     */
    public static Object getToolDefs(BHandle handle) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        List<Object> defs = new ArrayList<>();
        for (ToolMeta tool : info.tools) {
            defs.add(toolDef(tool.name(), tool.description(), tool.schema(), tool.kind(), tool.requiresApproval()));
        }
        if (info.eventNames != null) {
            for (String eventName : info.eventNames) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", new LinkedHashMap<>());
                defs.add(toolDef(EVENT_TOOL_PREFIX + eventName,
                        "Suspends until the external data event '" + eventName + "' arrives and returns its data. "
                                + "Use this when you need to wait for '" + eventName + "'.",
                        schema, KIND_EVENT_PREFIX + eventName));
            }
        }
        if (info.multiEvent) {
            // Under MULTI_EVENT the loop keeps the conversation open automatically after
            // each answer; ending is an explicit act via this tool (or the event timeout).
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            Map<String, Object> farewell = new LinkedHashMap<>();
            farewell.put("type", "string");
            farewell.put("description", "Optional farewell message shown to the user");
            properties.put("farewell", farewell);
            schema.put("properties", properties);
            defs.add(toolDef(END_CONVERSATION_TOOL,
                    "Permanently ends this conversation. Call this ONLY when the user says goodbye or asks to "
                            + "end the conversation.",
                    schema, KIND_END));
        }
        return StringUtils.fromString(TypesUtil.toJsonString(defs));
    }

    private static Map<String, Object> toolDef(String name, String description, Map<String, Object> schema,
                                               String kind) {
        return toolDef(name, description, schema, kind, false);
    }

    private static Map<String, Object> toolDef(String name, String description, Map<String, Object> schema,
                                               String kind, boolean requiresApproval) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", name);
        def.put("description", description);
        def.put("parameters", schema);
        def.put("kind", kind);
        def.put("requiresApproval", requiresApproval);
        return def;
    }

    /**
     * Returns the agent's workflow type (e.g. {@code workflow-orderAgent}).
     *
     * @param handle the agent context handle
     * @return the workflow type
     */
    public static BString getWorkflowType(BHandle handle) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        return StringUtils.fromString(info.workflowType);
    }

    /**
     * Stores the model provider configured via {@code ctx.setModelProvider}. Applied to the worker-wide model
     * registry when {@code runDurableAgent} starts.
     *
     * @param handle the agent context handle
     * @param model  the model provider client object
     */
    public static void setModelProvider(BHandle handle, BObject model) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        info.modelProvider = model;
    }

    /**
     * Registers a named two-way update channel declared via {@code ctx.registerUpdateEvents}. The name joins the
     * agent's waitable event set (so the loop and {@code updateAgent} can target it) and the request/response
     * typedescs are retained for validation.
     *
     * @param handle       the agent context handle
     * @param name         the update channel name
     * @param requestType  the request payload typedesc
     * @param responseType the optional response typedesc (nil when unspecified)
     * @return null on success, or a Ballerina error for an invalid name
     */
    public static Object registerUpdateEvent(BHandle handle, BString name, BTypedesc requestType, Object responseType) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        String eventName = name.getValue();
        if (eventName.isEmpty() || eventName.contains(".") || eventName.contains("|")) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Invalid update channel name '" + eventName + "': must be non-empty and not contain '.' or '|'"));
        }
        info.eventNames.add(eventName);
        info.updateEvents.put(eventName, new Object[] {requestType, responseType});
        return null;
    }

    /**
     * Registers the stored model provider for this agent so the built-in {@code llmChat}/{@code generate}
     * activities can resolve it (keyed by the agent's workflow type). Called by {@code runDurableAgent}.
     *
     * @param handle the agent context handle
     * @return null on success, or a Ballerina error when no provider was configured
     */
    public static Object registerModel(BHandle handle) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        if (info.modelProvider == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "No model provider configured. Call ctx.setModelProvider(...) before ctx->runDurableAgent(...)."));
        }
        WorkflowWorkerNative.putAgentModel(info.workflowType, info.modelProvider);
        return null;
    }

    /**
     * Stores the agent's final textual response for later retrieval.
     *
     * @param handle   the agent context handle
     * @param response the final response text
     * @return null (always succeeds)
     */
    /**
     * Returns the agent's recorded final response for this execution ("" when none).
     *
     * @param handle the AgentContextInfo handle
     * @return the final response text as a Ballerina string
     */
    public static BString getFinalResponse(BHandle handle) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        return StringUtils.fromString(info.finalResponse == null ? "" : info.finalResponse);
    }

    public static Object setResponse(BHandle handle, BString response) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        info.finalResponse = response.getValue();
        AgentResponseStore.put(info.workflowId, response.getValue());
        // Answer the updateAgent request whose message this turn consumed, if any.
        if (info.pendingResponder != null && !info.pendingResponder.isCompleted()) {
            info.pendingResponder.complete(response.getValue());
        }
        info.pendingResponder = null;
        // Surface the (latest) response cross-process via the workflow memo, so
        // management:getAgentResponse works from any process. Best-effort: some test
        // environments may not support memo upserts; the in-JVM store remains the fallback.
        try {
            Map<String, Object> memo = new HashMap<>();
            memo.put("workflowKind", "AGENT");
            memo.put("agentResponse", response.getValue());
            Workflow.upsertMemo(memo);
        } catch (Exception e) {
            // Ignore — response remains available via AgentResponseStore in this JVM.
        }
        return null;
    }

    /**
     * Settles all outstanding updateAgent requests when the agent finishes, so accepted updates never outlive the
     * workflow (which would fail them with "workflow completed before the update completed"). The consumed-but-
     * unanswered responder and every queued-but-unconsumed responder are completed with the agent's final response,
     * or exceptionally with the agent's failure message.
     *
     * @param handle         the agent context handle
     * @param failureMessage the agent's failure message, or null when the agent completed normally
     */
    public static void finishAgentUpdates(BHandle handle, Object failureMessage) {
        settleUpdates((AgentContextInfo) handle.getValue(),
                failureMessage instanceof BString failure ? failure.getValue() : null);
    }

    /**
     * Settles all outstanding updateAgent responders and yields until every update handler has finished, so update
     * results are delivered before the workflow method returns — on the failure path the workflow would otherwise
     * complete without ever scheduling the unblocked handler threads. Marks the context as closing so updates that
     * arrive during the yield are answered immediately instead of being enqueued. Idempotent; also called from the
     * workflow adapter as a backstop for failures outside the agent loop.
     *
     * @param info           the agent context state
     * @param failureMessage the agent's failure message, or null when the agent completed normally
     */
    public static void settleUpdates(AgentContextInfo info, String failureMessage) {
        info.closing = true;
        info.closingFailure = failureMessage;

        List<CompletablePromise<Object>> responders = new ArrayList<>();
        if (info.pendingResponder != null) {
            responders.add(info.pendingResponder);
            info.pendingResponder = null;
        }
        responders.addAll(info.signalWrapper.drainPendingResponders());
        for (CompletablePromise<Object> responder : responders) {
            if (responder.isCompleted()) {
                continue;
            }
            if (failureMessage != null) {
                responder.completeExceptionally(ApplicationFailure.newNonRetryableFailure(
                        "The agent finished without consuming this update: " + failureMessage, "error"));
            } else {
                responder.complete(info.finalResponse);
            }
        }
        // Yield so the unblocked handler threads run and deliver their update results
        // before the workflow method returns (critical on the failure path, where the
        // workflow would otherwise fail without scheduling them again).
        Workflow.await(Workflow::isEveryHandlerFinished);
    }

    /**
     * Waits durably for the agent's {@code chat} event, if the agent declared one. Returns the message string, or
     * null when no chat event is declared.
     *
     * @param handle the agent context handle
     * @return the chat message (BString), null, or a Ballerina error
     */
    public static Object awaitChatEvent(BHandle handle) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            if (info.eventNames == null || !info.eventNames.contains(CHAT_EVENT)) {
                return null;
            }
            Object data = awaitSignal(info, CHAT_EVENT);
            if (data instanceof TimedOut) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Timed out waiting for the initial 'chat' event"));
            }
            Object ballerina = TypesUtil.convertJavaToBallerinaType(data);
            if (ballerina instanceof BString bStr) {
                return bStr;
            }
            return StringUtils.fromString(String.valueOf(ballerina));
        } catch (NonDeterministicException | TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to await agent chat event: " + e.getMessage()));
        }
    }

    /**
     * Suspends the agent durably until the named data event arrives and returns its data. Backs the per-event
     * wait-tools advertised to the model. Honors the configured interaction semantics: FIFO-repeatable waits under
     * MULTI_EVENT, per-wait timeout (returned as a Ballerina error the loop feeds back to the model), and the
     * max-event-waits safety cap (thrown as a hard failure that ends the agent).
     *
     * @param handle    the agent context handle
     * @param eventName the event field name declared in the agent's signature
     * @return the event data, or a Ballerina error
     */
    public static Object awaitEvent(BHandle handle, BString eventName) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            String name = eventName.getValue();
            if (info.eventNames == null || !info.eventNames.contains(name)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Event '" + name + "' is not declared in the agent's signature."));
            }
            Object data = awaitSignal(info, name);
            if (data instanceof TimedOut) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Timed out waiting for event '" + name + "'"));
            }
            return TypesUtil.convertJavaToBallerinaType(data);
        } catch (NonDeterministicException | TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to await agent event: " + e.getMessage()));
        }
    }

    /** Sentinel returned by awaitSignal when the per-wait timeout elapses. */
    private static final class TimedOut {
        private static final TimedOut INSTANCE = new TimedOut();
    }

    /**
     * Waits for the next signal according to the configured interaction pattern. SINGLE_EVENT uses the legacy
     * one-shot promise; MULTI_EVENT takes from the FIFO channel so repeated waits observe successive signals.
     * Enforces the max-event-waits cap (hard failure) and the per-wait timeout (returns {@link TimedOut}).
     */
    private static Object awaitSignal(AgentContextInfo info, String eventName) throws Exception {
        info.eventWaitCount++;
        if (info.eventWaitCount > info.maxEventWaits) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Agent exceeded the maximum number of event waits (" + info.maxEventWaits
                            + "). Configure ctx.setInteraction(...) to raise the limit.", "AGENT_EVENT_WAIT_LIMIT");
        }

        CompletablePromise<SignalAwaitWrapper.SignalData> future = info.multiEvent
                ? info.signalWrapper.takeSignalFuture(eventName)
                : info.signalWrapper.getSignalFuture(eventName);

        if (info.eventTimeoutMillis != null) {
            boolean arrived = Workflow.await(Duration.ofMillis(info.eventTimeoutMillis),
                    future::isCompleted);
            if (!arrived) {
                // Remove the abandoned FIFO waiter so a later signal is not consumed silently.
                if (info.multiEvent) {
                    info.signalWrapper.cancelWaiter(eventName, future);
                }
                return TimedOut.INSTANCE;
            }
        } else {
            Workflow.await(future::isCompleted);
        }

        SignalAwaitWrapper.SignalData signalData = future.get();
        if (signalData.responder() != null) {
            // This message came from updateAgent: its responder is completed with the
            // answer of the turn now starting (the next recorded response).
            if (info.pendingResponder != null && !info.pendingResponder.isCompleted()) {
                info.pendingResponder.completeExceptionally(
                        ApplicationFailure.newNonRetryableFailure(
                                "The agent consumed another event before answering this update", "error"));
            }
            info.pendingResponder = signalData.responder();
        }
        return signalData.data();
    }

    /**
     * Starts the human-task sub-workflow registered under {@code taskName} and suspends the agent durably until a
     * person completes it. Reuses the same child-workflow machinery as {@code workflow:Context->awaitHumanTask}.
     *
     * @param handle   the agent context handle
     * @param taskName the registered task name
     * @param payload  the payload supplied by the model (shown to the person)
     * @return the completion result, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object awaitHumanTask(BHandle handle, BString taskName, Object payload) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        HumanTaskMeta meta = info.humanTasks.get(taskName.getValue());
        if (meta == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Human task '" + taskName.getValue() + "' is not registered on this agent."));
        }
        BMap<BString, Object> payloadMap = payload instanceof BMap
                ? (BMap<BString, Object>) payload
                : ValueCreator.createMapValue();
        return WorkflowContextNative.awaitHumanTask(null, taskName, meta.userRoles(), payloadMap,
                StringUtils.fromString(meta.title()), StringUtils.fromString(meta.description()),
                meta.timeout(), meta.resultType());
    }

    /**
     * Executes a registered agent activity tool (or a built-in activity such as {@code llmChat}) as a durable
     * Temporal activity, resolving the activity type from the current workflow. Mirrors the NoRetry path of
     * {@link WorkflowContextNative#callActivity} but resolves the activity by name rather than by function pointer.
     *
     * @param nameB the activity/tool name
     * @param args  named arguments
     * @param td    the expected return type (dependent typing)
     * @return the activity result coerced to {@code td}, or a Ballerina error
     */
    public static Object callActivity(BString nameB, BMap<BString, Object> args, BTypedesc td) {
        return executeActivity(nameB.getValue(), argsToJavaMap(args), td);
    }

    /**
     * Executes a registered activity tool by its advertised tool name: resolves the underlying activity function
     * (the tool name may be a registration-time override) and merges the registration-time bindings into the
     * model-supplied arguments — bindings win, so the model can never override a fixed value such as a connection
     * marker or a pinned HTTP method.
     *
     * @param handle    the agent context handle
     * @param toolNameB the advertised tool name from the model's tool call
     * @param args      model-supplied named arguments
     * @param td        the expected return type (dependent typing)
     * @return the activity result coerced to {@code td}, or a Ballerina error
     */
    public static Object callActivityTool(BHandle handle, BString toolNameB, BMap<BString, Object> args,
                                          BTypedesc td) {
        String toolName = toolNameB.getValue();
        String activityName = toolName;
        Object retryPolicy = null;
        Map<String, Object> namedArgs = argsToJavaMap(args);
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        for (ToolMeta tool : info.tools) {
            if (KIND_ACTIVITY.equals(tool.kind()) && tool.name().equals(toolName)) {
                if (tool.activityName() != null) {
                    activityName = tool.activityName();
                }
                if (tool.bindings() != null) {
                    namedArgs.putAll(tool.bindings());
                }
                retryPolicy = tool.retryPolicy();
                break;
            }
        }
        return executeActivity(activityName, namedArgs, td, retryPolicy);
    }

    private static Map<String, Object> argsToJavaMap(BMap<BString, Object> args) {
        Map<String, Object> namedArgs = new HashMap<>();
        for (BString key : args.getKeys()) {
            namedArgs.put(key.getValue(), TypesUtil.convertBallerinaToJavaType(args.get(key)));
        }
        return namedArgs;
    }

    private static Object executeActivity(String activityName, Map<String, Object> namedArgs, BTypedesc td) {
        return executeActivity(activityName, namedArgs, td, null);
    }

    /**
     * Runs a registered agent activity durably, applying its retry policy: {@code null} → single attempt (failure
     * reported to the model), an AutoRetry {@code BMap} → Temporal backoff retries, or the {@code "MANUAL_RETRY"}
     * sentinel → a rerun loop that creates a review activity on each failure (a human decides to rerun, rerun with
     * edited input, or fail — the AI cannot decide a manual retry itself).
     */
    @SuppressWarnings("unchecked")
    private static Object executeActivity(String activityName, Map<String, Object> namedArgs, BTypedesc td,
                                          Object retryPolicy) {
        String workflowType = Workflow.getInfo().getWorkflowType();
        String fullActivityName = workflowType + "." + activityName;
        boolean manualRetry = retryPolicy instanceof BString s && "MANUAL_RETRY".equals(s.getValue());
        boolean autoRetry = retryPolicy instanceof BMap;

        Map<String, Object> callConfig = new HashMap<>();
        callConfig.put(CALL_CONFIG_MARKER, true);
        callConfig.put(RETRY_ON_ERROR_KEY, autoRetry);

        RetryOptions retryOptions = autoRetry
                ? WorkflowContextNative.buildPerCallRetryOptions((BMap<BString, Object>) retryPolicy)
                : RetryOptions.newBuilder().setMaximumAttempts(1).build();
        ActivityOptions options = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(retryOptions)
                .build();
        io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(options);

        Map<String, Object> currentArgs = namedArgs;
        while (true) {
            try {
                Object result = stub.execute(fullActivityName, Object.class, new Object[]{currentArgs, callConfig});
                Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
                return TypesUtil.cloneWithType(ballerinaResult, td.getDescribingType());
            } catch (ActivityFailure e) {
                Throwable cause = e.getCause();
                String errorMsg = cause instanceof ApplicationFailure appFailure
                        ? appFailure.getOriginalMessage()
                        : (cause != null ? cause.getMessage() : e.getMessage());
                if (!manualRetry) {
                    return ErrorCreator.createError(StringUtils.fromString(errorMsg));
                }
                // Manual retry: a human reviews the failure and decides.
                Map<String, Object> decision = WorkflowContextNative.startReviewActivity(
                        "ON_FAILURE", fullActivityName, currentArgs, errorMsg, new String[0], null);
                String action = decision.containsKey("action") ? String.valueOf(decision.get("action")) : "reject";
                if ("proceed".equals(action)) {
                    continue;
                }
                if ("proceed-with-input".equals(action) && decision.get("input") instanceof Map<?, ?> in) {
                    currentArgs = (Map<String, Object>) in;
                    continue;
                }
                Object feedback = decision.get("feedback");
                String msg = feedback instanceof String fb && !fb.isBlank()
                        ? errorMsg + " (reviewer: " + fb + ")" : errorMsg;
                return ErrorCreator.createError(StringUtils.fromString(msg));
            } catch (NonDeterministicException | TemporalFailure e) {
                throw e;
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString("Agent activity failed: " + e.getMessage()));
            }
        }
    }

    // Derives a parameter JSON-schema map from a function's data parameters.
    private static Map<String, Object> parameterSchemaOf(BFunctionPointer fn) {
        return parameterSchemaOf(fn, Set.of(), null);
    }

    /**
     * Derives the model-facing parameter JSON schema, excluding parameters the model can never supply:
     * typedesc parameters, registration-time bound parameters, and client-object parameters. A required
     * (non-defaultable) client-object parameter that is not bound is a registration error — the model has no way to
     * provide a connection, so the user must fix a value via {@code bindings}.
     */
    private static Map<String, Object> parameterSchemaOf(BFunctionPointer fn, Set<String> boundNames,
                                                         String activityName) {
        FunctionType funcType = (FunctionType) fn.getType();
        Parameter[] allParams = funcType.getParameters();
        List<Parameter> dataParams = new ArrayList<>();
        if (allParams != null) {
            for (Parameter p : allParams) {
                if (p.type.getTag() == TypeTags.TYPEDESC_TAG || boundNames.contains(p.name)) {
                    continue;
                }
                if (WorkflowWorkerNative.isObjectParam(p)) {
                    if (!p.isDefault) {
                        throw new IllegalStateException("Parameter '" + p.name + "' of activity '"
                                + (activityName == null ? fn.getType().getName() : activityName)
                                + "' is a client object and cannot be supplied by the model. Bind it at "
                                + "registration: bindings = {" + p.name + ": <moduleLevelClient>}");
                    }
                    continue;
                }
                dataParams.add(p);
            }
        }
        Parameter[] params = dataParams.toArray(new Parameter[0]);
        return TypesUtil.toParameterSchemaMap(params, 0, params.length);
    }

    // Parses a JSON-schema string into the plain-map form used in tool definitions.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSchema(String schemaJson) {
        Object parsed = TypesUtil.convertBallerinaToJavaType(
                JsonUtils.parse(schemaJson));
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "object");
        return fallback;
    }
}
