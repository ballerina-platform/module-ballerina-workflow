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
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.workflow.Workflow;

import java.time.Duration;
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
}
