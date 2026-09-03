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
import io.ballerina.lib.workflow.worker.ActivityNaming;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
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
 * Native implementations backing the durable agent context and the durable agent loop. The context travels
 * through Ballerina as a raw handle injected into the object-model runner workflow; there is no user-facing
 * agent context API.
 * <p>
 * The runner registers the agent's declared capabilities on the context — workflow activities
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
    /** Site prefixes of an agent's graph nodes — see {@code AgentGraphBuilder}. */
    private static final String AGENT_TOOL_SITE_PREFIX = "tool:";
    private static final String EXECUTE_AGENT_TOOL_ACTIVITY = "executeAgentTool";
    private static final String TOOL_NAME_ARG = "toolName";
    private static final String AGENT_TASK_SITE_PREFIX = "task:";
    /** The agent graph's model node: every built-in model call belongs to it, not to a tool. */
    private static final String AGENT_MODEL_SITE = "model";
    private static final Set<String> MODEL_ACTIVITIES = Set.of("llmChat", "generate", "generateResult");

    /**
     * The retry curve for the built-in model activities. A model call fails for reasons that
     * are overwhelmingly transient — a connection blip, a rate limit, a gateway hiccup — and
     * an agent is a long-lived conversation: letting one such blip fail the whole run threw
     * away hours of durable state over a second of network weather. Bounded twice, so a
     * genuinely dead provider still fails the step instead of retrying forever: one that
     * answers with errors exhausts the five attempts in about a minute (the backoff sums to
     * ~30s), and one that hangs runs into {@link #MODEL_TOTAL_TIMEOUT} — without that cap,
     * five attempts each cut off at the per-attempt timeout would stretch to ~25 minutes.
     * A failed run remains recoverable by reset once the cause is fixed.
     */
    private static final RetryOptions MODEL_RETRY_OPTIONS = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .setMaximumAttempts(5)
            .build();

    /**
     * Overall ceiling for one model step, attempts and backoff together (Temporal's
     * schedule-to-close). Ten minutes: room for one full-length attempt to hang, the curve
     * above to absorb blips around it, and a second long attempt — while keeping the bound
     * in minutes. Model calls only; a user activity's per-call {@code retryPolicy} keeps
     * whatever curve its author declared.
     */
    private static final Duration MODEL_TOTAL_TIMEOUT = Duration.ofMinutes(10);
    private static final String CHAT_EVENT = "chat";

    // Tool dispatch kinds understood by the Ballerina agent loop.
    private static final String KIND_ACTIVITY = "activity";
    private static final String KIND_AI_TOOL = "aitool";
    private static final String KIND_HUMAN_TASK = "humantask";
    private static final String KIND_EVENT_PREFIX = "event:";
    private static final String KIND_END = "end";
    private static final String KIND_SLEEP = "sleep";
    private static final String KIND_WORKFLOW_ID = "workflowid";
    private static final String KIND_CURRENT_TIME = "currenttime";
    private static final String SLEEP_TOOL = "sleep";
    private static final String WORKFLOW_ID_TOOL = "getWorkflowId";
    private static final String CURRENT_TIME_TOOL = "getCurrentTime";
    private static final String EVENT_TOOL_PREFIX = "awaitEvent_";
    private static final String END_CONVERSATION_TOOL = "endConversation";
    // Names of built-in tools published by getAgentToolDefs; user registrations must not
    // shadow them, or the model would see duplicate definitions with diverging dispatch.
    private static final java.util.Set<String> RESERVED_TOOL_NAMES =
            java.util.Set.of(SLEEP_TOOL, END_CONVERSATION_TOOL, WORKFLOW_ID_TOOL, CURRENT_TIME_TOOL);

    private static BError reservedToolNameError(String name) {
        return ErrorCreator.createError(StringUtils.fromString(
                "The tool name '" + name + "' is reserved for a built-in agent tool"));
    }

    /**
     * Rejects a capability name already taken on this agent context. Activities, AI tools, peers,
     * and human tasks are all advertised to the model under this one name, which is also what
     * dispatch keys on: a second registration would show the model two identical tools and
     * silently shadow the first. The compiler plugin rejects the duplicates it can see in a
     * declaration (WORKFLOW_150), but names registered on the context are not always statically
     * known — this is the check that always runs.
     *
     * @param info the agent context state
     * @param name the capability name being registered
     * @return an error when the name is already registered, otherwise null
     */
    private static BError duplicateCapabilityError(AgentContextInfo info, String name) {
        boolean taken = false;
        for (ToolMeta tool : info.tools) {
            if (tool.name().equals(name)) {
                taken = true;
                break;
            }
        }
        // Each declared channel is advertised as its own wait-tool, so those generated names
        // are taken as well even though no ToolMeta carries them.
        if (!taken && info.eventNames != null) {
            for (String eventName : info.eventNames) {
                if (name.equals(EVENT_TOOL_PREFIX + eventName)) {
                    taken = true;
                    break;
                }
            }
        }
        if (!taken) {
            return null;
        }
        return ErrorCreator.createError(StringUtils.fromString(
                "Duplicate capability name '" + name + "' on agent '" + info.workflowType
                        + "': activities, tools, human tasks, and events share one namespace, and '"
                        + name + "' is already registered. Give the capability a different name."));
    }

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
        // Where the loop is durably parked right now (a human-readable phrase, used verbatim
        // in side-turn park notes), or null while it is reasoning. parkedEventName is set
        // when the park is an event wait, so chat-wait parks keep normal update delivery.
        private String parkedOn = null;
        private String parkedEventName = null;
        private long parkedAtMillis = 0;
        // One side turn at a time: a second question waits for the first to answer.
        private boolean sideTurnActive = false;
        // The clean conversation transcript (system, user, and content-bearing assistant
        // messages) as Java values, published by the loop; side turns reason over it.
        private List<Object> chatTranscript = new ArrayList<>();
        // Question/answer pairs answered by side turns while the loop was parked; the loop
        // merges them into its history before its next model call.
        private final List<Map<String, Object>> asides = new ArrayList<>();

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

        void beginPark(String description, String eventName) {
            this.parkedOn = description;
            this.parkedEventName = eventName;
            this.parkedAtMillis = Workflow.currentTimeMillis();
        }

        void endPark() {
            this.parkedOn = null;
            this.parkedEventName = null;
        }

        /**
         * Whether an incoming update on the named channel should be answered by a side turn:
         * a conversational agent (a MULTI_EVENT chat channel), a chat message, and the loop
         * durably parked on something OTHER than the chat wait itself. A message arriving
         * while the loop waits on chat is the next turn — it takes the normal path.
         *
         * @param eventName the update's channel
         * @return true when a side turn should answer this update
         */
        public boolean sideTurnEligible(String eventName) {
            return multiEvent && CHAT_EVENT.equals(eventName)
                    && eventNames != null && eventNames.contains(CHAT_EVENT)
                    && parkedOn != null && !CHAT_EVENT.equals(parkedEventName);
        }

        public boolean sideTurnActive() {
            return sideTurnActive;
        }

        public void setSideTurnActive(boolean active) {
            this.sideTurnActive = active;
        }

        /**
         * Injects a data event into this agent's own signal queues, as if the event had
         * arrived externally. Used by the asynchronous peer-callback path.
         *
         * @param eventName the event channel name
         * @param data      the payload
         */
        public void recordEvent(String eventName, Object data) {
            signalWrapper.recordSignal(eventName, data);
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
     *                     a {@code HumanReview} record; {@code null} for non-activity tools
     * @param reviewRoles  role(s) permitted to decide this tool's approval reviews; empty when the tool declares
     *                     none, in which case the agent-level approval roles apply
     */
    private record ToolMeta(String name, String description, Map<String, Object> schema, String kind,
                            String activityName, Map<String, Object> bindings, boolean requiresApproval,
                            Object retryPolicy, String[] reviewRoles) {
        ToolMeta(String name, String description, Map<String, Object> schema, String kind) {
            this(name, description, schema, kind, null, null, false, null, new String[0]);
        }

        ToolMeta(String name, String description, Map<String, Object> schema, String kind,
                 String activityName, Map<String, Object> bindings) {
            this(name, description, schema, kind, activityName, bindings, false, null, new String[0]);
        }
    }

    private record HumanTaskMeta(Object userRoles, String title, String description, BTypedesc resultType,
                                 Object timeout, BTypedesc taskInputType) { }

    /**
     * Parses a per-tool reviewer-roles value (a BString for one role or a BArray of role strings) into a role
     * array; returns an empty array when the tool declares no roles so the agent-level approval roles apply.
     */
    private static String[] parseReviewRoles(Object userRolesArg) {
        if (userRolesArg instanceof BString role && !role.getValue().isBlank()) {
            return new String[]{role.getValue()};
        }
        if (userRolesArg instanceof BArray roleArray) {
            String[] roles = new String[(int) roleArray.size()];
            for (int i = 0; i < roles.length; i++) {
                roles[i] = String.valueOf(roleArray.get(i));
            }
            return roles;
        }
        return new String[0];
    }

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
            // No timeout means each event wait is open-ended — a chat session lives as long
            // as the conversation does. The maxEventWaits cap remains the runaway backstop.
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
            String[] reviewRoles = info.approvalUserRoles;
            for (ToolMeta tool : info.tools) {
                if (!tool.name().equals(name)) {
                    continue;
                }
                if (KIND_ACTIVITY.equals(tool.kind()) && tool.activityName() != null) {
                    activityName = tool.activityName();
                }
                // Declared per-tool roles (activities and AI tools alike) override the
                // agent-level approval roles.
                if (tool.reviewRoles().length > 0) {
                    reviewRoles = tool.reviewRoles();
                }
                break;
            }
            String workflowType = Workflow.getInfo().getWorkflowType();
            String reviewTaskName = ActivityNaming.reviewTaskNameFor(workflowType, activityName);
            String activityType = ActivityNaming.activityTypeFor(workflowType, activityName);

            Object parsedArgs = JsonUtils.parse(argsJson.getValue());
            Map<String, Object> argsMap = new LinkedHashMap<>();
            Object javaArgs = TypesUtil.convertBallerinaToJavaType(parsedArgs);
            if (javaArgs instanceof Map<?, ?> m) {
                argsMap.putAll((Map<String, Object>) m);
            }

            info.beginPark("a human approval decision for the gated tool '" + activityName + "'", null);
            Map<String, Object> decision;
            try {
                // The step id names the graph node the model called: the ADVERTISED tool name
                // (as callActivityTool uses), which differs from the underlying activity when a
                // registration-time override renames it. The review task name and activity type
                // keep the real activity, so the reviewer still sees what would run.
                decision = WorkflowContextNative.startReviewActivity(
                        "PRE_RUN", reviewTaskName, activityType, argsMap, "", reviewRoles,
                        info.approvalTimeoutMillis, AGENT_TOOL_SITE_PREFIX + name);
            } finally {
                info.endPark();
            }
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
                                            boolean requiresApproval, Object retryPolicy, Object userRolesArg) {
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
            // NoAutomaticRetry arrives as nil; AutoRetry and HumanReview are both records,
            // told apart downstream by `userRoles` (WorkflowContextNative.readHumanReview).
            Object policy = retryPolicy instanceof BMap ? retryPolicy : null;
            if (RESERVED_TOOL_NAMES.contains(toolName)) {
                return reservedToolNameError(toolName);
            }
            BError duplicate = duplicateCapabilityError(info, toolName);
            if (duplicate != null) {
                return duplicate;
            }
            info.tools.add(new ToolMeta(toolName, description, schema, KIND_ACTIVITY, activityName, bindings,
                    requiresApproval, policy, parseReviewRoles(userRolesArg)));
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
    /**
     * Records a peer-agent tool on the agent context: advertised to the model with a generic
     * {@code {query: string}} schema; the kind spec encodes the dispatch target
     * ({@code peeragent:<targetAgent>} or {@code peeragent:<targetAgent>#<callbackChannel>}
     * for asynchronous delegation).
     *
     * @param handle           the AgentContextInfo handle
     * @param name             the tool name advertised to the model
     * @param description      the tool description advertised to the model
     * @param kindSpec         the encoded peeragent kind
     * @param requiresApproval whether a PRE_RUN review gates each delegation
     * @return null on success, or a BError
     */
    public static Object recordPeerTool(BHandle handle, BString name, BString description, BString kindSpec,
                                        boolean requiresApproval) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("type", "string");
            query.put("description", "The task or question to delegate to the peer agent");
            properties.put("query", query);
            schema.put("properties", properties);
            schema.put("required", java.util.List.of("query"));
            if (RESERVED_TOOL_NAMES.contains(name.getValue())) {
                return reservedToolNameError(name.getValue());
            }
            BError duplicate = duplicateCapabilityError(info, name.getValue());
            if (duplicate != null) {
                return duplicate;
            }
            info.tools.add(new ToolMeta(name.getValue(), description.getValue(), schema, kindSpec.getValue(),
                    null, null, requiresApproval, null, new String[0]));
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to register peer agent tool: " + e.getMessage()));
        }
    }

    /**
     * Durable, interruptible sleep for the built-in agent sleep tool: a workflow-side timer
     * that ends early when the {@code __agent_wake} signal arrives (sent by the management
     * API). The wake request is consumed so it interrupts exactly one sleep.
     *
     * @param handle the agent context handle (unused; the current workflow sleeps)
     * @param millis how long to sleep
     * @return true when the timer ran to completion, false when a wake interrupted it,
     *         or a BError on failure
     */
    public static Object agentInterruptibleSleep(BHandle handle, long millis) {
        try {
            io.ballerina.lib.workflow.worker.WorkflowWorkerNative.awaitWhileSuspended();
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            info.beginPark("a timer (the built-in sleep tool)", null);
            boolean woken;
            try {
                woken = Workflow.await(java.time.Duration.ofMillis(millis),
                        io.ballerina.lib.workflow.worker.WorkflowWorkerNative::isWakeRequested);
            } finally {
                info.endPark();
            }
            if (woken) {
                io.ballerina.lib.workflow.worker.WorkflowWorkerNative.clearWakeRequest();
            }
            return !woken;
        } catch (io.temporal.worker.NonDeterministicException | io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Agent sleep failed: " + e.getMessage()));
        }
    }

    public static Object recordAiTool(BHandle handle, BFunctionPointer fn, BString name, BString description,
                                      Object parametersJson, boolean requiresApproval, Object userRolesArg,
                                      boolean mcpTool) {
        try {
            AgentContextInfo info = (AgentContextInfo) handle.getValue();
            Map<String, Object> schema;
            if (parametersJson instanceof BString schemaJson) {
                schema = parseSchema(schemaJson.getValue());
            } else {
                schema = parameterSchemaOf(fn);
            }
            if (RESERVED_TOOL_NAMES.contains(name.getValue())) {
                return reservedToolNameError(name.getValue());
            }
            BError duplicate = duplicateCapabilityError(info, name.getValue());
            if (duplicate != null) {
                return duplicate;
            }
            info.tools.add(new ToolMeta(name.getValue(), description.getValue(), schema, KIND_AI_TOOL,
                    null, null, requiresApproval, null, parseReviewRoles(userRolesArg)));
            WorkflowWorkerNative.putAgentTool(info.workflowType, name.getValue(), fn, mcpTool);
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
     * @param taskName      the task name (also the tool name advertised to the model)
     * @param userRoles     role or roles permitted to complete the task
     * @param resultType    the expected completion result type
     * @param title         optional short title
     * @param description   optional description (also the tool description)
     * @param timeout       optional {@code time:Duration} after which the task times out
     * @param taskInputType the declared input shape the model's payload is checked against,
     *                      or null for the open default
     * @return null on success, or a Ballerina error
     */
    public static Object recordHumanTaskTool(BHandle handle, BString taskName, Object userRoles,
                                             BTypedesc resultType, Object title, Object description,
                                             Object timeout, Object taskInputType) {
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
            if (RESERVED_TOOL_NAMES.contains(name)) {
                return reservedToolNameError(name);
            }
            BError duplicate = duplicateCapabilityError(info, name);
            if (duplicate != null) {
                return duplicate;
            }
            info.tools.add(new ToolMeta(name, descriptionStr, schema, KIND_HUMAN_TASK));
            info.humanTasks.put(name, new HumanTaskMeta(userRoles, titleStr, descriptionStr, resultType,
                    timeout instanceof BMap ? timeout : null,
                    taskInputType instanceof BTypedesc t ? t : null));
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
        // Durable sleep is always available: the timer is a workflow-side operation
        // (never an activity), so the agent survives restarts while sleeping.
        Map<String, Object> sleepSchema = new LinkedHashMap<>();
        sleepSchema.put("type", "object");
        Map<String, Object> sleepProperties = new LinkedHashMap<>();
        Map<String, Object> secondsProperty = new LinkedHashMap<>();
        secondsProperty.put("type", "integer");
        secondsProperty.put("description", "How long to sleep, in seconds");
        secondsProperty.put("minimum", 1);
        sleepProperties.put("seconds", secondsProperty);
        sleepSchema.put("properties", sleepProperties);
        sleepSchema.put("required", java.util.List.of("seconds"));
        defs.add(toolDef(SLEEP_TOOL,
                "Pauses this agent durably for the given number of seconds. The agent survives worker "
                        + "restarts while sleeping and resumes exactly where it left off; a wake signal "
                        + "from the management API ends the sleep early.",
                sleepSchema, KIND_SLEEP));
        // Workflow-context reads a plain workflow gets from ctx: the agent loop answers these
        // deterministically on the workflow thread, so no activity (and no worker slot) is spent.
        Map<String, Object> emptySchema = new LinkedHashMap<>();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new LinkedHashMap<>());
        defs.add(toolDef(WORKFLOW_ID_TOOL,
                "Returns this run's workflow instance ID - the durable reference identifier of this "
                        + "agent execution. Use it whenever the user or an external system needs a "
                        + "reference ID for this run or conversation.",
                emptySchema, KIND_WORKFLOW_ID));
        defs.add(toolDef(CURRENT_TIME_TOOL,
                "Returns the current date and time as an ISO-8601 UTC timestamp. This is the "
                        + "workflow's deterministic clock - always call this instead of guessing "
                        + "the current date or time.",
                emptySchema, KIND_CURRENT_TIME));
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
        // A channel is advertised to the model as a wait-tool, so it shares the capability
        // namespace: a repeated channel would advertise that tool twice, and a channel whose
        // wait-tool name is already taken would make dispatch ambiguous.
        if (info.eventNames.contains(eventName)) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Duplicate data-event channel '" + eventName + "' on agent '" + info.workflowType
                            + "': the channel is already registered."));
        }
        BError duplicate = duplicateCapabilityError(info, EVENT_TOOL_PREFIX + eventName);
        if (duplicate != null) {
            return duplicate;
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
            if (data instanceof BError) {
                return data;
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
    /**
     * Stores the loop's published conversation transcript — the clean view (system, user,
     * and content-bearing assistant messages) side turns reason over.
     *
     * @param handle the agent context handle
     * @param transcript the transcript as JSON (an AgentChatMessage array)
     */
    @SuppressWarnings("unchecked")
    public static void publishTranscript(BHandle handle, Object transcript) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        Object javaValue = TypesUtil.convertBallerinaToJavaType(transcript);
        List<Object> messages = new ArrayList<>();
        if (javaValue instanceof List<?> list) {
            messages.addAll((List<Object>) list);
        }
        info.chatTranscript = messages;
    }

    /**
     * Drains the question/answer pairs side turns answered while the loop was parked, as a
     * JSON array of {@code {question, answer}} — the loop merges them into its history
     * before its next model call, so the main conversation sees everything that was said.
     *
     * @param handle the agent context handle
     * @return a JSON array string
     */
    public static BString drainAsides(BHandle handle) {
        AgentContextInfo info = (AgentContextInfo) handle.getValue();
        if (info.asides.isEmpty()) {
            return StringUtils.fromString("[]");
        }
        List<Map<String, Object>> drained = new ArrayList<>(info.asides);
        info.asides.clear();
        return StringUtils.fromString(TypesUtil.toJsonString(drained));
    }

    /**
     * Answers one chat update with a side turn while the main loop is durably parked: one
     * bounded, tool-less model call over the published transcript plus a park-state note.
     * The update completes with the side answer — still exactly one response per request —
     * and the pair is recorded for the loop to merge into its history when it resumes.
     * This is what keeps a parked agent conversational (and breaks the mutual wait where
     * the agent holds for an event while the user holds for a reply before sending it).
     * <p>
     * Runs on the update handler's own workflow coroutine; the model call is a recorded
     * activity, so the whole side turn replays deterministically. Side turns never touch
     * the event queues, the turn pairing, or the event-wait budget.
     *
     * @param info the agent context state
     * @param payload the update's chat message
     * @return the side answer
     */
    public static String sideTurnAnswer(AgentContextInfo info, Object payload) {
        Object javaPayload = TypesUtil.convertBallerinaToJavaType(payload);
        String question = String.valueOf(javaPayload);
        String parkedDescription = info.parkedOn != null ? info.parkedOn : "its current step";
        String since = java.time.Instant.ofEpochMilli(
                info.parkedAtMillis > 0 ? info.parkedAtMillis : Workflow.currentTimeMillis()).toString();
        String parkNote = "You are the same assistant, answering a quick side question while your main "
                + "work is paused: you are currently waiting on " + parkedDescription + " (since " + since
                + "). Answer briefly from the conversation so far and this status. You cannot run tools "
                + "or take any action right now and must not claim to; the main work continues "
                + "automatically once the wait resolves.";

        List<Object> messages = new ArrayList<>(info.chatTranscript);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("role", "system");
        note.put("content", parkNote);
        messages.add(note);
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", question);
        messages.add(userMessage);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("agentName", info.workflowType);
        args.put("messages", messages);
        args.put("tools", new ArrayList<>());
        Map<String, Object> callConfig = new LinkedHashMap<>();
        callConfig.put(CALL_CONFIG_MARKER, true);
        callConfig.put(RETRY_ON_ERROR_KEY, false);
        callConfig.put(WorkflowContextNative.STEP_ID_KEY, AGENT_MODEL_SITE);

        String answer = null;
        try {
            ActivityOptions options = ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .setSummary(AGENT_MODEL_SITE)
                    .build();
            io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(options);
            String activityType = ActivityNaming.activityTypeFor(info.workflowType, "llmChat");
            Object result = stub.execute(activityType, Object.class, new Object[]{args, callConfig});
            if (result instanceof Map<?, ?> reply && reply.get("content") instanceof String content
                    && !content.isBlank()) {
                answer = content;
            }
        } catch (NonDeterministicException e) {
            throw e;
        } catch (Exception e) {
            // The side answer must never fail the user's update: fall through to the
            // deterministic status line below.
        }
        if (answer == null) {
            // The model was unavailable or tried to act anyway — answer with the status the
            // framework knows for certain.
            answer = "I'm still working on it - currently waiting on " + parkedDescription
                    + ". I'll pick the conversation back up as soon as that resolves.";
        }
        Map<String, Object> aside = new LinkedHashMap<>();
        aside.put("question", question);
        aside.put("answer", answer);
        info.asides.add(aside);
        return answer;
    }

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
            if (data instanceof BError) {
                return data;
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
            // Returned as a Ballerina error (not thrown): a Java failure crossing the
            // Ballerina boundary loses its message. The loop recognizes this message
            // prefix and fails the agent instead of feeding it to the model.
            return ErrorCreator.createError(StringUtils.fromString(
                    "Agent exceeded the maximum number of event waits (" + info.maxEventWaits + ")."));
        }

        CompletablePromise<SignalAwaitWrapper.SignalData> future = info.multiEvent
                ? info.signalWrapper.takeSignalFuture(eventName)
                : info.signalWrapper.getSignalFuture(eventName);

        // Publish the wait to the execution memo/history (as for workflow data-event waits in
        // TemporalFutureValue) so diagrams can render where the agent is halted; cleared as soon
        // as the wait unblocks. A buffered event that completes the wait instantly is skipped —
        // the agent never actually blocked.
        boolean tracked = !future.isCompleted();
        if (tracked) {
            WaitingEventsTracker.beginWait(eventName);
        }
        info.beginPark("the external event '" + eventName + "'", eventName);
        try {
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
        } finally {
            info.endPark();
            if (tracked) {
                WaitingEventsTracker.endWait(eventName);
            }
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
        // The declared taskInputType gates the agent path too: the model supplies this input,
        // so the check the workflow surface runs before creating a task runs here as well.
        // The mismatch goes back as a tool error, which the loop feeds to the model as text —
        // the task is never created with input a person would see and distrust.
        BError inputMismatch = WorkflowContextNative.validateTaskInputShape(
                payloadMap, meta.taskInputType(), taskName.getValue());
        if (inputMismatch != null) {
            return inputMismatch;
        }
        info.beginPark("a person to complete the task '" + taskName.getValue() + "'", null);
        try {
            return WorkflowContextNative.awaitHumanTaskExploded(null, taskName, meta.userRoles(), payloadMap,
                    StringUtils.fromString(meta.title()), StringUtils.fromString(meta.description()),
                    meta.timeout(), meta.resultType(),
                    StringUtils.fromString(AGENT_TASK_SITE_PREFIX + taskName.getValue()));
        } finally {
            info.endPark();
        }
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
        String name = nameB.getValue();
        Map<String, Object> namedArgs = argsToJavaMap(args);
        // An AI-function tool runs inside the executeAgentTool wrapper, so the node this
        // execution belongs to in the agent's graph is the advertised tool named in the
        // arguments — never the wrapper's own name, which is machinery the graph doesn't show.
        String site = null;
        if (EXECUTE_AGENT_TOOL_ACTIVITY.equals(name)
                && namedArgs.get(TOOL_NAME_ARG) instanceof String toolName) {
            site = AGENT_TOOL_SITE_PREFIX + toolName;
        }
        return executeActivity(name, namedArgs, td, null, site);
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
        // The graph names the tool by its advertised name; a registration-time override may run
        // it as a differently-named activity, but the site stays the tool the model called.
        return executeActivity(activityName, namedArgs, td, retryPolicy, AGENT_TOOL_SITE_PREFIX + toolName);
    }

    private static Map<String, Object> argsToJavaMap(BMap<BString, Object> args) {
        Map<String, Object> namedArgs = new HashMap<>();
        for (BString key : args.getKeys()) {
            namedArgs.put(key.getValue(), TypesUtil.convertBallerinaToJavaType(args.get(key)));
        }
        return namedArgs;
    }

    /**
     * Runs a registered agent activity durably, applying its retry policy: {@code null} → single attempt (failure
     * reported to the model), an {@code AutoRetry} record → Temporal backoff retries, or a {@code HumanReview}
     * record → a rerun loop that creates a review activity on each failure, listed and worded as that record
     * declares (a human decides to rerun, rerun with edited input, or fail — the AI cannot decide this itself).
     */
    @SuppressWarnings("unchecked")
    private static Object executeActivity(String activityName, Map<String, Object> namedArgs, BTypedesc td,
                                          Object retryPolicy, String site) {
        String workflowType = Workflow.getInfo().getWorkflowType();
        String fullActivityName = ActivityNaming.activityTypeFor(workflowType, activityName);
        // Both retry-policy records are mappings, so a HumanReview is told from an AutoRetry
        // by its `userRoles` — see WorkflowContextNative.readHumanReview.
        WorkflowContextNative.ReviewDeclaration reviewPolicy =
                WorkflowContextNative.readHumanReview(retryPolicy);
        boolean manualRetry = reviewPolicy != null;
        boolean autoRetry = reviewPolicy == null && retryPolicy instanceof BMap;

        // The built-in model activities recover automatically: they are framework machinery,
        // not user tools, so no declaration site exists where an author could attach a policy.
        boolean modelCall = MODEL_ACTIVITIES.contains(activityName);

        Map<String, Object> callConfig = new HashMap<>();
        callConfig.put(CALL_CONFIG_MARKER, true);
        callConfig.put(RETRY_ON_ERROR_KEY, autoRetry || modelCall);
        // An agent has no lexical call site — the model chose this tool — so the site is the
        // node the call belongs to in the agent's graph: the caller's explicit site when the
        // activity runs under another identity (a wrapped AI tool, an overridden tool name),
        // else the model for a built-in model call, else the tool itself.
        String stepId = site != null ? site
                : MODEL_ACTIVITIES.contains(activityName)
                        ? AGENT_MODEL_SITE : AGENT_TOOL_SITE_PREFIX + activityName;
        callConfig.put(WorkflowContextNative.STEP_ID_KEY, stepId);

        RetryOptions retryOptions = autoRetry
                ? WorkflowContextNative.buildPerCallRetryOptions((BMap<BString, Object>) retryPolicy)
                : modelCall ? MODEL_RETRY_OPTIONS
                : RetryOptions.newBuilder().setMaximumAttempts(1).build();
        ActivityOptions.Builder optionsBuilder = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(retryOptions)
                .setSummary(stepId);
        if (modelCall) {
            // The per-attempt timeout bounds one attempt; this bounds the whole step, or a
            // provider that hangs rather than errors would hold the run for attempts × 5min.
            optionsBuilder.setScheduleToCloseTimeout(MODEL_TOTAL_TIMEOUT);
        }
        ActivityOptions options = optionsBuilder.build();
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
                // Manual retry: a human reviews the failure and decides. The declaration is
                // honoured here as it is on a workflow's own callActivity — its roles used to
                // be dropped on this path, so an agent tool's review was answerable by anyone.
                Map<String, Object> decision = WorkflowContextNative.startReviewActivity(
                        "ON_FAILURE", ActivityNaming.reviewTaskNameFor(workflowType, activityName),
                        fullActivityName, currentArgs, errorMsg, reviewPolicy.userRoles(),
                        reviewPolicy.timeoutMillis(), stepId,
                        reviewPolicy.title(), reviewPolicy.description());
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
        // Honor declared defaults: a defaultable parameter is one the model may omit, and
        // the descriptor's compile-time schema (DescriptorSchemaGen.parameterSlot) leaves it
        // out of `required` too. Passing false here made the two disagree for non-nilable
        // defaultable parameters.
        return TypesUtil.toParameterSchemaMap(params, 0, params.length, true);
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
