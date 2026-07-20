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

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.ModuleUtils;
import io.ballerina.lib.workflow.runtime.WorkflowRuntime;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.MapType;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/**
 * Native implementation for workflow module functions.
 * <p>
 * This class provides the native implementations for the external functions defined in the Ballerina workflow module:
 * <ul>
 *   <li>run - Start a new workflow execution</li>
 *   <li>sendData - Send signal data to a running workflow</li>
 *   <li>getRegisteredWorkflows - List all registered workflow functions</li>
 *   <li>getWorkflowResult - Wait for and retrieve a workflow's result</li>
 *   <li>getWorkflowInfo - Get current status/info of a workflow execution</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class WorkflowNative {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowNative.class);

    // Default timeout for implicit activity execution (run, sendData)
    private static final Duration DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT = Duration.ofMinutes(5);

    // Deadline in seconds for gRPC metadata calls (DescribeWorkflowExecution, GetHistory)
    private static final long GET_INFO_DEADLINE_SECONDS = 5;

    // Error message prefixes
    private static final String ERR_START_PROCESS = "Failed to start process: ";
    private static final String ERR_SEND_DATA = "Failed to send data: ";
    private static final String ERR_GET_RESULT = "Failed to get workflow result: ";
    private static final String ERR_GET_INFO = "Failed to get workflow info: ";
    private static final String ERR_GET_REGISTERED = "Failed to get registered workflows: ";
    private static final String ERR_CLIENT_NOT_INIT = "Workflow client not initialized";

    private WorkflowNative() {
        // Private constructor to prevent instantiation
    }

    /**
     * Builds {@link io.temporal.activity.ActivityOptions} for implicit (built-in) activities. Uses the global default
     * activity retry policy from {@link WorkflowWorkerNative} when available, falling back to a single-attempt policy
     * otherwise.
     *
     * @param timeout the start-to-close timeout for the activity
     * @return configured ActivityOptions
     */
    private static io.temporal.activity.ActivityOptions buildImplicitActivityOptions(Duration timeout) {
        io.temporal.common.RetryOptions retryOptions = WorkflowWorkerNative.getDefaultActivityRetryOptions();
        if (retryOptions == null) {
            retryOptions = io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build();
        }
        return io.temporal.activity.ActivityOptions.newBuilder().setStartToCloseTimeout(timeout).setRetryOptions(
                retryOptions).build();
    }

    /**
     * Handles errors from implicit activity execution, extracting the root cause message from Temporal's
     * {@link io.temporal.failure.ActivityFailure} wrapper.
     *
     * @param e           the caught exception
     * @param errorPrefix a human-readable prefix for the error message
     * @return a Ballerina error with the appropriate message
     */
    private static Object handleImplicitActivityError(Exception e, String errorPrefix) {
        String errorMsg;
        if (e instanceof io.temporal.failure.ActivityFailure activityFailure) {
            Throwable cause = activityFailure.getCause();
            if (cause instanceof io.temporal.failure.ApplicationFailure appFailure) {
                errorMsg = appFailure.getOriginalMessage();
            } else {
                errorMsg = cause != null ? cause.getMessage() : e.getMessage();
            }
        } else {
            errorMsg = e.getMessage();
        }
        return ErrorCreator.createError(StringUtils.fromString(errorPrefix + errorMsg));
    }

    /**
     * Native implementation for run function.
     * <p>
     * Starts a new workflow with the given input. Returns the workflow ID that can be used to track and interact with
     * the workflow.
     * <p>
     * When called from inside a workflow context, the call is automatically routed through an implicit activity so that
     * the operation is deterministic and replay-safe. The function pointer is resolved to its string name for
     * serialization since function pointers are not {@code anydata}.
     * <p>
     * When called from outside a workflow (e.g., HTTP handler, test), the workflow is started directly via the Temporal
     * client.
     *
     * @param env             the Ballerina runtime environment
     * @param processFunction the process function to execute (must be annotated with @Workflow)
     * @param input           the optional input data for the process (nil or any anydata value)
     * @return the workflow ID as a string, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object run(Environment env, BFunctionPointer processFunction, Object input) {
        // Extract the process name and apply the user-workflow prefix so it matches
        // the key stored in PROCESS_REGISTRY by registerWorkflow().
        String processName = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + processFunction.getType().getName();

        // Convert input to Java type (handle nil case)
        // In Ballerina Java interop, nil () is passed as null, so a simple null check suffices.
        // Every anydata subtype is a valid workflow input — primitives (boolean, int, string),
        // json, xml, arrays, tables and records all round-trip through
        // convertBallerinaToJavaType the same way sendData payloads do.
        Object javaInput = null;
        if (input != null) {
            javaInput = TypesUtil.convertBallerinaToJavaType(input);
        }

        // Check if we're inside a workflow execution context
        if (isInsideWorkflow()) {
            // Route through an implicit activity so the call is deterministic.
            // The function pointer is replaced with the string process name
            // for Temporal serialization.
            return runAsImplicitActivity(processName, javaInput);
        }

        // Outside workflow - use the normal async path
        final Object finalInput = javaInput;
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    String workflowId = WorkflowRuntime.getInstance().createInstance(processName, finalInput);
                    balFuture.complete(StringUtils.fromString(workflowId));
                } catch (Exception e) {
                    balFuture.complete(
                            ErrorCreator.createError(StringUtils.fromString(ERR_START_PROCESS + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Routes a {@code workflow:run} call through a built-in implicit activity so that it is deterministic inside a
     * workflow execution.
     *
     * @param processName the workflow type name (extracted from the function pointer)
     * @param javaInput   the input data converted to a Java type (may be null)
     * @return a Ballerina string containing the new workflow ID, or a BError
     */
    private static Object runAsImplicitActivity(String processName, Object javaInput) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(
                    buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            String workflowId = stub.execute(WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_RUN, String.class,
                                             processName, javaInput);
            return StringUtils.fromString(workflowId);
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_START_PROCESS);
        }
    }

    /**
     * Native implementation for sendData function.
     * <p>
     * Sends data to a running workflow process by workflow ID and data name. All parameters are required.
     * <p>
     * When called from inside a workflow context, the call is automatically routed through an implicit activity for
     * determinism.
     * <p>
     * Note: {@code workflowFunction} is not used at runtime; it exists in the signature so the compiler plugin can
     * validate that the target function carries the {@code @Workflow} annotation and that the data type matches the
     * workflow's events record. Removing it would be a breaking API change.
     *
     * @param env              the Ballerina runtime environment
     * @param workflowFunction the workflow function (unused at runtime; used by the compiler plugin for validation)
     * @param workflowId       the workflow ID to send the data to
     * @param dataName         the name identifying the data (must match an events record field)
     * @param data             the data to send
     * @return null on success, or an error
     */
    public static Object sendData(Environment env, BFunctionPointer workflowFunction, BString workflowId,
                                  BString dataName, Object data) {
        // Convert the data to its Java representation so Temporal's JSON payload converter can persist it.
        // This must handle every anydata value - not just records/maps - because primitives (boolean, int,
        // string), json, xml, arrays and tables are all valid signal payloads. convertBallerinaToJavaType
        // unwraps BString -> String, wraps xml in a round-trip marker, etc., and returns BMap/primitives as-is.
        Object javaData = TypesUtil.convertBallerinaToJavaType(data);

        String workflowIdStr = workflowId.getValue();
        String dataNameStr = dataName.getValue();

        // Check if we're inside a workflow execution context
        if (isInsideWorkflow()) {
            return sendDataAsImplicitActivity(workflowIdStr, dataNameStr, javaData);
        }

        // Outside workflow - use the normal async path
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    WorkflowRuntime.getInstance().sendSignalToWorkflow(workflowIdStr, dataNameStr, javaData);
                    balFuture.complete(null);
                } catch (Exception e) {
                    balFuture.complete(
                            ErrorCreator.createError(StringUtils.fromString(ERR_SEND_DATA + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Backs {@code workflow:updateAgent} — the request-response counterpart of {@code sendData} for durable
     * agents, modeled as a Temporal Update. Blocks until the agent consumes the message and answers the turn,
     * then coerces the response to the caller's dependently-typed {@code T}: directly for string-compatible
     * targets, or by parsing the response text as JSON for structured targets.
     *
     * @param env           the runtime environment
     * @param agentFunction the agent function (symmetry with sendData; reserved for compile-time validation)
     * @param agentId       the agent's workflow ID
     * @param eventName     the event field name declared in the agent's signature
     * @param data          the request payload
     * @param typedesc      the expected response type
     * @return the agent's turn response coerced to {@code T}, or a Ballerina error
     */
    public static Object updateAgent(Environment env, BFunctionPointer agentFunction, BString agentId,
                                     BString eventName, Object data, BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "updateAgent cannot be called inside a workflow; use it from services or main"));
        }
        Object javaData = TypesUtil.convertBallerinaToJavaType(data);
        String agentIdStr = agentId.getValue();
        String eventNameStr = eventName.getValue();
        Type targetType = typedesc.getDescribingType();

        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                    if (client == null) {
                        balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                                "Workflow client not initialized. Ensure worker is initialized.")));
                        return;
                    }
                    WorkflowStub stub = client.newUntypedWorkflowStub(agentIdStr);
                    Object result = stub.update(WorkflowWorkerNative.AGENT_UPDATE_NAME,
                            Object.class, eventNameStr, javaData);
                    balFuture.complete(coerceAgentResponse(result, targetType));
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String message = cause != null && cause.getMessage() != null
                            ? cause.getMessage() : e.getMessage();
                    balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                            "Failed to update agent '" + agentIdStr + "': " + message)));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Backs {@code workflow:updateAgentAsync} — the non-blocking counterpart of {@code updateAgent}. Starts the
     * Temporal Update and waits only for the {@code ACCEPTED} stage: the request is durably admitted into the
     * workflow history and this returns the update ID. The agent's answer is fetched later (from any process)
     * via {@link #getAgentUpdateResult}, so long turns — e.g. a human-task escalation — hold no caller thread
     * and survive caller crashes.
     *
     * @param env           the runtime environment
     * @param agentFunction the agent function (symmetry with updateAgent; reserved for compile-time validation)
     * @param agentId       the agent's workflow ID
     * @param eventName     the update channel registered by the agent
     * @param data          the request payload
     * @return the update ID as a Ballerina string, or a Ballerina error
     */
    public static Object updateAgentAsync(Environment env, BFunctionPointer agentFunction, BString agentId,
                                          BString eventName, Object data) {
        if (isInsideWorkflow()) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "updateAgentAsync cannot be called inside a workflow; use it from services or main"));
        }
        Object javaData = TypesUtil.convertBallerinaToJavaType(data);
        String agentIdStr = agentId.getValue();
        String eventNameStr = eventName.getValue();

        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                    if (client == null) {
                        balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                                "Workflow client not initialized. Ensure worker is initialized.")));
                        return;
                    }
                    WorkflowStub stub = client.newUntypedWorkflowStub(agentIdStr);
                    UpdateOptions<Object> options = UpdateOptions.newBuilder(Object.class)
                            .setUpdateName(WorkflowWorkerNative.AGENT_UPDATE_NAME)
                            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                            .build();
                    WorkflowUpdateHandle<Object> handle = stub.startUpdate(options, eventNameStr, javaData);
                    balFuture.complete(StringUtils.fromString(handle.getId()));
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String message = cause != null && cause.getMessage() != null
                            ? cause.getMessage() : e.getMessage();
                    balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                            "Failed to send update to agent '" + agentIdStr + "': " + message)));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Backs {@code workflow:getAgentUpdateResult}. Re-attaches to a previously accepted update by its ID and
     * waits up to the given timeout for the agent's turn answer. On timeout a {@code UpdatePendingError} is
     * returned so the caller can check back later — the update result lives in the workflow history, so it is
     * retrievable from any process at any time.
     *
     * @param env            the runtime environment
     * @param agentId        the agent's workflow ID
     * @param updateId       the update ID returned by {@code updateAgentAsync}
     * @param timeoutSeconds how long to wait before reporting the update as pending
     * @param typedesc       the expected response type
     * @return the agent's answer coerced to {@code T}, an {@code UpdatePendingError}, or a Ballerina error
     */
    public static Object getAgentUpdateResult(Environment env, BString agentId, BString updateId,
                                              BDecimal timeoutSeconds, BTypedesc typedesc) {
        if (isInsideWorkflow()) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "getAgentUpdateResult cannot be called inside a workflow; use it from services or main"));
        }
        String agentIdStr = agentId.getValue();
        String updateIdStr = updateId.getValue();
        long timeoutMillis = (long) (timeoutSeconds.floatValue() * 1000);
        Type targetType = typedesc.getDescribingType();

        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                    if (client == null) {
                        balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                                "Workflow client not initialized. Ensure worker is initialized.")));
                        return;
                    }
                    WorkflowStub stub = client.newUntypedWorkflowStub(agentIdStr);
                    WorkflowUpdateHandle<Object> handle = stub.getUpdateHandle(updateIdStr, Object.class);
                    Object result = handle.getResultAsync(timeoutMillis, TimeUnit.MILLISECONDS).get();
                    balFuture.complete(coerceAgentResponse(result, targetType));
                } catch (Exception e) {
                    if (isTimeout(e)) {
                        balFuture.complete(buildUpdatePendingError(agentIdStr, updateIdStr));
                        return;
                    }
                    Throwable cause = e.getCause();
                    String message = cause != null && cause.getMessage() != null
                            ? cause.getMessage() : e.getMessage();
                    balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                            "Failed to fetch update result for agent '" + agentIdStr + "': " + message)));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Backs {@code workflow:getPendingAgentUpdates}: queries the agent workflow for the updates it has
     * accepted but not yet answered, so callers can rediscover in-flight turns after a crash and fetch
     * their answers via {@link #getAgentUpdateResult}.
     *
     * @param env     the runtime environment
     * @param agentId the agent's workflow ID
     * @return a Ballerina {@code PendingAgentUpdate[]}, or a Ballerina error
     */
    public static Object getPendingAgentUpdates(Environment env, BString agentId) {
        String agentIdStr = agentId.getValue();
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
                    if (client == null) {
                        balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                                "Workflow client not initialized. Ensure worker is initialized.")));
                        return;
                    }
                    WorkflowStub stub = client.newUntypedWorkflowStub(agentIdStr);
                    Object raw = stub.query(WorkflowWorkerNative.PENDING_AGENT_UPDATES_QUERY, Object.class);

                    RecordType pendingType = (RecordType) ValueCreator.createRecordValue(
                            ModuleUtils.getModule(), "PendingAgentUpdate").getType();
                    List<BMap<BString, Object>> records = new ArrayList<>();
                    if (raw instanceof List<?> entries) {
                        for (Object entry : entries) {
                            if (entry instanceof Map<?, ?> pendingEntry) {
                                BMap<BString, Object> record = ValueCreator.createRecordValue(
                                        ModuleUtils.getModule(), "PendingAgentUpdate");
                                record.put(StringUtils.fromString("updateId"), StringUtils.fromString(
                                        String.valueOf(pendingEntry.get("updateId"))));
                                record.put(StringUtils.fromString("eventName"), StringUtils.fromString(
                                        String.valueOf(pendingEntry.get("eventName"))));
                                records.add(record);
                            }
                        }
                    }
                    BArray result = ValueCreator.createArrayValue(
                            TypeCreator.createArrayType(pendingType));
                    for (BMap<BString, Object> record : records) {
                        result.append(record);
                    }
                    balFuture.complete(result);
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String message = cause != null && cause.getMessage() != null
                            ? cause.getMessage() : e.getMessage();
                    balFuture.complete(ErrorCreator.createError(StringUtils.fromString(
                            "Failed to list pending updates for agent '" + agentIdStr + "': " + message)));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Returns {@code true} when the exception (or any of its causes) is a timeout — the update is still
     * in flight rather than failed.
     */
    private static boolean isTimeout(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 8; depth++) {
            // The SDK surfaces an expired result wait either as a TimeoutException or as a
            // gRPC DEADLINE_EXCEEDED from the underlying long poll — both mean "still running".
            if (current instanceof java.util.concurrent.TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")
                    || (current.getMessage() != null && current.getMessage().contains("DEADLINE_EXCEEDED"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Builds a Ballerina {@code UpdatePendingError} carrying the agent and update IDs so callers can
     * check back with the same handle.
     */
    private static BError buildUpdatePendingError(String agentId, String updateId) {
        BMap<BString, Object> detail = ValueCreator.createMapValue();
        detail.put(StringUtils.fromString("agentId"), StringUtils.fromString(agentId));
        detail.put(StringUtils.fromString("updateId"), StringUtils.fromString(updateId));
        try {
            return ErrorCreator.createError(ModuleUtils.getModule(), "UpdatePendingError", StringUtils.fromString(
                    "The agent is still working on this request (it may be waiting on a human task). "
                            + "Check back with update ID '" + updateId + "'"), null, detail);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "UpdatePendingError: the agent is still working on update '" + updateId + "'"), detail);
        }
    }

    /**
     * Coerces an agent's textual turn response to the caller's expected type. String-compatible targets convert
     * directly; for structured targets the response text is parsed as JSON first (enabling typed responses when
     * the model answers with JSON).
     */
    private static Object coerceAgentResponse(Object result, Type targetType) {
        Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
        Object converted = TypesUtil.validateAndConvert(ballerinaResult, targetType);
        if (converted instanceof BError && ballerinaResult instanceof BString textResponse) {
            try {
                Object parsed = JsonUtils.parse(textResponse.getValue());
                Object parsedConverted = TypesUtil.validateAndConvert(parsed, targetType);
                if (!(parsedConverted instanceof BError)) {
                    return parsedConverted;
                }
            } catch (Exception ignore) {
                // Fall through to the original conversion error.
            }
        }
        return converted;
    }

    /**
     * Routes a {@code workflow:sendData} call through a built-in implicit activity.
     */
    private static Object sendDataAsImplicitActivity(String workflowId, String dataName, Object javaData) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(
                    buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            stub.execute(WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_SEND_DATA, Void.class, workflowId,
                         dataName, javaData);
            return null;
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_SEND_DATA);
        }
    }

    /**
     * Checks whether the current thread is executing inside a Temporal workflow context. Uses Temporal's thread-local
     * workflow info to detect this.
     *
     * @return {@code true} if inside a workflow execution, {@code false} otherwise
     */
    private static boolean isInsideWorkflow() {
        try {
            Workflow.getInfo();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Native implementation for getRegisteredWorkflows function.
     * <p>
     * Returns information about all registered workflow processes and their activities. This is useful for testing and
     * introspection.
     *
     * @return a map of process names to their information including activities and events
     */
    public static Object getRegisteredWorkflows() {
        try {
            // Get registries from WorkflowWorkerNative (the singleton worker)
            Map<String, BFunctionPointer> processRegistry = WorkflowWorkerNative.getProcessRegistry();
            Map<String, BFunctionPointer> activityRegistry = WorkflowWorkerNative.getActivityRegistry();
            Map<String, List<String>> eventRegistry = WorkflowWorkerNative.getEventRegistry();

            // Get the ProcessRegistration record type from the workflow module
            RecordType processRegType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getModule(),
                                                                                    "ProcessRegistration").getType();

            // Create a typed map for map<ProcessRegistration>
            MapType mapType = TypeCreator.createMapType(processRegType);
            BMap<BString, Object> resultMap = ValueCreator.createMapValue(mapType);

            for (Map.Entry<String, BFunctionPointer> entry : processRegistry.entrySet()) {
                String processName = entry.getKey(); // internal prefixed name, e.g. "workflow-test-process"

                // Strip the "workflow-" prefix for user-facing display name
                String displayName = processName.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX) ?
                                     processName.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) :
                                     processName;

                // Create a ProcessRegistration record
                BMap<BString, Object> processRecord = ValueCreator.createRecordValue(ModuleUtils.getModule(),
                                                                                     "ProcessRegistration");
                processRecord.put(StringUtils.fromString("name"), StringUtils.fromString(displayName));

                // Find activities for this process (activities are registered as "processName.activityName")
                List<String> processActivities = new ArrayList<>();
                for (String activityName : activityRegistry.keySet()) {
                    if (activityName.startsWith(processName + ".")) {
                        // Extract just the activity name part
                        String shortName = activityName.substring(processName.length() + 1);
                        processActivities.add(shortName);
                    }
                }

                BString[] activityArray = processActivities.stream().map(StringUtils::fromString).toArray(
                        BString[]::new);
                BArray activitiesBalArray = ValueCreator.createArrayValue(activityArray);
                processRecord.put(StringUtils.fromString("activities"), activitiesBalArray);

                // Get events for this process from the event registry
                List<String> processEvents = eventRegistry.getOrDefault(processName, new ArrayList<>());
                BString[] eventArray = processEvents.stream().map(StringUtils::fromString).toArray(BString[]::new);
                BArray eventsBalArray = ValueCreator.createArrayValue(eventArray);
                processRecord.put(StringUtils.fromString("events"), eventsBalArray);

                resultMap.put(StringUtils.fromString(displayName), processRecord);
            }

            return resultMap;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(ERR_GET_REGISTERED + e.getMessage()));
        }
    }

    /**
     * Native implementation for getWorkflowResult function.
     * <p>
     * Waits for a workflow to complete and returns its result value directly. Returns the raw workflow return value on
     * success, or an error if the workflow failed, was cancelled, or timed out.
     *
     * @param workflowId     the ID of the workflow to get the result for
     * @param timeoutSeconds maximum time to wait for workflow completion
     * @return the workflow result value as anydata, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getWorkflowResult(BString workflowId, long timeoutSeconds) {
        // Check if we're inside a workflow execution context
        if (isInsideWorkflow()) {
            return getWorkflowResultAsImplicitActivity(workflowId.getValue(), (int) timeoutSeconds);
        }

        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();
            WorkflowStub stub = client.newUntypedWorkflowStub(wfId);

            try {
                Object result = stub.getResult(timeoutSeconds, TimeUnit.SECONDS, Object.class);
                return result != null ? TypesUtil.convertJavaToBallerinaType(result) : null;
            } catch (io.temporal.client.WorkflowFailedException e) {
                String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return ErrorCreator.createError(StringUtils.fromString(ERR_GET_RESULT + errorMsg));
            } catch (java.util.concurrent.TimeoutException e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        ERR_GET_RESULT + "Workflow timed out after " + timeoutSeconds + " seconds"));
            }

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(ERR_GET_RESULT + e.getMessage()));
        }
    }

    /**
     * Routes a {@code workflow:getWorkflowResult} call through a built-in implicit activity. Returns the raw workflow
     * result value, or an error if the workflow failed.
     */
    @SuppressWarnings("unchecked")
    private static Object getWorkflowResultAsImplicitActivity(String workflowId, int timeoutSeconds) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            Duration activityTimeout = Duration.ofSeconds(timeoutSeconds + 30);
            io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(
                    buildImplicitActivityOptions(activityTimeout));
            Map<String, Object> info = stub.execute(WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_GET_RESULT,
                                                    Map.class, workflowId, timeoutSeconds);

            String status = (String) info.get("status");
            Object result = info.get("result");
            String errorMessage = (String) info.get("errorMessage");

            if ("FAILED".equals(status) || "CANCELED".equals(status) || "TIMED_OUT".equals(status)) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_GET_RESULT + errorMessage));
            }

            return result != null ? TypesUtil.convertJavaToBallerinaType(result) : null;
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_GET_RESULT);
        }
    }

    /**
     * Native implementation for getWorkflowInfo function.
     * <p>
     * Gets information about a workflow execution without waiting for completion. Returns the current state including
     * workflow type and status.
     * <p>
     * When called from inside a workflow context, the blocking gRPC call is routed through an implicit activity to
     * preserve determinism and avoid a PotentialDeadlockException.
     *
     * @param workflowId the ID of the workflow to get info for
     * @return a WorkflowExecutionInfo record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getWorkflowInfo(BString workflowId) {
        // Check if we're inside a workflow execution context
        if (isInsideWorkflow()) {
            return getWorkflowInfoAsImplicitActivity(workflowId.getValue());
        }

        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();

            // Describe the workflow execution to get its status
            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(wfId).build()).build();

            DescribeWorkflowExecutionResponse response =
                    client
                            .getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            String workflowType = execInfo.getType().getName();
            String status = convertStatus(execInfo.getStatus());
            if ("RUNNING".equals(status) && WorkflowWorkerNative.isSuspendedMemo(client, execInfo)) {
                status = "SUSPENDED";
            }

            return buildWorkflowExecutionInfo(wfId, workflowType, status, null, null, client);

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(ERR_GET_INFO + e.getMessage()));
        }
    }

    /**
     * Routes a {@code workflow:getWorkflowInfo} call through a built-in implicit activity when invoked from inside a
     * workflow, ensuring the blocking describeWorkflowExecution RPC is performed off the workflow thread and the result
     * is deterministic on replay.
     */
    @SuppressWarnings("unchecked")
    private static Object getWorkflowInfoAsImplicitActivity(String workflowId) {
        try {
            WorkflowWorkerNative.awaitWhileSuspended();
            io.temporal.workflow.ActivityStub stub = Workflow.newUntypedActivityStub(
                    buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            Map<String, Object> info = stub.execute(WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_GET_INFO,
                                                    Map.class, workflowId);

            String workflowType = (String) info.getOrDefault("workflowType", "");
            String status = (String) info.getOrDefault("status", "UNKNOWN");

            return buildWorkflowExecutionInfo(workflowId, workflowType, status, null, null, null);
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_GET_INFO);
        }
    }

    /**
     * Converts Temporal WorkflowExecutionStatus to a string status.
     */
    private static String convertStatus(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> "RUNNING";
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> "COMPLETED";
            case WORKFLOW_EXECUTION_STATUS_FAILED -> "FAILED";
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> "CANCELED";
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> "TERMINATED";
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> "CONTINUED_AS_NEW";
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> "TIMED_OUT";
            default -> "UNKNOWN";
        };
    }

    /**
     * Builds a WorkflowExecutionInfo Ballerina record using the management module types. When a {@link WorkflowClient}
     * is provided and the status is terminal (COMPLETED or FAILED), activity invocations are fetched from the
     * workflow's event history.
     */
    public static BMap<BString, Object> buildWorkflowExecutionInfo(String workflowId, String workflowType,
                                                                   String status, Object result, String errorMessage,
                                                                   WorkflowClient client) {

        BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                      "WorkflowExecutionInfo");

        record.put(StringUtils.fromString("workflowId"), StringUtils.fromString(workflowId));
        String displayType = workflowType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX) ?
                             workflowType.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) : workflowType;
        record.put(StringUtils.fromString("workflowType"), StringUtils.fromString(displayType));
        record.put(StringUtils.fromString("status"), StringUtils.fromString(status));

        if (result != null) {
            record.put(StringUtils.fromString("result"), TypesUtil.convertJavaToBallerinaType(result));
        } else {
            record.put(StringUtils.fromString("result"), null);
        }

        if (errorMessage != null) {
            record.put(StringUtils.fromString("errorMessage"), StringUtils.fromString(errorMessage));
        } else {
            record.put(StringUtils.fromString("errorMessage"), null);
        }

        BArray activityInvocations;
        if (client != null && ("COMPLETED".equals(status) || "FAILED".equals(status))) {
            activityInvocations = fetchActivityInvocations(client, workflowId);
        } else {
            activityInvocations = createEmptyActivityInvocationsArray();
        }
        record.put(StringUtils.fromString("activityInvocations"), activityInvocations);

        return record;
    }

    /**
     * Creates an empty typed array for the {@code activityInvocations} field using the management module's
     * ActivityInvocation type.
     */
    public static BArray createEmptyActivityInvocationsArray() {
        RecordType invocationType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                "ActivityInvocation").getType();
        return ValueCreator.createArrayValue(TypeCreator.createArrayType(invocationType));
    }

    /**
     * Fetches activity invocation history from the Temporal server.
     * <p>
     * Iterates over the workflow's event history, pairing {@code ACTIVITY_TASK_SCHEDULED} events with their terminal
     * events ({@code COMPLETED}, {@code FAILED}, {@code TIMED_OUT}, {@code CANCELED}). Each
     * {@code ACTIVITY_TASK_STARTED} event carries the attempt number which is recorded in the
     * {@code ActivityInvocation.attempt} field.
     * <p>
     * When an activity is retried, multiple (scheduled → started → failed) cycles appear in the history. Each cycle
     * produces a separate {@code ActivityInvocation} entry so the caller can see every attempt.
     *
     * @param client     the Temporal client for gRPC calls
     * @param workflowId the workflow execution to query
     * @return a Ballerina array of {@code ActivityInvocation} records
     */
    private static BArray fetchActivityInvocations(WorkflowClient client, String workflowId) {
        RecordType invocationType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                "ActivityInvocation").getType();
        BArray invocations = ValueCreator.createArrayValue(TypeCreator.createArrayType(invocationType));

        try {
            // Map: scheduledEventId → activity name (from SCHEDULED events)
            Map<Long, String> scheduledActivities = new HashMap<>();
            // Map: scheduledEventId → attempt number (from STARTED events, last one wins)
            Map<Long, Integer> scheduledAttempts = new HashMap<>();

            com.google.protobuf.ByteString nextPageToken = com.google.protobuf.ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest.Builder reqBuilder = GetWorkflowExecutionHistoryRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setExecution(io.temporal.api.common.v1.WorkflowExecution
                                              .newBuilder()
                                              .setWorkflowId(workflowId)
                                              .build());
                if (!nextPageToken.isEmpty()) {
                    reqBuilder.setNextPageToken(nextPageToken);
                }

                GetWorkflowExecutionHistoryResponse response =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                                .getWorkflowExecutionHistory(reqBuilder.build());

                for (HistoryEvent event : response.getHistory().getEventsList()) {
                    EventType eventType = event.getEventType();

                    if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
                        String activityName =
                                event.getActivityTaskScheduledEventAttributes().getActivityType().getName();
                        scheduledActivities.put(event.getEventId(), activityName);
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED) {
                        long scheduledId = event.getActivityTaskStartedEventAttributes().getScheduledEventId();
                        int attempt = event.getActivityTaskStartedEventAttributes().getAttempt();
                        scheduledAttempts.put(scheduledId, attempt);
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
                        long scheduledId = event.getActivityTaskCompletedEventAttributes().getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(name, "COMPLETED", null, attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED) {
                        long scheduledId = event.getActivityTaskFailedEventAttributes().getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        String failMsg = "";
                        if (event.getActivityTaskFailedEventAttributes().hasFailure()) {
                            failMsg = event.getActivityTaskFailedEventAttributes().getFailure().getMessage();
                        }
                        invocations.append(createActivityInvocation(name, "FAILED", failMsg, attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT) {
                        long scheduledId = event.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(name, "TIMED_OUT", "Activity timed out", attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED) {
                        long scheduledId = event.getActivityTaskCanceledEventAttributes().getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(name, "CANCELED", null, attempt));
                    }
                }

                nextPageToken = response.getNextPageToken();
            } while (!nextPageToken.isEmpty());

        } catch (Exception e) {
            LOGGER.debug("Failed to fetch activity history for workflow '{}': {}", workflowId, e.getMessage());
        }

        return invocations;
    }

    /**
     * Creates a single {@code ActivityInvocation} Ballerina record using management module types.
     */
    private static BMap<BString, Object> createActivityInvocation(String activityName, String status,
                                                                  String errorMessage, int attempt) {
        BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                      "ActivityInvocation");
        record.put(StringUtils.fromString("activityName"), StringUtils.fromString(activityName));
        record.put(StringUtils.fromString("input"), ValueCreator.createArrayValue(new BString[0]));
        record.put(StringUtils.fromString("output"), null);
        record.put(StringUtils.fromString("status"), StringUtils.fromString(status));
        record.put(StringUtils.fromString("errorMessage"),
                   errorMessage != null ? StringUtils.fromString(errorMessage) : null);
        record.put(StringUtils.fromString("attempt"), (long) attempt);
        return record;
    }

    /**
     * Gets the result from a CompletableFuture, handling exceptions appropriately.
     *
     * @param balFuture the CompletableFuture to get the result from
     * @return the result or throws an error
     */
    private static Object getResult(CompletableFuture<Object> balFuture) {
        try {
            return balFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ErrorCreator.createError(e);
        } catch (Throwable throwable) {
            throw ErrorCreator.createError(throwable);
        }
    }

    // -------------------------------------------------------------------------
    // completeHumanTask
    // -------------------------------------------------------------------------

    /**
     * Sends a {@code "taskCompletion"} signal to the human task child workflow identified by {@code taskWorkflowId},
     * completing the task with the supplied result.
     *
     * @param taskWorkflowId the Temporal workflow ID of the human task child workflow
     * @param result         the value to return to the waiting {@code awaitHumanTask} call
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object completeHumanTask(BString taskWorkflowId, Object result, Object callerRoles, Object userId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString("Workflow client not initialized"));
            }

            // Always verify the target is a HUMAN_TASK workflow; also enforce role
            // intersection when callerRoles is provided, and validate the completion
            // payload against the task's expected result type (ballerina-library#8866).
            BArray callerRolesArray = (callerRoles instanceof BArray ba) ? ba : null;
            Object validationError = validateHumanTaskAndRoles(client, taskWorkflowId.getValue(), callerRolesArray,
                                                               result, false);
            if (validationError != null) {
                return validationError;
            }

            Object javaResult = TypesUtil.convertBallerinaToJavaType(result);
            Map<String, Object> payload = new HashMap<>();
            payload.put("result", javaResult);
            // Embed audit fields so executeBuiltinHumanTask can store them in workflow history
            payload.put("completedBy", userId instanceof BString bs ? bs.getValue() : "unknown");
            payload.put("completedAt", java.time.Instant.now().toString());

            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(taskWorkflowId.getValue(),
                                                                                   "taskCompletion", payload);
            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to complete human task: task '" + taskWorkflowId.getValue() +
                                "' completed or was no longer running when signal was delivered"));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to complete human task: " + e.getMessage()));
        }
    }

    /**
     * Fails (rejects) a pending human task. Sends a {@code taskCompletion} signal whose envelope
     * carries a top-level {@code __rejected} marker (plus the reason and optional details), so the
     * built-in human task workflow fails with {@code HUMANTASK_REJECTED} instead of completing
     * (ballerina-library#8892). The rejection metadata lives in the signal envelope — not inside the
     * user-facing {@code result} payload — so a legitimate completion result that happens to contain
     * an {@code __rejected} field is never misread as a rejection.
     *
     * @param taskWorkflowId the Temporal workflow ID of the human task child workflow
     * @param reason         human-readable rejection reason (becomes the task failure message)
     * @param details        optional structured details recorded with the rejection
     * @param callerRoles    optional caller roles for authorization enforcement
     * @param userId         optional user ID stored in the audit trail
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object failHumanTask(BString taskWorkflowId, BString reason, Object details,
                                       Object callerRoles, Object userId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString("Workflow client not initialized"));
            }

            // Kind/status/role checks only — a rejection carries no result payload to validate.
            BArray callerRolesArray = (callerRoles instanceof BArray ba) ? ba : null;
            Object validationError = validateHumanTaskAndRoles(client, taskWorkflowId.getValue(), callerRolesArray,
                                                               null, true);
            if (validationError != null) {
                return validationError;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("__rejected", true);
            payload.put("reason", reason.getValue());
            if (details != null) {
                payload.put("details", TypesUtil.convertBallerinaToJavaType(details));
            }
            payload.put("completedBy", userId instanceof BString bs ? bs.getValue() : "unknown");
            payload.put("completedAt", java.time.Instant.now().toString());

            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(taskWorkflowId.getValue(),
                                                                                   "taskCompletion", payload);
            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to fail human task: task '" + taskWorkflowId.getValue() +
                                "' completed or was no longer running when signal was delivered"));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to fail human task: " + e.getMessage()));
        }
    }

    /**
     * Fetches the Temporal memo for {@code taskWorkflowId} and:
     * <ol>
     *   <li>Always asserts {@code workflowKind == "HUMAN_TASK"} — prevents signalling
     *       non-human workflows via {@code completeHumanTask}.</li>
     *   <li>When {@code callerRolesArray} is non-null, additionally verifies that at least
     *       one caller role is present in the task's {@code userRoles} memo field.</li>
     * </ol>
     *
     * <p>Returns {@code null} when all checks pass, or a Ballerina error otherwise.
     *
     * <p>If the {@code userRoles} memo field is absent or cannot be decoded the role
     * intersection is skipped (backward-compatible with tasks started before role metadata
     * was added).  The {@code workflowKind} check is never skipped.
     */
    private static Object validateHumanTaskAndRoles(WorkflowClient client, String taskWorkflowId,
                                                    BArray callerRolesArray, Object result,
                                                    boolean skipPayloadValidation) {
        try {
            DescribeWorkflowExecutionRequest req = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(taskWorkflowId).build()).build();

            DescribeWorkflowExecutionResponse resp = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(
                    GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS).describeWorkflowExecution(req);

            WorkflowExecutionInfo execInfo = resp.getWorkflowExecutionInfo();

            // 0. Status check — reject tasks that are no longer running
            WorkflowExecutionStatus execStatus = execInfo.getStatus();
            if (execStatus != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Human task '" + taskWorkflowId + "' is not running (status=" + convertStatus(execStatus) +
                                ")"));
            }

            Map<String, io.temporal.api.common.v1.Payload> memoFields = execInfo.getMemo().getFieldsMap();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

            // 1. workflowKind check — always enforced
            String workflowKind = null;
            try {
                io.temporal.api.common.v1.Payload kindPl = memoFields.get("workflowKind");
                if (kindPl != null) {
                    workflowKind = dc.fromPayload(kindPl, String.class, String.class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode workflowKind from memo for '{}': {}", taskWorkflowId, e.getMessage());
            }
            if (!"HUMAN_TASK".equals(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Invalid task: '" + taskWorkflowId + "' is not a human task workflow (workflowKind=" +
                                workflowKind + ")"));
            }

            // 2. Payload type check — reject completions whose result does not match the task's expected type.
            // This runs before the signal is sent so an invalid payload never completes the task
            // (ballerina-library#8866). Skipped when the expected type is unknown in this JVM, and for
            // rejections (failHumanTask), which carry no result payload.
            if (!skipPayloadValidation) {
                Object payloadError = validateCompletionPayload(dc, memoFields, result);
                if (payloadError != null) {
                    return payloadError;
                }
            }

            // 3. Role intersection — only when callerRoles was supplied
            if (callerRolesArray == null) {
                return null;
            }

            Set<String> allowedRoles = new HashSet<>();
            try {
                io.temporal.api.common.v1.Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    String[] rolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                    allowedRoles.addAll(Arrays.asList(rolesArr));
                }
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to decode task roles for '" + taskWorkflowId + "': " + e.getMessage()));
            }

            if (allowedRoles.isEmpty()) {
                // No roles configured on the task — nothing to enforce.
                return null;
            }

            for (int i = 0; i < callerRolesArray.size(); i++) {
                if (allowedRoles.contains(callerRolesArray.get(i).toString())) {
                    return null; // at least one matching role — authorized
                }
            }

            return ErrorCreator.createError(StringUtils.fromString(
                    "Unauthorized: caller does not have a required role to complete task '" + taskWorkflowId +
                            "'. Required one of: " + allowedRoles));
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to validate task '" + taskWorkflowId + "': " + e.getMessage()));
        }
    }

    /**
     * Validates a completion {@code result} against the human task's expected result type before the task is completed.
     * <p>
     * The expected type is looked up from the in-JVM registry populated by {@code awaitHumanTask}, keyed by the human
     * task workflow type ({@code "humantask-" + qualifiedTaskName}). When the type is unknown in this JVM (e.g. after a
     * worker restart, or when completion is served by a separate process) validation is skipped and the worker-side
     * coercion remains the safety net. A mismatch returns a Ballerina error whose message is prefixed with
     * {@code "Invalid payload"} so the management HTTP layer can map it to 422 (ballerina-library#8866).
     *
     * @return {@code null} when the payload is valid or cannot be validated; a Ballerina error on a type mismatch
     */
    private static Object validateCompletionPayload(io.temporal.common.converter.DataConverter dc,
                                                    Map<String, io.temporal.api.common.v1.Payload> memoFields,
                                                    Object result) {
        String qualifiedTaskName;
        try {
            io.temporal.api.common.v1.Payload namePl = memoFields.get("taskName");
            if (namePl == null) {
                return null;
            }
            qualifiedTaskName = dc.fromPayload(namePl, String.class, String.class);
        } catch (Exception e) {
            return null; // taskName unavailable — skip type validation
        }
        if (qualifiedTaskName == null || qualifiedTaskName.isBlank()) {
            return null;
        }

        io.ballerina.runtime.api.types.Type expectedType =
                WorkflowWorkerNative.getHumanTaskResultType("humantask-" + qualifiedTaskName);
        if (expectedType == null) {
            return null; // expected type unknown in this JVM — cannot validate here
        }

        Object converted = TypesUtil.validateAndConvert(result, expectedType);
        if (converted instanceof io.ballerina.runtime.api.values.BError err) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Invalid payload for human task '" + qualifiedTaskName + "': " + err.getMessage()));
        }
        return null;
    }

}
