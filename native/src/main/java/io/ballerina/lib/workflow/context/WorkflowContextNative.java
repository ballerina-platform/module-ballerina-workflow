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

import io.ballerina.lib.workflow.ModuleUtils;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowLocal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native implementation for workflow context operations. Provides workflow-specific operations like sleep, state
 * queries, and activity execution.
 *
 * <p>ARCHITECTURE NOTES:
 * <ul>
 *   <li>Per-Instance ServiceObject: Each workflow execution gets its own ServiceObject instance
 *       (created in WorkflowWorkerNative.createServiceInstance()) to avoid state sharing between
 *       workflow instances, including during replay scenarios.</li>
 *   <li>Context objects are created per workflow execution and hold workflow-specific information.</li>
 *   <li>Activity execution is done via ctx.callActivity() remote method on the Context client.</li>
 *   <li>Signal handling is done via Ballerina's wait action with event futures.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class WorkflowContextNative {

    // Key used to mark a call configuration map passed as the last activity argument
    private static final String CALL_CONFIG_MARKER = "__callConfig__";
    private static final String RETRY_ON_ERROR_KEY = "retryOnError";

    private WorkflowContextNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Execute an activity function within the workflow context.
     * <p>
     * This is the remote method implementation for ctx->callActivity(). Activities are non-deterministic operations
     * that should only be executed once during workflow execution (not during replay).
     * <p>
     * The method uses dependent typing - the return type is determined by the typedesc parameter and the result is
     * converted using cloneWithType.
     * <p>
     * The {@code retryPolicy} parameter controls failure behaviour:
     * <ul>
     *   <li>{@code null} / NoRetry — the error is returned as a Ballerina value; no retry.</li>
     *   <li>AutoRetry BMap — Temporal automatic backoff retry using the configured fields.</li>
     *   <li>ManualRetry string sentinel — on failure a built-in RetryTask child workflow is
     *       started; execution blocks until a human decides to retry, retry with different
     *       input, or permanently fail the activity. Task name is derived from the activity.</li>
     * </ul>
     *
     * @param self             the Context BObject (self reference from Ballerina)
     * @param activityFunction the activity function to execute
     * @param args             the map&lt;anydata&gt; args containing arguments to pass to the activity
     * @param typedesc         the expected return type descriptor for dependent typing
     * @param retryPolicy      null for NoRetry, AutoRetry BMap, or ManualRetry string sentinel
     * @return the result of the activity execution converted to the expected type, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object callActivity(BObject self, BFunctionPointer activityFunction, BMap<BString, Object> args,
                                      BTypedesc typedesc, Object retryPolicy) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            String simpleActivityName = activityFunction.getType().getName();
            String workflowType = Workflow.getInfo().getWorkflowType();
            String fullActivityName = workflowType + "." + simpleActivityName;

            Map<String, Object> namedArgs = convertArgsMapWithConnectionMarkers(args);

            // Classify the retry policy: a string or string list is a ManualRetry policy
            // carrying the reviewer role(s); a mapping is AutoRetry; nil is NoRetry.
            boolean isManualRetry = retryPolicy instanceof BString
                    || retryPolicy instanceof io.ballerina.runtime.api.values.BArray;
            String[] manualRetryRoles = isManualRetry ? extractManualRetryRoles(retryPolicy) : new String[0];
            boolean isAutoRetry = false;
            BMap<BString, Object> retryPolicyMap = null;
            if (!isManualRetry && retryPolicy instanceof BMap<?, ?>) {
                retryPolicyMap = (BMap<BString, Object>) retryPolicy;
                isAutoRetry = true;
            }

            // Build the call config map forwarded to the activity adapter
            Map<String, Object> callConfig = new HashMap<>();
            callConfig.put(CALL_CONFIG_MARKER, true);
            callConfig.put(RETRY_ON_ERROR_KEY, isAutoRetry);

            if (isManualRetry) {
                // Manual retry: run activity in a loop; on failure start a RetryTask
                // child workflow and wait for a human decision.
                return executeWithManualRetry(fullActivityName, workflowType, namedArgs, callConfig,
                        manualRetryRoles, typedesc);
            }

            // AutoRetry or NoRetry — single Temporal activity invocation
            io.temporal.activity.ActivityOptions.Builder optionsBuilder =
                    io.temporal.activity.ActivityOptions.newBuilder().setStartToCloseTimeout(
                            java.time.Duration.ofMinutes(5));

            if (!isAutoRetry) {
                optionsBuilder.setRetryOptions(
                        io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build());
            } else {
                optionsBuilder.setRetryOptions(buildPerCallRetryOptions(retryPolicyMap));
            }

            io.temporal.workflow.ActivityStub activityStub = Workflow.newUntypedActivityStub(optionsBuilder.build());

            Object result = activityStub.execute(fullActivityName, Object.class, new Object[]{namedArgs, callConfig});

            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
            return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());

        } catch (io.temporal.failure.ActivityFailure e) {
            Throwable cause = e.getCause();
            String errorMsg;
            if (cause instanceof io.temporal.failure.ApplicationFailure appFailure) {
                errorMsg = appFailure.getOriginalMessage();
            } else {
                errorMsg = cause != null ? cause.getMessage() : e.getMessage();
            }
            return ErrorCreator.createError(StringUtils.fromString(errorMsg));
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Activity execution failed: " + e.getMessage()));
        }
    }

    /**
     * Executes the given activity in a loop, starting a built-in RetryTask child workflow whenever the activity fails,
     * and repeating based on the human's decision.
     * <p>
     * Loop exits when:
     * <ul>
     *   <li>The activity succeeds — result is returned.</li>
     *   <li>The human chooses {@code "fail"} — the original error is returned.</li>
     * </ul>
     * Between attempts the human can choose {@code "retry"} (same args) or
     * {@code "retry-with-input"} (override args map).
     */
    @SuppressWarnings("unchecked")
    // Reads the reviewer role(s) from a ManualRetry policy value: a string is one role, a
    // string list is several; the legacy "MANUAL_RETRY" sentinel means any role.
    private static String[] extractManualRetryRoles(Object retryPolicy) {
        if (retryPolicy instanceof BString roleString) {
            String value = roleString.getValue();
            return "MANUAL_RETRY".equals(value) ? new String[0] : new String[]{value};
        }
        if (retryPolicy instanceof io.ballerina.runtime.api.values.BArray roleArray) {
            String[] roles = new String[(int) roleArray.size()];
            for (int i = 0; i < roles.length; i++) {
                roles[i] = String.valueOf(roleArray.get(i));
            }
            return roles;
        }
        return new String[0];
    }

    private static Object executeWithManualRetry(String fullActivityName, String workflowType,
                                                 Map<String, Object> initialArgs, Map<String, Object> callConfig,
                                                 String[] reviewerRoles, BTypedesc typedesc) {

        io.temporal.activity.ActivityOptions activityOptions =
                io.temporal.activity.ActivityOptions.newBuilder().setStartToCloseTimeout(
                        java.time.Duration.ofMinutes(5)).setRetryOptions(
                        io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build()).build();
        io.temporal.workflow.ActivityStub activityStub = Workflow.newUntypedActivityStub(activityOptions);

        Map<String, Object> currentArgs = initialArgs;
        String lastErrorMsg = null;

        while (true) {
            try {
                WorkflowWorkerNative.awaitWhileSuspended();
                Object result = activityStub.execute(fullActivityName, Object.class,
                                                     new Object[]{currentArgs, callConfig});
                Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
                return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());

            } catch (io.temporal.failure.ActivityFailure e) {
                Throwable cause = e.getCause();
                if (cause instanceof io.temporal.failure.ApplicationFailure appFailure) {
                    lastErrorMsg = appFailure.getOriginalMessage();
                } else {
                    lastErrorMsg = cause != null ? cause.getMessage() : e.getMessage();
                }
            }

            // Activity failed — start a RetryTask child workflow and await the human decision
            Map<String, Object> decision = callBuiltinRetryTask(fullActivityName, currentArgs, lastErrorMsg,
                                                                reviewerRoles);

            String action = decision.containsKey("action") ? String.valueOf(decision.get("action")) : "reject";

            switch (action) {
                case "proceed" -> {
                    // Re-run with the same arguments
                }
                case "proceed-with-input" -> {
                    // Merge the reviewer's edits over the existing arguments: keys present in the
                    // edited input override, omitted keys keep their last-used values — so a form
                    // that submits only the corrected fields does not drop the rest.
                    Object newInput = decision.get("input");
                    if (newInput instanceof Map<?, ?> inputMap) {
                        Map<String, Object> merged = new HashMap<>(currentArgs);
                        inputMap.forEach((key, value) -> merged.put(String.valueOf(key), value));
                        currentArgs = merged;
                    }
                    // else: keep existing args (safety fallback)
                }
                default -> {
                    // "reject" or any unknown action — surface the original error, appending the
                    // reviewer's feedback when present.
                    Object feedback = decision.get("feedback");
                    String base = lastErrorMsg != null ? lastErrorMsg
                            : "Activity failed and the review decision was 'reject'";
                    String message = feedback instanceof String fb && !fb.isBlank()
                            ? base + " (reviewer: " + fb + ")" : base;
                    return ErrorCreator.createError(StringUtils.fromString(message));
                }
            }
        }
    }

    /**
     * Starts a built-in review-activity child workflow and blocks until a human sends a {@code "taskDecision"}
     * signal. Returns the signal payload map ({@code action}, optionally {@code input}/{@code feedback}).
     * <p>Used for the on-failure manual-retry path (via {@link #callBuiltinRetryTask}); the pre-run
     * approval gate (PRE_RUN) shares this starter when gated-activity policies land.
     *
     * @param trigger          {@code "PRE_RUN"} (approval gate) or {@code "ON_FAILURE"} (rerun decision)
     * @param fullActivityName the qualified activity name (also used as the task name)
     * @param activityArgs     the proposed (or last-attempted) arguments, shown to the reviewer
     * @param errorMessage     the failure message for ON_FAILURE, or empty/null for PRE_RUN
     * @param userRoles        roles permitted to decide (empty → any role)
     * @param timeoutMillis    max wait for a decision, or null to wait indefinitely
     * @return the decision map
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> startReviewActivity(String trigger, String fullActivityName,
                                                   Map<String, Object> activityArgs, String errorMessage,
                                                   String[] userRoles, Long timeoutMillis) {
        WorkflowWorkerNative.awaitWhileSuspended();

        String qualifiedTaskName = fullActivityName;
        String parentWorkflowId = Workflow.getInfo().getWorkflowId();
        String reviewId = "reviewactivity-" + Workflow.randomUUID();

        WorkflowWorkerNative.ensureRetryTaskRegistered();

        String[] roles = userRoles != null ? userRoles : new String[0];

        // Title and description distinguish the review trigger for task inboxes: a failed
        // activity awaiting a rerun decision reads differently from a pre-run approval gate.
        boolean onFailure = "ON_FAILURE".equals(trigger);
        String title = onFailure
                ? "Review failed activity: " + fullActivityName
                : "Approval required: " + fullActivityName;
        String description = onFailure
                ? "Activity '" + fullActivityName + "' failed with: "
                        + (errorMessage != null && !errorMessage.isBlank() ? errorMessage : "an unknown error")
                        + ". Proceed to rerun it with the original input, proceed with edited input, "
                        + "or reject to surface the failure to the workflow."
                : "Activity '" + fullActivityName + "' is awaiting approval before it runs. "
                        + "Proceed to run it with the proposed input, proceed with edited input, "
                        + "or reject to skip the call.";

        // Memo — readable without fetching full history
        Map<String, Object> memo = new HashMap<>();
        memo.put("workflowKind", "REVIEW_ACTIVITY");
        memo.put("trigger", trigger);
        memo.put("activityName", fullActivityName);
        memo.put("taskName", qualifiedTaskName);
        memo.put("title", title);
        memo.put("description", description);
        memo.put("parentWorkflowId", parentWorkflowId);
        memo.put("errorMessage", errorMessage != null ? errorMessage : "");
        memo.put("activityArgs", activityArgs);
        memo.put("userRoles", roles);
        memo.put("createdAt", java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
        memo.put("formSchema", deriveReviewInputSchema(fullActivityName, activityArgs));

        // Input passed into the child workflow's execute()
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("activityName", fullActivityName);
        inputs.put("taskName", qualifiedTaskName);
        inputs.put("parentWorkflowId", parentWorkflowId);
        inputs.put("errorMessage", errorMessage != null ? errorMessage : "");
        inputs.put("activityArgs", activityArgs);

        // REQUEST_CANCEL (not TERMINATE) so a review retired by its parent closing ends as
        // CANCELED — distinguishable from an admin terminating the task (ballerina-library#8892).
        io.temporal.workflow.ChildWorkflowOptions.Builder optsBuilder =
                io.temporal.workflow.ChildWorkflowOptions.newBuilder().setWorkflowId(reviewId).setParentClosePolicy(
                        io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL).setMemo(memo);
        if (timeoutMillis != null && timeoutMillis > 0) {
            optsBuilder.setWorkflowExecutionTimeout(java.time.Duration.ofMillis(timeoutMillis));
        }

        io.temporal.workflow.ChildWorkflowStub childStub = Workflow.newUntypedChildWorkflowStub(
                WorkflowWorkerNative.RETRYTASK_WORKFLOW_TYPE, optsBuilder.build());

        try {
            Object rawResult = childStub.execute(Object.class, inputs);
            if (rawResult instanceof Map<?, ?> resultMap) {
                return (Map<String, Object>) resultMap;
            }
        } catch (io.temporal.failure.ChildWorkflowFailure e) {
            // Timed out (or otherwise ended) without a decision — treat as reject and say so.
            Map<String, Object> timedOut = new HashMap<>();
            timedOut.put("action", "reject");
            timedOut.put("feedback", "the review timed out before a human decided");
            return timedOut;
        }
        // Fallback: treat any unexpected result as reject
        Map<String, Object> failDecision = new HashMap<>();
        failDecision.put("action", "reject");
        return failDecision;
    }

    // On-failure manual-retry review (the ManualRetry policy). Delegates to the shared starter.
    private static Map<String, Object> callBuiltinRetryTask(String fullActivityName, Map<String, Object> activityArgs,
                                                            String errorMessage, String[] reviewerRoles) {
        return startReviewActivity("ON_FAILURE", fullActivityName, activityArgs, errorMessage,
                reviewerRoles, null);
    }

    /**
     * Builds the JSON Schema for a review activity's {@code proceed-with-input} form: an object whose properties are
     * the reviewed activity's data parameters (ballerina-library#8895). Preferred source is the registered activity
     * function's signature (accurate types); when the activity is not in this JVM's registry (e.g. an agent tool
     * closure) the schema is derived from the recorded argument values instead. The values themselves are served
     * separately as {@code activityArgs} so a form can be pre-filled.
     */
    private static String deriveReviewInputSchema(String fullActivityName, Map<String, Object> activityArgs) {
        try {
            BFunctionPointer fn = WorkflowWorkerNative.getActivityRegistry().get(fullActivityName);
            if (fn != null && fn.getType() instanceof FunctionType funcType) {
                Parameter[] allParams = funcType.getParameters();
                List<Parameter> dataParams = new ArrayList<>();
                if (allParams != null) {
                    for (Parameter p : allParams) {
                        // Skip non-data parameters a reviewer can never supply: typedescs and
                        // client objects (connections are bound at registration, not per call).
                        if (p.type.getTag() == TypeTags.TYPEDESC_TAG || WorkflowWorkerNative.isObjectParam(p)) {
                            continue;
                        }
                        dataParams.add(p);
                    }
                }
                Parameter[] params = dataParams.toArray(new Parameter[0]);
                // Honor parameter defaults: a defaultable activity parameter need not be supplied
                // by the reviewer, so it must not appear in the schema's `required` list.
                return TypesUtil.toJsonSchemaForParameters(params, 0, params.length, true);
            }
        } catch (Exception e) {
            // Fall through to the value-derived schema below.
        }

        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        if (activityArgs != null) {
            for (Map.Entry<String, Object> entry : activityArgs.entrySet()) {
                Map<String, Object> prop = new java.util.LinkedHashMap<>();
                String jsonType = jsonTypeOf(entry.getValue());
                if (jsonType != null) {
                    prop.put("type", jsonType);
                }
                properties.put(entry.getKey(), prop);
            }
        }
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        return TypesUtil.toJsonString(root);
    }

    // Maps a recorded argument value to its JSON Schema type name, or null when unknown (nil values).
    private static String jsonTypeOf(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List || (value != null && value.getClass().isArray())) {
            return "array";
        }
        return null;
    }

    /**
     * Builds Temporal {@link io.temporal.common.RetryOptions} from an {@code AutoRetry} BMap. Fields:
     * {@code maxRetries}, {@code retryDelay}, {@code retryBackoff}, {@code maxRetryDelay}.
     *
     * @param autoRetryMap the AutoRetry BMap passed as retryPolicy
     * @return configured RetryOptions
     */
    static io.temporal.common.RetryOptions buildPerCallRetryOptions(BMap<BString, Object> autoRetryMap) {
        io.temporal.common.RetryOptions.Builder builder = io.temporal.common.RetryOptions.newBuilder();

        // maxRetries → maximumAttempts (maxRetries=0 means 1 total attempt, no retries)
        Object maxRetriesVal = autoRetryMap.get(StringUtils.fromString("maxRetries"));
        int maxRetries = 3; // AutoRetry default
        if (maxRetriesVal instanceof Long longVal) {
            maxRetries = Math.toIntExact(longVal);
        }
        builder.setMaximumAttempts(maxRetries + 1);

        // retryDelay → initialInterval (decimal seconds)
        Object retryDelayVal = autoRetryMap.get(StringUtils.fromString("retryDelay"));
        if (retryDelayVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double delaySeconds = bDecimal.floatValue();
            if (delaySeconds > 0) {
                builder.setInitialInterval(java.time.Duration.ofMillis((long) (delaySeconds * 1000)));
            }
        }

        // retryBackoff → backoffCoefficient
        Object retryBackoffVal = autoRetryMap.get(StringUtils.fromString("retryBackoff"));
        if (retryBackoffVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double backoff = bDecimal.floatValue();
            if (backoff >= 1.0) {
                builder.setBackoffCoefficient(backoff);
            }
        }

        // maxRetryDelay → maximumInterval (optional, decimal seconds)
        Object maxRetryDelayVal = autoRetryMap.get(StringUtils.fromString("maxRetryDelay"));
        if (maxRetryDelayVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double maxDelaySeconds = bDecimal.floatValue();
            if (maxDelaySeconds > 0) {
                builder.setMaximumInterval(java.time.Duration.ofMillis((long) (maxDelaySeconds * 1000)));
            }
        }

        return builder.build();
    }

    /**
     * Converts an activity {@code args} BMap to a Java map for Temporal serialization, replacing any {@link BObject}
     * value with the marker string {@code "connection:<name>"}.
     * <p>
     * The map type at the Ballerina level is {@code map<anydata|object {}>}: only client-object values are non-anydata
     * and they cannot cross the Temporal boundary. The compiler plugin has already validated at the call site that any
     * such value is a module-level {@code final} {@code client object} reference and that {@code registerConnection}
     * has been emitted for it during module init, so the registry lookup is expected to succeed.
     *
     * @param args the raw BMap passed to {@code callActivity}
     * @return a serializable Java map with connection refs replaced by markers
     * @throws RuntimeException if a {@link BObject} value is not registered; this surfaces as a workflow-side error in
     *                          the catch block above.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> convertArgsMapWithConnectionMarkers(BMap<BString, Object> args) {
        Map<String, Object> result = new HashMap<>();
        for (BString key : args.getKeys()) {
            Object value = args.get(key);
            if (value instanceof BObject bObject) {
                String name = WorkflowWorkerNative.getConnectionName(bObject);
                if (name == null) {
                    throw new RuntimeException("Activity argument '" + key.getValue() + "' is a client object " +
                                                       "that has not been registered as a module-level " +
                                                       "connection. Only module-level `final` `client object` " +
                                                       "variables may be passed to activities.");
                }
                result.put(key.getValue(), WorkflowWorkerNative.CONNECTION_MARKER_PREFIX + name);
            } else {
                result.put(key.getValue(), TypesUtil.convertBallerinaToJavaType(value));
            }
        }
        return result;
    }

    /**
     * Create a new context info object. This is called when creating a new workflow context.
     *
     * @param workflowId   the workflow ID
     * @param workflowType the workflow type name
     * @return a ContextInfo object
     */
    public static Object createContext(String workflowId, String workflowType) {
        return new ContextInfo(workflowId, workflowType);
    }

    /**
     * Sleep for a specified duration in milliseconds.
     *
     * @param contextHandle Context handle
     * @param millis        Duration in milliseconds
     * @return null on success, error on failure
     */
    public static Object sleepMillis(Object contextHandle, long millis) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            Workflow.sleep(Duration.ofMillis(millis));
            return null;
        } catch (io.temporal.worker.NonDeterministicException | io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Workflow sleep failed: " + e.getMessage()));
        }
    }

    /**
     * Returns the current workflow time as epoch milliseconds.
     * <p>
     * The workflow engine records the timestamp at each workflow task and provides it via
     * {@code Workflow.currentTimeMillis()}. This value is replayed identically, making it safe to use inside workflow
     * functions.
     *
     * @param contextHandle Context handle
     * @return epoch milliseconds as a long
     */
    public static long currentTimeMillis(Object contextHandle) {
        return Workflow.currentTimeMillis();
    }

    /**
     * Check if the workflow is currently replaying history.
     *
     * @param contextHandle Context handle
     * @return true if replaying, false otherwise
     */
    public static boolean isReplaying(Object contextHandle) {
        return Workflow.isReplaying();
    }

    /**
     * Get the workflow ID.
     *
     * @param contextHandle Context handle
     * @return the workflow ID as BString
     */
    public static Object getWorkflowId(Object contextHandle) {
        try {
            if (contextHandle instanceof ContextInfo) {
                return StringUtils.fromString(((ContextInfo) contextHandle).workflowId());
            }
            io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
            return StringUtils.fromString(info.getWorkflowId());
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get workflow ID: " + e.getMessage()));
        }
    }

    /**
     * Get the workflow type.
     *
     * @param contextHandle Context handle
     * @return the workflow type as BString
     */
    public static Object getWorkflowType(Object contextHandle) {
        try {
            if (contextHandle instanceof ContextInfo) {
                return StringUtils.fromString(((ContextInfo) contextHandle).workflowType());
            }
            io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
            return StringUtils.fromString(info.getWorkflowType());
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get workflow type: " + e.getMessage()));
        }
    }

    /**
     * Starts a built-in human task child workflow and blocks until a human completes it (via a {@code "taskCompletion"}
     * signal) or an optional timeout elapses.
     *
     * <p>The child workflow type equals {@code taskName}, which must have been registered
     * in the {@code HUMANTASK_REGISTRY} via {@code WorkflowWorkerNative.registerHumanTask} before the worker started.
     * {@code awaitHumanTask} also performs a lazy in-workflow registration so that ad-hoc calls work without
     * compile-time plugin support.
     *
     * <p>On success the {@code result} field of the signal payload is coerced to the
     * caller's {@code typedesc T} and returned.
     *
     * <p>When {@code timeout} is absent (nil) the workflow waits indefinitely.
     * When a timeout is set and fires, a {@code HumanTaskTimeoutError} distinct error is returned.
     *
     * @param self           the Context BObject (unused; present for Ballerina calling convention)
     * @param taskNameBStr   identifies the task type; used as the Temporal workflow type
     * @param userRolesObj   one or more roles permitted to complete this task (BString or BArray)
     * @param payloadObj     read-only JSON object rendered next to the form (BMap or null)
     * @param titleObj       short summary shown in the inbox; defaults to taskName when null
     * @param descriptionObj additional context shown alongside the form (BString or null)
     * @param timeoutObj     maximum wait duration (BMap time:Duration or null for indefinite)
     * @param typedesc       the expected result type descriptor (for dependent-typing and coercion)
     * @return the coerced result value, or a {@code HumanTaskTimeoutError} BError
     */
    @SuppressWarnings("unchecked")
    public static Object awaitHumanTask(BObject self, BString taskNameBStr, Object userRolesObj,
                                        BMap<BString, Object> payloadObj, Object titleObj, Object descriptionObj,
                                        Object timeoutObj, BTypedesc typedesc) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            // --- Extract individual params -------------------------------------------
            String taskName = taskNameBStr.getValue();

            // taskName must be non-blank and must not contain '.' (qualifier separator) or '|' (timeout msg separator)
            if (taskName.isBlank()) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "HumanTask taskName must not be blank", "HUMANTASK_CONFIG_ERROR");
            }
            if (taskName.contains(".") || taskName.contains("|")) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "HumanTask taskName '" + taskName + "' must not contain '.' or '|'", "HUMANTASK_CONFIG_ERROR");
            }

            // userRoles: can be BString (single role) or BArray<BString> (multiple roles)
            java.util.List<String> userRoles = new java.util.ArrayList<>();
            if (userRolesObj instanceof io.ballerina.runtime.api.values.BArray rolesArray) {
                for (int i = 0; i < rolesArray.size(); i++) {
                    userRoles.add(rolesArray.get(i).toString());
                }
            } else if (userRolesObj instanceof BString roleStr) {
                userRoles.add(roleStr.getValue());
            }

            // title defaults to taskName when absent/null
            String title = (titleObj instanceof BString bs) ? bs.getValue() : taskName;

            // description
            String description = (descriptionObj instanceof BString bs) ? bs.getValue() : "";

            // payload (always a BMap since Ballerina default = {} guarantees non-null)
            Object payload = payloadObj;

            // timeout: nil (BNull/null) means wait indefinitely
            Long timeoutMillis = null;
            if (timeoutObj instanceof BMap) {
                timeoutMillis = computeTimeoutMillis((BMap<BString, Object>) timeoutObj);
            }

            // --- Build child workflow identity ---------------------------------------
            String parentWorkflowId = Workflow.getInfo().getWorkflowId();
            // Strip the "workflow-" prefix from the current type to get the user-facing name.
            String rawWorkflowType = Workflow.getInfo().getWorkflowType();
            String workflowDefinitionName = rawWorkflowType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX) ?
                                            rawWorkflowType.substring(
                                                    WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) :
                                            rawWorkflowType;
            // Display name stored in memo (user-facing, e.g. "procurementApproval.approveRequest")
            String qualifiedTaskName = workflowDefinitionName + "." + taskName;
            // Temporal WorkflowType: prefixed so internal tasks are separate from user workflows
            String humanTaskTypeName = "humantask-" + qualifiedTaskName;

            // --- Ensure the human task workflow type is registered ------------------
            // Lazy registration covers ad-hoc / test usage without compiler-plugin support.
            if (!WorkflowWorkerNative.getHumanTaskRegistry().contains(humanTaskTypeName)) {
                WorkflowWorkerNative.registerHumanTask(StringUtils.fromString(humanTaskTypeName));
            }
            // Remember the expected result type so completeHumanTask can validate the completion payload before
            // the task is completed (ballerina-library#8866).
            WorkflowWorkerNative.registerHumanTaskResultType(humanTaskTypeName, typedesc.getDescribingType());

            // Compact instance ID: "humantask-" + UUID7 (deterministic across replays)
            String taskWorkflowId = "humantask-" + Workflow.randomUUID();

            // --- Memo (immutable, readable without full history) --------------------
            Map<String, Object> memo = new HashMap<>();
            memo.put("workflowKind", "HUMAN_TASK");
            memo.put("taskName", qualifiedTaskName);
            memo.put("parentWorkflowId", parentWorkflowId);
            memo.put("parentWorkflowType", workflowDefinitionName);
            memo.put("title", title);
            memo.put("description", description);
            memo.put("userRoles", userRoles);
            memo.put("payload", TypesUtil.convertBallerinaToJavaType(payload));
            memo.put("createdAt", Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
            memo.put("formSchema", TypesUtil.toJsonSchema(typedesc.getDescribingType()));

            // --- Build input map passed to the child workflow -----------------------
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("taskName", qualifiedTaskName);
            inputs.put("title", title);
            inputs.put("description", description);
            inputs.put("userRoles", userRoles);
            inputs.put("payload", TypesUtil.convertBallerinaToJavaType(payload));
            // null means no timeout (wait indefinitely)
            inputs.put("timeoutMillis", timeoutMillis);
            inputs.put("parentWorkflowId", parentWorkflowId);
            inputs.put("workflowDefinitionName", workflowDefinitionName);

            // --- Start child workflow and block until completion --------------------
            // REQUEST_CANCEL (not TERMINATE) so a task retired by its parent closing ends as
            // CANCELED — distinguishable from an admin terminating the task (ballerina-library#8892).
            ChildWorkflowOptions childOptions = ChildWorkflowOptions
                    .newBuilder()
                    .setWorkflowId(taskWorkflowId)
                    .setParentClosePolicy(
                            io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                    .setMemo(memo)
                    .build();

            ChildWorkflowStub childStub = Workflow.newUntypedChildWorkflowStub(humanTaskTypeName, childOptions);

            Object rawResult = childStub.execute(Object.class, inputs);

            // --- Extract the "result" field from the signal payload -----------------
            // Signal payload shape: { completedBy: {...}, result: <json> }
            Object formResult = extractResultField(rawResult);

            // Coerce to the caller's typedesc T. Use validateAndConvert (not cloneWithType) so a nil result
            // against a non-nilable T yields a proper error instead of a nil that panics with a TypeCastError
            // at the Java→Ballerina boundary (ballerina-library#8866).
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(formResult);
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.validateAndConvert(ballerinaResult, targetType);

        } catch (ChildWorkflowFailure e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApplicationFailure af && WorkflowWorkerNative.HUMANTASK_TIMEOUT_FAILURE_TYPE.equals(
                    af.getType())) {
                return buildTimeoutError(af.getOriginalMessage());
            }
            // Rejection via the management `fail` operation — the task workflow failed with the
            // rejection reason as the failure message (ballerina-library#8892).
            if (cause instanceof ApplicationFailure af
                    && WorkflowWorkerNative.HUMANTASK_REJECTED_FAILURE_TYPE.equals(af.getType())) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Human task rejected: " + af.getOriginalMessage()));
            }
            // Some other child workflow failure — surface as a generic error
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            return ErrorCreator.createError(StringUtils.fromString("Human task failed: " + msg));

        } catch (io.temporal.worker.NonDeterministicException | io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("awaitHumanTask failed: " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // awaitHumanTask
    // -----------------------------------------------------------------------

    /**
     * Converts a {@code time:Duration} BMap to total milliseconds as a {@code long}. Returns {@code null} to indicate
     * "no timeout" when the duration map is absent.
     */
    @SuppressWarnings("unchecked")
    static Long computeTimeoutMillis(BMap<BString, Object> duration) {
        if (duration == null) {
            return null; // no timeout — wait indefinitely
        }
        long years = getLongField(duration, "years");
        long months = getLongField(duration, "months");
        if (years != 0 || months != 0) {
            throw new IllegalArgumentException("HumanTask timeout does not support months or years");
        }
        long days = getLongField(duration, "days");
        long hours = getLongField(duration, "hours");
        long minutes = getLongField(duration, "minutes");
        double seconds = getDoubleField(duration, "seconds");
        long milliSeconds = getLongField(duration, "milliSeconds");
        long millis = Math.addExact(Math.addExact(days * 86_400_000L, hours * 3_600_000L),
                                    Math.addExact(minutes * 60_000L, Math.round(seconds * 1000) + milliSeconds));
        if (millis < 0) {
            throw new IllegalArgumentException("HumanTask timeout must be non-negative");
        }
        return millis;
    }

    private static long getLongField(BMap<BString, Object> map, String key) {
        Object val = map.get(StringUtils.fromString(key));
        if (val instanceof Long l) {
            return l;
        }
        if (val instanceof io.ballerina.runtime.api.values.BDecimal bd) {
            return bd.value().longValue();
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static double getDoubleField(BMap<BString, Object> map, String key) {
        Object val = map.get(StringUtils.fromString(key));
        if (val instanceof Double d) {
            return d;
        }
        if (val instanceof io.ballerina.runtime.api.values.BDecimal bd) {
            return bd.value().doubleValue();
        }
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /**
     * Extracts the {@code result} field from the signal completion payload. Uses {@code containsKey} so that an
     * explicit {@code null} result (tasks completed with no input value) is returned as {@code null} rather than
     * falling back to the whole payload map. If the payload is not a Map or has no "result" key, the raw value is
     * returned as-is.
     */
    @SuppressWarnings("unchecked")
    private static Object extractResultField(Object rawResult) {
        if (rawResult instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (map.containsKey("result")) {
                return map.get("result"); // may be null — valid for tasks with no input
            }
        }
        return rawResult;
    }

    /**
     * Builds a Ballerina {@code HumanTaskTimeoutError} from the pipe-delimited message encoded by
     * {@code executeBuiltinHumanTask}. Format: {@code taskName|taskWorkflowId|timedOutAfter|timedOutAt}
     */
    private static BError buildTimeoutError(String msg) {
        String[] parts = msg == null ? new String[0] : msg.split("\\|", -1);
        String taskName = parts.length > 0 ? parts[0] : "unknown";
        String taskWorkflowId = parts.length > 1 ? parts[1] : "unknown";
        String timedOutAfter = parts.length > 2 ? parts[2] : "unknown";
        String timedOutAt = parts.length > 3 ? parts[3] : "unknown";

        BMap<BString, Object> detail = io.ballerina.runtime.api.creators.ValueCreator.createMapValue();
        detail.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        detail.put(StringUtils.fromString("taskWorkflowId"), StringUtils.fromString(taskWorkflowId));
        detail.put(StringUtils.fromString("timedOutAfter"), StringUtils.fromString(timedOutAfter));
        detail.put(StringUtils.fromString("timedOutAt"), StringUtils.fromString(timedOutAt));

        try {
            return ErrorCreator.createError(ModuleUtils.getModule(), "HumanTaskTimeoutError", StringUtils.fromString(
                    "Human task '" + taskName + "' timed out after " + timedOutAfter), null, detail);
        } catch (Exception e) {
            // Fallback if the module type hasn't been initialised yet (e.g. in unit tests)
            return ErrorCreator.createError(StringUtils.fromString(
                    "HumanTaskTimeoutError: Human task '" + taskName + "' timed out after " + timedOutAfter), detail);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Child workflow composition (ctx->runChildWorkflow / getChildWorkflowResult /
    // waitForChildWorkflow / callWorkflow / sendDataToChildWorkflow)
    // -----------------------------------------------------------------------------------------

    private static final String CHILD_WORKFLOW_ID_PREFIX = "childwf-";
    private static final String WORKFLOW_BUSY_ERROR = "WorkflowBusyError";
    private static final String CHILD_WORKFLOW_KIND = "CHILD_WORKFLOW";

    /**
     * Child workflow handles started by the current workflow execution, keyed by the child workflow ID returned
     * from {@code runChildWorkflow}. {@link WorkflowLocal} scopes the map to the workflow execution — during
     * replay {@code runChildWorkflow} re-executes deterministically (same {@code Workflow.randomUUID()} ids) and
     * repopulates the map, so result reads always find the stub/promise pair belonging to this run.
     */
    private static final WorkflowLocal<Map<String, ChildWorkflowHandle>> CHILD_HANDLES =
            WorkflowLocal.withCachedInitial(HashMap::new);

    /**
     * A started child workflow.
     *
     * @param stub   the untyped child workflow stub
     * @param result the promise of the child's result
     */
    private record ChildWorkflowHandle(ChildWorkflowStub stub, Promise<Object> result) { }

    /**
     * Starts a child workflow and returns its instance ID without waiting for the result. The child is a true
     * Temporal child workflow (REQUEST_CANCEL parent-close policy), so in-flight children are cancelled when the
     * parent closes. The handle is retained so {@code getChildWorkflowResult}/{@code waitForChildWorkflow} can
     * await the result promise and correlate by the returned ID.
     *
     * @param self          the Context BObject (self reference from Ballerina)
     * @param childWorkflow the child workflow function (annotated with @Workflow; validated by the compiler plugin)
     * @param input         the optional input for the child (nil or any anydata value)
     * @return the child workflow instance ID as a Ballerina string, or a BError if the child could not start
     */
    public static Object runChildWorkflow(BObject self, BFunctionPointer childWorkflow, Object input) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            String functionName = childWorkflow.getType().getName();
            String childId = CHILD_WORKFLOW_ID_PREFIX + functionName + "-" + Workflow.randomUUID();
            ChildWorkflowStub stub = newChildStub(functionName, childId);
            Object javaInput = input == null ? null : TypesUtil.convertBallerinaToJavaType(input);
            Promise<Object> result = stub.executeAsync(Object.class, javaInput);
            // Block (durably) until the child has actually started, so a start failure (e.g. an
            // unregistered workflow type or a duplicate workflow ID) surfaces here, not at the read.
            stub.getExecution().get();
            CHILD_HANDLES.get().put(childId, new ChildWorkflowHandle(stub, result));
            return StringUtils.fromString(childId);
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (CanceledFailure e) {
            throw e;
        } catch (ChildWorkflowFailure e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to start child workflow: " + childFailureMessage(e)));
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to start child workflow: " + e.getMessage()));
        }
    }

    /**
     * Returns the result of a child workflow started with {@code runChildWorkflow} if it has already completed,
     * without waiting. While the child is still running a {@code workflow:WorkflowBusyError} is returned.
     * <p>
     * {@link Promise#isCompleted()} is a non-blocking peek and is deterministic: at any given point in workflow
     * code the event-loop state (and therefore the promise state) is identical between the original execution and
     * replay, because history events are processed in the same workflow-task batches.
     *
     * @param self            the Context BObject (self reference from Ballerina)
     * @param childWorkflowId the child workflow instance ID returned by {@code runChildWorkflow}
     * @param typedesc        the expected result type descriptor for dependent typing
     * @return the typed result, a WorkflowBusyError while running, or a BError if the child failed
     */
    public static Object getChildWorkflowResult(BObject self, BString childWorkflowId, BTypedesc typedesc) {
        try {
            ChildWorkflowHandle handle = CHILD_HANDLES.get().get(childWorkflowId.getValue());
            if (handle == null) {
                return unknownChildWorkflowError(childWorkflowId.getValue());
            }
            if (!handle.result().isCompleted()) {
                return createWorkflowBusyError(childWorkflowId.getValue());
            }
            return readChildResult(handle, typedesc);
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read child workflow result: " + e.getMessage()));
        }
    }

    /**
     * Durably waits until a child workflow started with {@code runChildWorkflow} completes and returns its
     * result. The wait suspends the workflow (no thread held) and is crash-resumable: on replay the result is
     * served from history.
     *
     * @param self            the Context BObject (self reference from Ballerina)
     * @param childWorkflowId the child workflow instance ID returned by {@code runChildWorkflow}
     * @param typedesc        the expected result type descriptor for dependent typing
     * @return the typed result, or a BError if the child failed
     */
    public static Object waitForChildWorkflow(BObject self, BString childWorkflowId, BTypedesc typedesc) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            ChildWorkflowHandle handle = CHILD_HANDLES.get().get(childWorkflowId.getValue());
            if (handle == null) {
                return unknownChildWorkflowError(childWorkflowId.getValue());
            }
            return readChildResult(handle, typedesc);
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read child workflow result: " + e.getMessage()));
        }
    }

    /**
     * Starts a child workflow and durably waits for its result — {@code runChildWorkflow} followed by
     * {@code waitForChildWorkflow} fused into one call.
     *
     * @param self          the Context BObject (self reference from Ballerina)
     * @param childWorkflow the child workflow function (annotated with @Workflow; validated by the compiler plugin)
     * @param input         the optional input for the child (nil or any anydata value)
     * @param typedesc      the expected result type descriptor for dependent typing
     * @return the typed result, or a BError if the child failed
     */
    public static Object callWorkflow(BObject self, BFunctionPointer childWorkflow, Object input,
                                      BTypedesc typedesc) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            String functionName = childWorkflow.getType().getName();
            String childId = CHILD_WORKFLOW_ID_PREFIX + functionName + "-" + Workflow.randomUUID();
            ChildWorkflowStub stub = newChildStub(functionName, childId);
            Object javaInput = input == null ? null : TypesUtil.convertBallerinaToJavaType(input);
            Object raw = stub.execute(Object.class, javaInput);
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(raw);
            return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (CanceledFailure e) {
            throw e;
        } catch (ChildWorkflowFailure e) {
            return ErrorCreator.createError(StringUtils.fromString(childFailureMessage(e)));
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Child workflow execution failed: " + e.getMessage()));
        }
    }

    /**
     * Sends data to a running workflow instance's events record from inside a workflow. Implemented with an
     * external workflow stub (a {@code signalExternalWorkflow} command), which is deterministic and works for any
     * workflow instance ID — typically a child started with {@code runChildWorkflow}, but not necessarily.
     *
     * @param self            the Context BObject (self reference from Ballerina)
     * @param childWorkflowId the target workflow instance ID
     * @param dataName        the events-record field name (used as the signal name)
     * @param data            the payload (any anydata value)
     * @return null on success, or a BError (e.g. when the target workflow does not exist)
     */
    public static Object sendDataToChildWorkflow(BObject self, BString childWorkflowId, BString dataName,
                                                 Object data) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            Object javaData = TypesUtil.convertBallerinaToJavaType(data);
            ExternalWorkflowStub stub = Workflow.newUntypedExternalWorkflowStub(childWorkflowId.getValue());
            stub.signal(dataName.getValue(), javaData);
            return null;
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (CanceledFailure e) {
            throw e;
        } catch (SignalExternalWorkflowException e) {
            // The target does not exist or is already closed — an application-level error the
            // caller can handle, not a workflow-task failure.
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to send data to workflow '" + childWorkflowId.getValue() + "': "
                            + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())));
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to send data to workflow '" + childWorkflowId.getValue() + "': " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------------------------
    // Durable agent (object model) child support — used by DurableAgentNative when
    // DurableAgent.run / result reads execute inside a workflow.
    // -----------------------------------------------------------------------------------------

    private static final String CHILD_AGENT_ID_PREFIX = "childagent-";

    /**
     * Starts a durable agent instance as a true Temporal child workflow of the current workflow.
     * The agent's workflow type is {@code workflow-<agentName>} and the handle is retained so
     * {@code getResult}/{@code waitForResult} can await the child's result promise by the
     * returned instance ID.
     *
     * @param agentName the agent name (module-level variable name)
     * @param runInput  the runner input map ({agentName, query, input})
     * @return the child instance ID as a Ballerina string, or a BError
     */
    public static Object startDurableAgentChild(String agentName, Map<String, Object> runInput) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            String childId = CHILD_AGENT_ID_PREFIX + agentName + "-" + Workflow.randomUUID();
            ChildWorkflowStub stub = newChildStub(agentName, childId);
            Promise<Object> result = stub.executeAsync(Object.class, runInput);
            stub.getExecution().get();
            CHILD_HANDLES.get().put(childId, new ChildWorkflowHandle(stub, result));
            return StringUtils.fromString(childId);
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (CanceledFailure e) {
            throw e;
        } catch (ChildWorkflowFailure e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to start durable agent '" + agentName + "': " + childFailureMessage(e)));
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to start durable agent '" + agentName + "': " + e.getMessage()));
        }
    }

    /**
     * Reads the result of a durable agent child started with {@code startDurableAgentChild}.
     * Non-blocking reads return a {@code workflow:AgentBusyError} while the child is running;
     * blocking reads durably suspend until it completes.
     *
     * @param childId  the child instance ID returned by {@code startDurableAgentChild}
     * @param typedesc the expected result type descriptor
     * @param blocking whether to durably wait for completion
     * @return the typed result, an AgentBusyError (non-blocking, still running), or a BError
     */
    public static Object readDurableAgentChildResult(String childId, BTypedesc typedesc, boolean blocking) {
        try {
            if (blocking) {
                WorkflowWorkerNative.awaitWhileSuspended();
            }
            ChildWorkflowHandle handle = CHILD_HANDLES.get().get(childId);
            if (handle == null) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Unknown durable agent instance '" + childId + "': result reads inside a workflow "
                                + "are only available for agents started with run() in this workflow execution"));
            }
            if (!blocking && !handle.result().isCompleted()) {
                return io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative
                        .createAgentBusyError(childId);
            }
            return readChildResult(handle, typedesc);
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read the result of durable agent instance '" + childId + "': "
                            + e.getMessage()));
        }
    }

    /**
     * Reads a durable-agent child's result without type binding: the raw final response converted
     * to its natural Ballerina value. Used by the peer-agent dispatch, whose contract is anydata.
     *
     * @param childId  the child instance ID
     * @param blocking whether to durably wait for completion
     * @return the child's result as a Ballerina value, an AgentBusyError (non-blocking), or a BError
     */
    public static Object readDurableAgentChildRaw(String childId, boolean blocking) {
        try {
            if (blocking) {
                WorkflowWorkerNative.awaitWhileSuspended();
            }
            ChildWorkflowHandle handle = CHILD_HANDLES.get().get(childId);
            if (handle == null) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Unknown peer agent instance '" + childId + "'"));
            }
            if (!blocking && !handle.result().isCompleted()) {
                return io.ballerina.lib.workflow.runtime.nativeimpl.DurableAgentNative
                        .createAgentBusyError(childId);
            }
            try {
                return TypesUtil.convertJavaToBallerinaType(handle.result().get());
            } catch (ChildWorkflowFailure e) {
                return ErrorCreator.createError(StringUtils.fromString(childFailureMessage(e)));
            }
        } catch (io.temporal.worker.NonDeterministicException e) {
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read the result of peer agent instance '" + childId + "': " + e.getMessage()));
        }
    }

    /**
     * Arms the asynchronous peer-callback path: a detached workflow task awaits the peer child's
     * result and injects it into the calling agent's own callback event channel, as if the event
     * had arrived externally. The model consumes it later with the channel's wait tool.
     *
     * @param ctxHandle       the calling agent's AgentContextInfo handle
     * @param childId         the peer child instance ID (also the correlation id in the payload)
     * @param callbackChannel the declared event channel that receives the peer's reply
     * @return null on success, or a BError when the child handle is unknown
     */
    public static Object armPeerAgentCallback(io.ballerina.runtime.api.values.BHandle ctxHandle,
                                              BString childId, BString callbackChannel) {
        ChildWorkflowHandle handle = CHILD_HANDLES.get().get(childId.getValue());
        if (handle == null) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Unknown peer agent instance '" + childId.getValue() + "'"));
        }
        io.ballerina.lib.workflow.context.AgentContextNative.AgentContextInfo info =
                (io.ballerina.lib.workflow.context.AgentContextNative.AgentContextInfo) ctxHandle.getValue();
        String channel = callbackChannel.getValue();
        String correlationId = childId.getValue();
        io.temporal.workflow.Async.procedure(() -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("correlationId", correlationId);
            try {
                payload.put("response", handle.result().get());
            } catch (Exception e) {
                payload.put("error", e.getMessage() != null ? e.getMessage() : "the peer agent failed");
            }
            info.recordEvent(channel, payload);
        });
        return null;
    }

    /**
     * Builds an untyped child workflow stub for the given @Workflow function name. The child workflow type uses
     * the same user-workflow prefix as {@code workflow:run}, so it resolves against the worker's process
     * registry. REQUEST_CANCEL (not TERMINATE) ties the child's lifecycle to the parent while letting it end as
     * CANCELED, and the memo carries the parent linkage for the management/tree views.
     */
    private static ChildWorkflowStub newChildStub(String functionName, String childId) {
        String childType = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + functionName;

        Map<String, Object> memo = new HashMap<>();
        memo.put("workflowKind", CHILD_WORKFLOW_KIND);
        memo.put("parentWorkflowId", Workflow.getInfo().getWorkflowId());
        memo.put("createdAt", Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());

        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(childId)
                .setParentClosePolicy(
                        io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                .setMemo(memo)
                .build();
        return Workflow.newUntypedChildWorkflowStub(childType, options);
    }

    /**
     * Awaits a completed (or completing) child result promise and converts it to the expected Ballerina type.
     * A failed child surfaces as a BError carrying the child's application error message.
     */
    private static Object readChildResult(ChildWorkflowHandle handle, BTypedesc typedesc) {
        try {
            Object raw = handle.result().get();
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(raw);
            return TypesUtil.cloneWithType(ballerinaResult, typedesc.getDescribingType());
        } catch (ChildWorkflowFailure e) {
            return ErrorCreator.createError(StringUtils.fromString(childFailureMessage(e)));
        }
    }

    /**
     * Extracts the application-level failure message from a child workflow failure, mirroring the activity
     * failure handling in {@code callActivity}.
     */
    private static String childFailureMessage(ChildWorkflowFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure appFailure) {
            return appFailure.getOriginalMessage();
        }
        return cause != null ? cause.getMessage() : e.getMessage();
    }

    /**
     * Builds a Ballerina {@code workflow:WorkflowBusyError} indicating the child is still running.
     */
    private static BError createWorkflowBusyError(String childWorkflowId) {
        String message = "Child workflow '" + childWorkflowId + "' is still running";
        try {
            return ErrorCreator.createError(ModuleUtils.getModule(), WORKFLOW_BUSY_ERROR,
                    StringUtils.fromString(message), null, null);
        } catch (Exception e) {
            // Fallback if the module type hasn't been initialised yet (e.g. in unit tests)
            return ErrorCreator.createError(StringUtils.fromString(WORKFLOW_BUSY_ERROR + ": " + message));
        }
    }

    private static BError unknownChildWorkflowError(String childWorkflowId) {
        return ErrorCreator.createError(StringUtils.fromString(
                "Unknown child workflow ID '" + childWorkflowId + "': result reads are only available "
                        + "for children started with runChildWorkflow in this workflow execution"));
    }

    /**
     * Context information holder. Stores workflow-specific context information.
     *
     * @param workflowId   the workflow ID
     * @param workflowType the workflow type
     */
    public record ContextInfo(String workflowId, String workflowType) { }
}
