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
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/**
 * Native implementation for workflow module functions.
 * <p>
 * This class provides the native implementations for the external functions
 * defined in the Ballerina workflow module:
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
     * Builds {@link io.temporal.activity.ActivityOptions} for implicit (built-in) activities.
     * Uses the global default activity retry policy from {@link WorkflowWorkerNative} when
     * available, falling back to a single-attempt policy otherwise.
     *
     * @param timeout the start-to-close timeout for the activity
     * @return configured ActivityOptions
     */
    private static io.temporal.activity.ActivityOptions buildImplicitActivityOptions(Duration timeout) {
        io.temporal.common.RetryOptions retryOptions =
                WorkflowWorkerNative.getDefaultActivityRetryOptions();
        if (retryOptions == null) {
            retryOptions = io.temporal.common.RetryOptions.newBuilder()
                    .setMaximumAttempts(1)
                    .build();
        }
        return io.temporal.activity.ActivityOptions.newBuilder()
                .setStartToCloseTimeout(timeout)
                .setRetryOptions(retryOptions)
                .build();
    }

    /**
     * Handles errors from implicit activity execution, extracting the root cause
     * message from Temporal's {@link io.temporal.failure.ActivityFailure} wrapper.
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
     * Starts a new workflow with the given input.
     * Returns the workflow ID that can be used to track and interact with the workflow.
     * <p>
     * When called from inside a workflow context, the call is automatically routed
     * through an implicit activity so that the operation is deterministic and
     * replay-safe. The function pointer is resolved to its string name for
     * serialization since function pointers are not {@code anydata}.
     * <p>
     * When called from outside a workflow (e.g., HTTP handler, test), the workflow
     * is started directly via the Temporal client.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to execute (must be annotated with @Workflow)
     * @param input the optional input data for the process (nil or map)
     * @return the workflow ID as a string, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object run(Environment env, BFunctionPointer processFunction,
                                        Object input) {
        // Extract the process name from the function pointer (works in both contexts)
        String processName = processFunction.getType().getName();

        // Convert input to Java type (handle nil case)
        // In Ballerina Java interop, nil () is passed as null, so a simple null check suffices.
        Object javaInput = null;
        if (input != null) {
            if (input instanceof BMap) {
                @SuppressWarnings("unchecked")
                BMap<BString, Object> bMapInput = (BMap<BString, Object>) input;
                javaInput = TypesUtil.convertBallerinaToJavaType(bMapInput);
            }
            // Other anydata subtypes (int, string, boolean, etc.) are not currently
            // supported as workflow input — only record (BMap) types are expected.
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
                    String workflowId = WorkflowRuntime.getInstance()
                            .createInstance(processName, finalInput);
                    balFuture.complete(StringUtils.fromString(workflowId));
                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(
                            StringUtils.fromString(ERR_START_PROCESS + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Routes a {@code workflow:run} call through a built-in implicit activity
     * so that it is deterministic inside a workflow execution.
     *
     * @param processName the workflow type name (extracted from the function pointer)
     * @param javaInput   the input data converted to a Java type (may be null)
     * @return a Ballerina string containing the new workflow ID, or a BError
     */
    private static Object runAsImplicitActivity(String processName, Object javaInput) {
        try {
            io.temporal.workflow.ActivityStub stub =
                    Workflow.newUntypedActivityStub(
                            buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            String workflowId = stub.execute(
                    WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_RUN,
                    String.class,
                    processName, javaInput);
            return StringUtils.fromString(workflowId);
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_START_PROCESS);
        }
    }

    /**
     * Native implementation for sendData function.
     * <p>
     * Sends data to a running workflow process by workflow ID and data name.
     * All parameters are required.
     * <p>
     * When called from inside a workflow context, the call is automatically routed
     * through an implicit activity for determinism.
     * <p>
     * Note: {@code workflowFunction} is not used at runtime; it exists in the signature
     * so the compiler plugin can validate that the target function carries the
     * {@code @Workflow} annotation and that the data type matches the workflow's events
     * record. Removing it would be a breaking API change.
     *
     * @param env the Ballerina runtime environment
     * @param workflowFunction the workflow function (unused at runtime; used by the compiler plugin for validation)
     * @param workflowId the workflow ID to send the data to
     * @param dataName the name identifying the data (must match an events record field)
     * @param data the data to send
     * @return null on success, or an error
     */
    public static Object sendData(Environment env, BFunctionPointer workflowFunction, 
            BString workflowId, BString dataName, Object data) {
        // Convert data to Java type
        Object javaData;
        if (data instanceof BMap) {
            @SuppressWarnings("unchecked")
            BMap<BString, Object> bMapData = (BMap<BString, Object>) data;
            javaData = TypesUtil.convertBallerinaToJavaType(bMapData);
        } else {
            javaData = data;
        }

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
                    WorkflowRuntime.getInstance().sendSignalToWorkflow(
                            workflowIdStr, dataNameStr, javaData);
                    balFuture.complete(null);
                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(
                            StringUtils.fromString(ERR_SEND_DATA + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Routes a {@code workflow:sendData} call through a built-in implicit activity.
     */
    private static Object sendDataAsImplicitActivity(String workflowId, String dataName,
                                                     Object javaData) {
        try {
            io.temporal.workflow.ActivityStub stub =
                    Workflow.newUntypedActivityStub(
                            buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            stub.execute(
                    WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_SEND_DATA,
                    Void.class,
                    workflowId, dataName, javaData);
            return null;
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_SEND_DATA);
        }
    }

    /**
     * Checks whether the current thread is executing inside a Temporal workflow
     * context. Uses Temporal's thread-local workflow info to detect this.
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
     * Returns information about all registered workflow processes and their activities.
     * This is useful for testing and introspection.
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
            RecordType processRegType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), "ProcessRegistration").getType();

            // Create a typed map for map<ProcessRegistration>
            MapType mapType = TypeCreator.createMapType(processRegType);
            BMap<BString, Object> resultMap = ValueCreator.createMapValue(mapType);

            for (Map.Entry<String, BFunctionPointer> entry : processRegistry.entrySet()) {
                String processName = entry.getKey();

                // Create a ProcessRegistration record
                BMap<BString, Object> processRecord = ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), "ProcessRegistration");
                processRecord.put(StringUtils.fromString("name"), StringUtils.fromString(processName));

                // Find activities for this process (activities are registered as "processName.activityName")
                List<String> processActivities = new ArrayList<>();
                for (String activityName : activityRegistry.keySet()) {
                    if (activityName.startsWith(processName + ".")) {
                        // Extract just the activity name part
                        String shortName = activityName.substring(processName.length() + 1);
                        processActivities.add(shortName);
                    }
                }

                BString[] activityArray = processActivities.stream()
                        .map(StringUtils::fromString)
                        .toArray(BString[]::new);
                BArray activitiesBalArray = ValueCreator.createArrayValue(activityArray);
                processRecord.put(StringUtils.fromString("activities"), activitiesBalArray);

                // Get events for this process from the event registry
                List<String> processEvents = eventRegistry.getOrDefault(processName, new ArrayList<>());
                BString[] eventArray = processEvents.stream()
                        .map(StringUtils::fromString)
                        .toArray(BString[]::new);
                BArray eventsBalArray = ValueCreator.createArrayValue(eventArray);
                processRecord.put(StringUtils.fromString("events"), eventsBalArray);

                resultMap.put(StringUtils.fromString(processName), processRecord);
            }

            return resultMap;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString(ERR_GET_REGISTERED + e.getMessage()));
        }
    }

    /**
     * Native implementation for getWorkflowResult function.
     * <p>
     * Gets the execution result of a workflow, waiting for completion if necessary.
     * Returns detailed information including the workflow result and activity invocations.
     *
     * @param workflowId the ID of the workflow to get the result for
     * @param timeoutSeconds maximum time to wait for workflow completion
     * @return a WorkflowExecutionInfo record or an error
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
                return ErrorCreator.createError(
                        StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();

            // Fetch workflowType via DescribeWorkflowExecution (best-effort; non-fatal).
            // This is done first so the type is available even when the workflow has already
            // completed and the stub.getResult() call below returns immediately.
            String workflowType = "";
            try {
                DescribeWorkflowExecutionRequest describeRequest =
                        DescribeWorkflowExecutionRequest.newBuilder()
                                .setNamespace(client.getOptions().getNamespace())
                                .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                        .setWorkflowId(wfId)
                                        .build())
                                .build();
                DescribeWorkflowExecutionResponse describeResponse = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .describeWorkflowExecution(describeRequest);
                workflowType = describeResponse.getWorkflowExecutionInfo().getType().getName();
            } catch (Exception e) {
                // Non-fatal: workflowType stays empty if the describe call fails
            }

            // Create an untyped stub to get the result
            WorkflowStub stub = client.newUntypedWorkflowStub(wfId);

            // Wait for the workflow to complete and get the result
            Object result = null;
            String status;
            String errorMessage = null;

            try {
                // Get the result with a timeout
                result = stub.getResult(timeoutSeconds, TimeUnit.SECONDS, Object.class);
                status = "COMPLETED";
            } catch (io.temporal.client.WorkflowFailedException e) {
                status = "FAILED";
                errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            } catch (java.util.concurrent.TimeoutException e) {
                status = "RUNNING"; // Still running after timeout
            } catch (Exception e) {
                status = "FAILED";
                errorMessage = e.getMessage();
            }

            // Build the WorkflowExecutionInfo record (with activity history)
            return buildWorkflowExecutionInfo(wfId, workflowType, status, result, errorMessage, client);

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString(ERR_GET_RESULT + e.getMessage()));
        }
    }

    /**
     * Routes a {@code workflow:getWorkflowResult} call through a built-in implicit activity.
     * The activity returns a serializable Map which is then converted to a WorkflowExecutionInfo record.
     */
    @SuppressWarnings("unchecked")
    private static Object getWorkflowResultAsImplicitActivity(String workflowId, int timeoutSeconds) {
        try {
            // Use a timeout that accounts for the workflow's own timeout plus buffer
            Duration activityTimeout = Duration.ofSeconds(timeoutSeconds + 30);
            io.temporal.workflow.ActivityStub stub =
                    Workflow.newUntypedActivityStub(buildImplicitActivityOptions(activityTimeout));
            Map<String, Object> info = stub.execute(
                    WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_GET_RESULT,
                    Map.class,
                    workflowId, timeoutSeconds);

            String status = (String) info.get("status");
            Object result = info.get("result");
            String errorMessage = (String) info.get("errorMessage");
            String workflowType = (String) info.getOrDefault("workflowType", "");

            return buildWorkflowExecutionInfo(workflowId, workflowType, status, result, errorMessage);
        } catch (Exception e) {
            return handleImplicitActivityError(e, ERR_GET_RESULT);
        }
    }

    /**
     * Native implementation for getWorkflowInfo function.
     * <p>
     * Gets information about a workflow execution without waiting for completion.
     * Returns the current state including workflow type and status.
     * <p>
     * When called from inside a workflow context, the blocking gRPC call is routed
     * through an implicit activity to preserve determinism and avoid a
     * PotentialDeadlockException.
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
                return ErrorCreator.createError(
                        StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();

            // Describe the workflow execution to get its status
            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                            .setWorkflowId(wfId)
                            .build())
                    .build();

            DescribeWorkflowExecutionResponse response = client.getWorkflowServiceStubs()
                    .blockingStub()
                    .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .describeWorkflowExecution(request);

            WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            String workflowType = execInfo.getType().getName();
            String status = convertStatus(execInfo.getStatus());

            return buildWorkflowExecutionInfo(wfId, workflowType, status, null, null);

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString(ERR_GET_INFO + e.getMessage()));
        }
    }

    /**
     * Routes a {@code workflow:getWorkflowInfo} call through a built-in implicit activity
     * when invoked from inside a workflow, ensuring the blocking describeWorkflowExecution
     * RPC is performed off the workflow thread and the result is deterministic on replay.
     */
    @SuppressWarnings("unchecked")
    private static Object getWorkflowInfoAsImplicitActivity(String workflowId) {
        try {
            io.temporal.workflow.ActivityStub stub =
                    Workflow.newUntypedActivityStub(buildImplicitActivityOptions(DEFAULT_IMPLICIT_ACTIVITY_TIMEOUT));
            Map<String, Object> info = stub.execute(
                    WorkflowWorkerNative.BallerinaActivityAdapter.BUILTIN_GET_INFO,
                    Map.class,
                    workflowId);

            String workflowType = (String) info.getOrDefault("workflowType", "");
            String status = (String) info.getOrDefault("status", "UNKNOWN");

            return buildWorkflowExecutionInfo(workflowId, workflowType, status, null, null);
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
     * Builds a WorkflowExecutionInfo Ballerina record.
     * When a {@link WorkflowClient} is provided, activity invocations are fetched
     * from the workflow's event history; otherwise the array is left empty.
     */
    private static BMap<BString, Object> buildWorkflowExecutionInfo(
            String workflowId,
            String workflowType,
            String status,
            Object result,
            String errorMessage) {
        return buildWorkflowExecutionInfo(workflowId, workflowType, status, result, errorMessage, null);
    }

    /**
     * Overload that accepts an optional {@link WorkflowClient} to fetch activity history.
     */
    private static BMap<BString, Object> buildWorkflowExecutionInfo(
            String workflowId,
            String workflowType,
            String status,
            Object result,
            String errorMessage,
            WorkflowClient client) {

        BMap<BString, Object> record = ValueCreator.createRecordValue(
                ModuleUtils.getModule(), "WorkflowExecutionInfo");

        record.put(StringUtils.fromString("workflowId"), StringUtils.fromString(workflowId));
        record.put(StringUtils.fromString("workflowType"), StringUtils.fromString(workflowType));
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

        // Fetch activity invocations from workflow event history when a client is available
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
     * Creates an empty typed array for the {@code activityInvocations} field.
     */
    private static BArray createEmptyActivityInvocationsArray() {
        RecordType invocationType = (RecordType) ValueCreator.createRecordValue(
                ModuleUtils.getModule(), "ActivityInvocation").getType();
        return ValueCreator.createArrayValue(
                TypeCreator.createArrayType(invocationType));
    }

    /**
     * Fetches activity invocation history from the Temporal server.
     * <p>
     * Iterates over the workflow's event history, pairing
     * {@code ACTIVITY_TASK_SCHEDULED} events with their terminal events
     * ({@code COMPLETED}, {@code FAILED}, {@code TIMED_OUT}, {@code CANCELED}).
     * Each {@code ACTIVITY_TASK_STARTED} event carries the attempt number
     * which is recorded in the {@code ActivityInvocation.attempt} field.
     * <p>
     * When an activity is retried, multiple (scheduled → started → failed) cycles
     * appear in the history. Each cycle produces a separate
     * {@code ActivityInvocation} entry so the caller can see every attempt.
     *
     * @param client     the Temporal client for gRPC calls
     * @param workflowId the workflow execution to query
     * @return a Ballerina array of {@code ActivityInvocation} records
     */
    private static BArray fetchActivityInvocations(WorkflowClient client, String workflowId) {
        RecordType invocationType = (RecordType) ValueCreator.createRecordValue(
                ModuleUtils.getModule(), "ActivityInvocation").getType();
        BArray invocations = ValueCreator.createArrayValue(
                TypeCreator.createArrayType(invocationType));

        try {
            // Map: scheduledEventId → activity name (from SCHEDULED events)
            Map<Long, String> scheduledActivities = new HashMap<>();
            // Map: scheduledEventId → attempt number (from STARTED events, last one wins)
            Map<Long, Integer> scheduledAttempts = new HashMap<>();

            com.google.protobuf.ByteString nextPageToken = com.google.protobuf.ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest.Builder reqBuilder =
                        GetWorkflowExecutionHistoryRequest.newBuilder()
                                .setNamespace(client.getOptions().getNamespace())
                                .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                        .setWorkflowId(workflowId)
                                        .build());
                if (!nextPageToken.isEmpty()) {
                    reqBuilder.setNextPageToken(nextPageToken);
                }

                GetWorkflowExecutionHistoryResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(reqBuilder.build());

                for (HistoryEvent event : response.getHistory().getEventsList()) {
                    EventType eventType = event.getEventType();

                    if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
                        String activityName = event.getActivityTaskScheduledEventAttributes()
                                .getActivityType().getName();
                        scheduledActivities.put(event.getEventId(), activityName);
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED) {
                        long scheduledId = event.getActivityTaskStartedEventAttributes()
                                .getScheduledEventId();
                        int attempt = event.getActivityTaskStartedEventAttributes().getAttempt();
                        scheduledAttempts.put(scheduledId, attempt);
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
                        long scheduledId = event.getActivityTaskCompletedEventAttributes()
                                .getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(
                                name, "COMPLETED", null, attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED) {
                        long scheduledId = event.getActivityTaskFailedEventAttributes()
                                .getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        String failMsg = "";
                        if (event.getActivityTaskFailedEventAttributes().hasFailure()) {
                            failMsg = event.getActivityTaskFailedEventAttributes()
                                    .getFailure().getMessage();
                        }
                        invocations.append(createActivityInvocation(
                                name, "FAILED", failMsg, attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT) {
                        long scheduledId = event.getActivityTaskTimedOutEventAttributes()
                                .getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(
                                name, "TIMED_OUT", "Activity timed out", attempt));
                    } else if (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED) {
                        long scheduledId = event.getActivityTaskCanceledEventAttributes()
                                .getScheduledEventId();
                        String name = scheduledActivities.getOrDefault(scheduledId, "unknown");
                        int attempt = scheduledAttempts.getOrDefault(scheduledId, 1);
                        invocations.append(createActivityInvocation(
                                name, "CANCELED", null, attempt));
                    }
                }

                nextPageToken = response.getNextPageToken();
            } while (!nextPageToken.isEmpty());

        } catch (Exception e) {
            LOGGER.debug("Failed to fetch activity history for workflow '{}': {}",
                    workflowId, e.getMessage());
        }

        return invocations;
    }

    /**
     * Creates a single {@code ActivityInvocation} Ballerina record.
     */
    private static BMap<BString, Object> createActivityInvocation(
            String activityName, String status, String errorMessage, int attempt) {
        BMap<BString, Object> record = ValueCreator.createRecordValue(
                ModuleUtils.getModule(), "ActivityInvocation");
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
}
