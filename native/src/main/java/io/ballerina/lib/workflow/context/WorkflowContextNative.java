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
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Native implementation for workflow context operations.
 * Provides workflow-specific operations like sleep, state queries, and activity execution.
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

    private WorkflowContextNative() {
        // Utility class, prevent instantiation
    }

    // Key used to mark a call configuration map passed as the last activity argument
    private static final String CALL_CONFIG_MARKER = "__callConfig__";
    private static final String RETRY_ON_ERROR_KEY = "retryOnError";

    /**
     * Execute an activity function within the workflow context.
     * <p>
     * This is the remote method implementation for ctx->callActivity().
     * Activities are non-deterministic operations that should only be executed
     * once during workflow execution (not during replay).
     * <p>
     * The method uses dependent typing - the return type is determined by the typedesc
     * parameter and the result is converted using cloneWithType.
     * <p>
     * By default ({@code retryOnError = false}), if the activity function returns an error,
     * it is passed back to the workflow as a normal return value. Setting
     * {@code retryOnError} to {@code true} enables Temporal retries based on the per-call
     * policy ({@code maxRetries}, {@code retryDelay}, {@code retryBackoff}, {@code maxRetryDelay}).
     *
     * @param self the Context BObject (self reference from Ballerina)
     * @param activityFunction the activity function to execute
     * @param args the map<anydata> args containing arguments to pass to the activity
     * @param options ActivityOptions record with retryOnError, maxRetries, retryDelay, retryBackoff, maxRetryDelay
     * @param typedesc the expected return type descriptor for dependent typing
     * @return the result of the activity execution converted to the expected type, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object callActivity(BObject self, BFunctionPointer activityFunction, 
            BMap<BString, Object> args, BTypedesc typedesc, BMap<BString, Object> options) {
        try {
            // Get the activity name from the function pointer
            String simpleActivityName = activityFunction.getType().getName();
            
            // Get the current workflow type from Temporal context to build the full activity name
            // Activities are registered as "workflowType.activityName"
            String workflowType = Workflow.getInfo().getWorkflowType();
            String fullActivityName = workflowType + "." + simpleActivityName;

            // Convert args map (BMap) to a Java Map for Temporal serialization.
            // We pass the entire named map as a single argument so that the
            // BallerinaActivityAdapter can reconstruct positional args using the
            // function's parameter names. This avoids misalignment when optional
            // parameters are omitted from the args map.
            //
            // Client object values (declared parameter type is `client object`)
            // cannot be serialized, so they are first replaced with the marker
            // string "connection:<name>" via the connection registry. The
            // activity-side adapter resolves the marker back to the BObject
            // before invoking the activity function.
            Map<String, Object> namedArgs = convertArgsMapWithConnectionMarkers(args);

            // Parse ActivityOptions from the included record param
            boolean retryOnError = false;

            // Extract retryOnError flag (default: false — errors are returned as values)
            Object retryOnErrorVal = options.get(StringUtils.fromString(RETRY_ON_ERROR_KEY));
            if (retryOnErrorVal instanceof Boolean) {
                retryOnError = (Boolean) retryOnErrorVal;
            }

            io.temporal.activity.ActivityOptions.Builder optionsBuilder =
                    io.temporal.activity.ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(java.time.Duration.ofMinutes(5));

            if (!retryOnError) {
                // retryOnError=false: disable Temporal retries so errors surface as return values
                optionsBuilder.setRetryOptions(
                        io.temporal.common.RetryOptions.newBuilder()
                                .setMaximumAttempts(1)
                                .build());
            } else {
                // retryOnError=true: build retry policy from per-call flat fields
                optionsBuilder.setRetryOptions(buildPerCallRetryOptions(options));
            }

            io.temporal.activity.ActivityOptions activityOptions = optionsBuilder.build();
            io.temporal.workflow.ActivityStub activityStub =
                    Workflow.newUntypedActivityStub(activityOptions);

            // Pass the retryOnError flag to the activity adapter as a call config map
            // The adapter receives [namedArgs, callConfig] as Temporal arguments
            Map<String, Object> callConfig = new HashMap<>();
            callConfig.put(CALL_CONFIG_MARKER, true);
            callConfig.put(RETRY_ON_ERROR_KEY, retryOnError);

            // Execute the activity through Temporal's activity mechanism with the full name
            Object result = activityStub.execute(fullActivityName, Object.class,
                    new Object[] { namedArgs, callConfig });

            // Convert result back to Ballerina type
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
            
            // Use cloneWithType to convert to the expected type from typedesc
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.cloneWithType(ballerinaResult, targetType);

        } catch (io.temporal.failure.ActivityFailure e) {
            // Activity failed - extract the original error message from the cause
            Throwable cause = e.getCause();
            String errorMsg;
            if (cause instanceof io.temporal.failure.ApplicationFailure appFailure) {
                errorMsg = appFailure.getOriginalMessage();
            } else {
                errorMsg = cause != null ? cause.getMessage() : e.getMessage();
            }
            return ErrorCreator.createError(
                    StringUtils.fromString(errorMsg));
        } catch (io.temporal.worker.NonDeterministicException e) {
            // Re-throw non-determinism exceptions so Temporal's replay engine handles them.
            // Swallowing this would produce FAIL_WORKFLOW_EXECUTION instead of the expected
            // next command, causing a cascade of NonDeterministicException SEVERE log entries.
            throw e;
        } catch (io.temporal.failure.TemporalFailure e) {
            // Re-throw other Temporal failures (cancellation, etc.) — not activity errors.
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Activity execution failed: " + e.getMessage()));
        }
    }

    /**
     * Builds a Temporal {@link io.temporal.common.RetryOptions} from the flat per-call
     * {@code ActivityOptions} fields ({@code maxRetries}, {@code retryDelay},
     * {@code retryBackoff}, {@code maxRetryDelay}).
     *
     * @param options the ActivityOptions BMap passed to callActivity
     * @return RetryOptions configured from the per-call fields
     */
    private static io.temporal.common.RetryOptions buildPerCallRetryOptions(
            BMap<BString, Object> options) {
        io.temporal.common.RetryOptions.Builder builder =
                io.temporal.common.RetryOptions.newBuilder();

        // maxRetries → maximumAttempts (maxRetries=0 means 1 total attempt, no retries)
        Object maxRetriesVal = options.get(StringUtils.fromString("maxRetries"));
        int maxRetries = 0;
        if (maxRetriesVal instanceof Long longVal) {
            maxRetries = Math.toIntExact(longVal);
        }
        builder.setMaximumAttempts(maxRetries + 1);

        // retryDelay → initialInterval (decimal seconds)
        Object retryDelayVal = options.get(StringUtils.fromString("retryDelay"));
        if (retryDelayVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double delaySeconds = bDecimal.floatValue();
            if (delaySeconds > 0) {
                builder.setInitialInterval(
                        java.time.Duration.ofMillis((long) (delaySeconds * 1000)));
            }
        }

        // retryBackoff → backoffCoefficient
        Object retryBackoffVal = options.get(StringUtils.fromString("retryBackoff"));
        if (retryBackoffVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double backoff = bDecimal.floatValue();
            if (backoff >= 1.0) {
                builder.setBackoffCoefficient(backoff);
            }
        }

        // maxRetryDelay → maximumInterval (optional, decimal seconds)
        Object maxRetryDelayVal = options.get(StringUtils.fromString("maxRetryDelay"));
        if (maxRetryDelayVal instanceof io.ballerina.runtime.api.values.BDecimal bDecimal) {
            double maxDelaySeconds = bDecimal.floatValue();
            if (maxDelaySeconds > 0) {
                builder.setMaximumInterval(
                        java.time.Duration.ofMillis((long) (maxDelaySeconds * 1000)));
            }
        }

        return builder.build();
    }

    /**
     * Converts an activity {@code args} BMap to a Java map for Temporal
     * serialization, replacing any {@link BObject} value with the marker string
     * {@code "connection:<name>"}.
     * <p>
     * The map type at the Ballerina level is
     * {@code map<anydata|object {}>}: only client-object values are non-anydata
     * and they cannot cross the Temporal boundary. The compiler plugin has
     * already validated at the call site that any such value is a module-level
     * {@code final} {@code client object} reference and that
     * {@code registerConnection} has been emitted for it during module init,
     * so the registry lookup is expected to succeed.
     *
     * @param args the raw BMap passed to {@code callActivity}
     * @return a serializable Java map with connection refs replaced by markers
     * @throws RuntimeException if a {@link BObject} value is not registered;
     *         this surfaces as a workflow-side error in the catch block above.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertArgsMapWithConnectionMarkers(
            BMap<BString, Object> args) {
        Map<String, Object> result = new HashMap<>();
        for (BString key : args.getKeys()) {
            Object value = args.get(key);
            if (value instanceof BObject bObject) {
                String name = WorkflowWorkerNative.getConnectionName(bObject);
                if (name == null) {
                    throw new RuntimeException(
                            "Activity argument '" + key.getValue() + "' is a client object "
                                    + "that has not been registered as a module-level "
                                    + "connection. Only module-level `final` `client object` "
                                    + "variables may be passed to activities.");
                }
                result.put(key.getValue(),
                        WorkflowWorkerNative.CONNECTION_MARKER_PREFIX + name);
            } else {
                result.put(key.getValue(),
                        TypesUtil.convertBallerinaToJavaType(value));
            }
        }
        return result;
    }

    /**
     * Create a new context info object.
     * This is called when creating a new workflow context.
     *
     * @param workflowId the workflow ID
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
     * @param millis Duration in milliseconds
     * @return null on success, error on failure
     */
    public static Object sleepMillis(Object contextHandle, long millis) {
        try {
            Workflow.sleep(Duration.ofMillis(millis));
            return null;
        } catch (io.temporal.worker.NonDeterministicException | io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow sleep failed: " + e.getMessage()));
        }
    }

    /**
     * Returns the current workflow time as epoch milliseconds.
     * <p>
     * The workflow engine records the timestamp at each workflow task and
     * provides it via {@code Workflow.currentTimeMillis()}. This value is
     * replayed identically, making it safe to use inside workflow functions.
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
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow ID: " + e.getMessage()));
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
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow type: " + e.getMessage()));
        }
    }

    /**
     * Context information holder. Stores workflow-specific context information.
     *
     * @param workflowId   the workflow ID
     * @param workflowType the workflow type
     */
    public record ContextInfo(String workflowId, String workflowType) {
    }

    // -----------------------------------------------------------------------
    // callHumanTask
    // -----------------------------------------------------------------------

    /**
     * Starts a built-in human task child workflow and blocks until a human completes it
     * (via a {@code "taskCompletion"} signal) or an optional timeout elapses.
     *
     * <p>The child workflow type equals {@code taskName}, which must have been registered
     * in the {@code HUMANTASK_REGISTRY} via {@code WorkflowWorkerNative.registerHumanTask}
     * before the worker started.  {@code callHumanTask} also performs a lazy in-workflow
     * registration so that ad-hoc calls work without compile-time plugin support.
     *
     * <p>On success the {@code result} field of the signal payload is coerced to the
     * caller's {@code typedesc T} and returned.
     *
     * <p>When {@code timeout} is absent (nil) the workflow waits indefinitely.
     * When a timeout is set and fires, a {@code HumanTaskTimeoutError} distinct error
     * is returned.
     *
     * @param self     the Context BObject (unused; present for Ballerina calling convention)
     * @param typedesc the expected result type descriptor (for dependent-typing and coercion)
     * @param config   the HumanTaskConfig BMap (taskName, title?, description?, userRoles, payload, timeout?)
     * @return the coerced result value, or a {@code HumanTaskTimeoutError} BError
     */
    @SuppressWarnings("unchecked")
    public static Object callHumanTask(BObject self, BMap<BString, Object> config, BTypedesc typedesc) {
        try {
            // --- Extract config fields -----------------------------------------------
            String taskName = ((BString) config.get(StringUtils.fromString("taskName"))).getValue();

            // taskName must be non-blank and must not contain '.' (qualifier separator) or '|' (timeout msg separator)
            if (taskName.isBlank()) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "HumanTask taskName must not be blank", "HUMANTASK_CONFIG_ERROR");
            }
            if (taskName.contains(".") || taskName.contains("|")) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "HumanTask taskName '" + taskName + "' must not contain '.' or '|'",
                        "HUMANTASK_CONFIG_ERROR");
            }

            // title defaults to the user-provided (unqualified) taskName when absent
            Object titleObj = config.get(StringUtils.fromString("title"));
            String title = (titleObj instanceof BString bs) ? bs.getValue() : taskName;

            Object descObj = config.get(StringUtils.fromString("description"));
            String description = (descObj instanceof BString bs) ? bs.getValue() : "";

            // userRoles: BArray of BString; default is ["admin"] from the type default
            io.ballerina.runtime.api.values.BArray rolesArray =
                    (io.ballerina.runtime.api.values.BArray)
                            config.get(StringUtils.fromString("userRoles"));
            java.util.List<String> userRoles = new java.util.ArrayList<>();
            if (rolesArray != null) {
                for (int i = 0; i < rolesArray.size(); i++) {
                    userRoles.add(rolesArray.get(i).toString());
                }
            }
            if (userRoles.isEmpty()) {
                userRoles.add("admin");
            }

            Object payload = config.get(StringUtils.fromString("payload"));

            // timeout: nil (BNull/null) means wait indefinitely
            Object timeoutObj = config.get(StringUtils.fromString("timeout"));
            Long timeoutSeconds = null;
            if (timeoutObj instanceof BMap) {
                timeoutSeconds = computeTimeoutSeconds((BMap<BString, Object>) timeoutObj);
            }

            // --- Build child workflow identity ---------------------------------------
            // Qualify taskName as "workflowType.taskName" to ensure uniqueness across
            // different workflow definitions that may reuse the same short task name.
            String parentWorkflowId = Workflow.getInfo().getWorkflowId();
            String workflowDefinitionName = Workflow.getInfo().getWorkflowType();
            String qualifiedTaskName = workflowDefinitionName + "." + taskName;

            // --- Ensure qualifiedTaskName is registered as a human task type --------
            // Lazy registration covers ad-hoc / test usage without compiler-plugin support.
            // The contains check makes this a no-op on replay when already registered
            // from module init (compiler-plugin path) or a prior workflow task.
            if (!WorkflowWorkerNative.getHumanTaskRegistry().contains(qualifiedTaskName)) {
                WorkflowWorkerNative.registerHumanTask(StringUtils.fromString(qualifiedTaskName));
            }

            // Workflow.randomUUID() is deterministic across Temporal replays
            String taskWorkflowId = "humantask-" + parentWorkflowId + "-" + qualifiedTaskName
                    + "-" + Workflow.randomUUID();

            // --- Memo (immutable, readable without full history) --------------------
            Map<String, Object> memo = new HashMap<>();
            memo.put("workflowKind", "HUMAN_TASK");
            memo.put("taskName", qualifiedTaskName);
            memo.put("parentWorkflowId", parentWorkflowId);
            memo.put("title", title);
            memo.put("description", description);
            memo.put("userRoles", userRoles);
            memo.put("payload", TypesUtil.convertBallerinaToJavaType(payload));
            memo.put("createdAt",
                    Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
            // formSchema will be added by the compiler plugin once JSON Schema generation is implemented
            memo.put("formSchema", null);

            // --- Build input map passed to the child workflow -----------------------
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("taskName", qualifiedTaskName);
            inputs.put("title", title);
            inputs.put("description", description);
            inputs.put("userRoles", userRoles);
            inputs.put("payload", TypesUtil.convertBallerinaToJavaType(payload));
            // null means no timeout (wait indefinitely)
            inputs.put("timeoutSeconds", timeoutSeconds);
            inputs.put("parentWorkflowId", parentWorkflowId);
            inputs.put("workflowDefinitionName", workflowDefinitionName);

            // --- Start child workflow and block until completion --------------------
            ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                    .setWorkflowId(taskWorkflowId)
                    .setParentClosePolicy(
                            io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                    .setMemo(memo)
                    .build();

            // The child workflow type IS the qualifiedTaskName — each task is its own type,
            // scoped to the parent workflow to avoid collisions across workflow definitions.
            ChildWorkflowStub childStub = Workflow.newUntypedChildWorkflowStub(
                    qualifiedTaskName, childOptions);

            Object rawResult = childStub.execute(Object.class, inputs);

            // --- Extract the "result" field from the signal payload -----------------
            // Signal payload shape: { completedBy: {...}, result: <json> }
            Object formResult = extractResultField(rawResult);

            // Coerce to the caller's typedesc T
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(formResult);
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.cloneWithType(ballerinaResult, targetType);

        } catch (ChildWorkflowFailure e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApplicationFailure af
                    && WorkflowWorkerNative.HUMANTASK_TIMEOUT_FAILURE_TYPE.equals(af.getType())) {
                return buildTimeoutError(af.getOriginalMessage());
            }
            // Some other child workflow failure — surface as a generic error
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            return ErrorCreator.createError(StringUtils.fromString(
                    "Human task failed: " + msg));

        } catch (io.temporal.worker.NonDeterministicException
                 | io.temporal.failure.TemporalFailure e) {
            throw e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "callHumanTask failed: " + e.getMessage()));
        }
    }

    /**
     * Converts a {@code time:Duration} BMap to total seconds as a {@code long}.
     * Returns {@code null} to indicate "no timeout" when the duration map is absent.
     */
    @SuppressWarnings("unchecked")
    private static Long computeTimeoutSeconds(BMap<BString, Object> duration) {
        if (duration == null) {
            return null; // no timeout — wait indefinitely
        }
        long hours = getLongField(duration, "hours");
        long minutes = getLongField(duration, "minutes");
        double seconds = getDoubleField(duration, "seconds");
        return hours * 3600L + minutes * 60L + (long) seconds;
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
     * Extracts the {@code result} field from the signal completion payload.
     * Uses {@code containsKey} so that an explicit {@code null} result (tasks completed
     * with no input value) is returned as {@code null} rather than falling back to the
     * whole payload map.
     * If the payload is not a Map or has no "result" key, the raw value is returned as-is.
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
     * Builds a Ballerina {@code HumanTaskTimeoutError} from the pipe-delimited
     * message encoded by {@code executeBuiltinHumanTask}.
     * Format: {@code taskName|taskWorkflowId|timedOutAfter|timedOutAt}
     */
    private static BError buildTimeoutError(String msg) {
        String[] parts = msg == null ? new String[0] : msg.split("\\|", -1);
        String taskName = parts.length > 0 ? parts[0] : "unknown";
        String taskWorkflowId = parts.length > 1 ? parts[1] : "unknown";
        String timedOutAfter = parts.length > 2 ? parts[2] : "unknown";
        String timedOutAt = parts.length > 3 ? parts[3] : "unknown";

        BMap<BString, Object> detail = io.ballerina.runtime.api.creators.ValueCreator
                .createMapValue();
        detail.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        detail.put(StringUtils.fromString("taskWorkflowId"), StringUtils.fromString(taskWorkflowId));
        detail.put(StringUtils.fromString("timedOutAfter"), StringUtils.fromString(timedOutAfter));
        detail.put(StringUtils.fromString("timedOutAt"), StringUtils.fromString(timedOutAt));

        try {
            return ErrorCreator.createError(
                    ModuleUtils.getModule(),
                    "HumanTaskTimeoutError",
                    StringUtils.fromString(
                            "Human task '" + taskName + "' timed out after " + timedOutAfter),
                    null,
                    detail);
        } catch (Exception e) {
            // Fallback if the module type hasn't been initialised yet (e.g. in unit tests)
            return ErrorCreator.createError(
                    StringUtils.fromString("HumanTaskTimeoutError: Human task '" + taskName
                            + "' timed out after " + timedOutAfter),
                    detail);
        }
    }
}
