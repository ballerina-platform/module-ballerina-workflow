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
import java.util.concurrent.ConcurrentHashMap;

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

    // Activity kind markers for remote and resource activities
    public static final String ACTIVITY_KIND_KEY = "__activityKind__";
    public static final String ACTIVITY_KIND_REMOTE = "remote";
    public static final String ACTIVITY_KIND_RESOURCE = "resource";
    public static final String METHOD_NAME_KEY = "__methodName__";
    public static final String ACCESSOR_KEY = "__accessor__";
    public static final String RESOURCE_PATH_KEY = "__resourcePath__";

    // Prefix used for remote/resource activity names
    public static final String REMOTE_ACTIVITY_PREFIX = "__remote__";
    public static final String RESOURCE_ACTIVITY_PREFIX = "__resource__";

    // Key used to pass the per-invocation ID through Temporal's callConfig
    public static final String INVOCATION_ID_KEY = "__invocationId__";

    // Ephemeral map of active invocations: invocationId → client BObject.
    // Entries are inserted immediately before the Temporal activity call and
    // removed either by the activity adapter (on fresh execution) or by the
    // workflow thread (after activityStub.execute() returns during replay).
    // This is NOT a long-lived cache – each entry exists only for the duration
    // of a single activity dispatch and is always cleaned up.
    static final ConcurrentHashMap<String, BObject> PENDING_INVOCATIONS =
            new ConcurrentHashMap<>();

    /**
     * Consumes (retrieves and removes) the client object for a pending invocation.
     * Designed for one-shot use by the activity adapter.
     *
     * @param invocationId the unique invocation ID passed through callConfig
     * @return the client BObject, or null if not found
     */
    public static BObject consumeInvocation(String invocationId) {
        return PENDING_INVOCATIONS.remove(invocationId);
    }

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
            Map<String, Object> namedArgs = TypesUtil.convertBMapToMap(args);

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
     * Execute a remote method on a client object as a workflow activity.
     * <p>
     * This method enables calling remote methods on client objects (e.g., HTTP clients)
     * as workflow activities. Since remote methods cannot be passed as function pointers,
     * the client object and method name are provided separately.
     *
     * @param self the Context BObject (self reference from Ballerina)
     * @param connection the client BObject whose remote method should be invoked
     * @param remoteMethodName the name of the remote method to call
     * @param args the map containing arguments to pass to the remote method
     * @param typedesc the expected return type descriptor for dependent typing
     * @param options ActivityOptions record with retryOnError, maxRetries, etc.
     * @return the result of the activity execution converted to the expected type, or an error
     */
    public static Object callRemoteActivity(BObject self, BObject connection,
            BString remoteMethodName, BMap<BString, Object> args,
            BTypedesc typedesc, BMap<BString, Object> options) {
        // Use Temporal's deterministic randomUUID (replays identically)
        String invocationId = Workflow.randomUUID().toString();
        try {
            String methodName = remoteMethodName.getValue();
            String workflowType = Workflow.getInfo().getWorkflowType();
            String fullActivityName = workflowType + "." + REMOTE_ACTIVITY_PREFIX + methodName;

            // Register the connection for this specific invocation.
            // The adapter consumes (removes) it on fresh execution; we remove it
            // below in the finally block to handle replay (adapter not called).
            PENDING_INVOCATIONS.put(invocationId, connection);

            Map<String, Object> namedArgs = TypesUtil.convertBMapToMap(args);
            boolean retryOnError = extractRetryOnError(options);

            io.temporal.activity.ActivityOptions activityOptions = buildActivityOptions(retryOnError, options);
            io.temporal.workflow.ActivityStub activityStub =
                    Workflow.newUntypedActivityStub(activityOptions);

            // Pass the invocation ID so the adapter can look up the connection
            // from the passed parameter rather than any external cache.
            Map<String, Object> callConfig = new HashMap<>();
            callConfig.put(CALL_CONFIG_MARKER, true);
            callConfig.put(RETRY_ON_ERROR_KEY, retryOnError);
            callConfig.put(ACTIVITY_KIND_KEY, ACTIVITY_KIND_REMOTE);
            callConfig.put(METHOD_NAME_KEY, methodName);
            callConfig.put(INVOCATION_ID_KEY, invocationId);

            Object result = activityStub.execute(fullActivityName, Object.class,
                    new Object[]{namedArgs, callConfig});

            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.cloneWithType(ballerinaResult, targetType);

        } catch (io.temporal.failure.ActivityFailure e) {
            return handleActivityFailure(e);
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Remote activity execution failed: " + e.getMessage()));
        } finally {
            // Always clean up – on replay the adapter is never called, so the
            // entry would otherwise linger. remove() is a no-op if already consumed.
            PENDING_INVOCATIONS.remove(invocationId);
        }
    }

    /**
     * Execute a resource method on a client object as a workflow activity.
     * <p>
     * This method enables calling resource methods on client objects (e.g., HTTP clients)
     * as workflow activities. Since resource methods cannot be passed as function pointers,
     * the client object, accessor, and resource path are provided separately.
     *
     * @param self the Context BObject (self reference from Ballerina)
     * @param connection the client BObject whose resource method should be invoked
     * @param accessor the HTTP accessor (e.g., "get", "post", "put", "delete")
     * @param resourcePath the resource path (e.g., "/api/users")
     * @param args the map containing arguments to pass to the resource method
     * @param typedesc the expected return type descriptor for dependent typing
     * @param options ActivityOptions record with retryOnError, maxRetries, etc.
     * @return the result of the activity execution converted to the expected type, or an error
     */
    public static Object callResourceActivity(BObject self, BObject connection,
            BString accessor, BString resourcePath, BMap<BString, Object> args,
            BTypedesc typedesc, BMap<BString, Object> options) {
        // Use Temporal's deterministic randomUUID (replays identically)
        String invocationId = Workflow.randomUUID().toString();
        try {
            String accessorStr = accessor.getValue();
            String pathStr = resourcePath.getValue();
            String workflowType = Workflow.getInfo().getWorkflowType();
            String fullActivityName = workflowType + "." + RESOURCE_ACTIVITY_PREFIX
                    + accessorStr + "$" + pathStr.replace("/", "$");

            // Register the connection for this specific invocation.
            // The adapter consumes (removes) it on fresh execution; we remove it
            // below in the finally block to handle replay (adapter not called).
            PENDING_INVOCATIONS.put(invocationId, connection);

            Map<String, Object> namedArgs = TypesUtil.convertBMapToMap(args);
            boolean retryOnError = extractRetryOnError(options);

            io.temporal.activity.ActivityOptions activityOptions = buildActivityOptions(retryOnError, options);
            io.temporal.workflow.ActivityStub activityStub =
                    Workflow.newUntypedActivityStub(activityOptions);

            // Pass the invocation ID so the adapter can look up the connection
            // from the passed parameter rather than any external cache.
            Map<String, Object> callConfig = new HashMap<>();
            callConfig.put(CALL_CONFIG_MARKER, true);
            callConfig.put(RETRY_ON_ERROR_KEY, retryOnError);
            callConfig.put(ACTIVITY_KIND_KEY, ACTIVITY_KIND_RESOURCE);
            callConfig.put(ACCESSOR_KEY, accessorStr);
            callConfig.put(RESOURCE_PATH_KEY, pathStr);
            callConfig.put(INVOCATION_ID_KEY, invocationId);

            Object result = activityStub.execute(fullActivityName, Object.class,
                    new Object[]{namedArgs, callConfig});

            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.cloneWithType(ballerinaResult, targetType);

        } catch (io.temporal.failure.ActivityFailure e) {
            return handleActivityFailure(e);
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Resource activity execution failed: " + e.getMessage()));
        } finally {
            // Always clean up – on replay the adapter is never called, so the
            // entry would otherwise linger. remove() is a no-op if already consumed.
            PENDING_INVOCATIONS.remove(invocationId);
        }
    }

    /**
     * Extracts the retryOnError flag from the options map.
     */
    private static boolean extractRetryOnError(BMap<BString, Object> options) {
        Object retryOnErrorVal = options.get(StringUtils.fromString(RETRY_ON_ERROR_KEY));
        if (retryOnErrorVal instanceof Boolean) {
            return (Boolean) retryOnErrorVal;
        }
        return false;
    }

    /**
     * Builds Temporal ActivityOptions from the Ballerina options record.
     */
    private static io.temporal.activity.ActivityOptions buildActivityOptions(
            boolean retryOnError, BMap<BString, Object> options) {
        io.temporal.activity.ActivityOptions.Builder optionsBuilder =
                io.temporal.activity.ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(java.time.Duration.ofMinutes(5));

        if (!retryOnError) {
            optionsBuilder.setRetryOptions(
                    io.temporal.common.RetryOptions.newBuilder()
                            .setMaximumAttempts(1)
                            .build());
        } else {
            optionsBuilder.setRetryOptions(buildPerCallRetryOptions(options));
        }

        return optionsBuilder.build();
    }

    /**
     * Handles an ActivityFailure exception by extracting the error message.
     */
    private static Object handleActivityFailure(io.temporal.failure.ActivityFailure e) {
        Throwable cause = e.getCause();
        String errorMsg;
        if (cause instanceof io.temporal.failure.ApplicationFailure appFailure) {
            errorMsg = appFailure.getOriginalMessage();
        } else {
            errorMsg = cause != null ? cause.getMessage() : e.getMessage();
        }
        return ErrorCreator.createError(StringUtils.fromString(errorMsg));
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
