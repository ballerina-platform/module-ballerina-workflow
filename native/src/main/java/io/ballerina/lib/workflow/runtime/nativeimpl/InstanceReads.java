/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one client-side read of an instance's status and result that workflows and durable agents
 * share, and the one error vocabulary it answers in: {@code WorkflowInProgressError} while the
 * instance runs, {@code InstanceFailedError} when it closed without a result,
 * {@code InstanceNotFoundError} for an id nothing holds.
 *
 * @since 0.11.0
 */
public final class InstanceReads {

    public static final String IN_PROGRESS_ERROR = "WorkflowInProgressError";
    public static final String FAILED_ERROR = "InstanceFailedError";
    public static final String NOT_FOUND_ERROR = "InstanceNotFoundError";
    private static final String FAILED_DETAIL = "InstanceFailedDetail";
    private static final String NOT_FOUND_DETAIL = "InstanceNotFoundDetail";
    private static final BString INSTANCE_ID = StringUtils.fromString("instanceId");
    private static final BString STATUS = StringUtils.fromString("status");
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String CONTINUED_AS_NEW = "CONTINUED_AS_NEW";
    // The members of the Ballerina InstanceStatus enum: nothing else may cross the boundary.
    private static final java.util.Set<String> INSTANCE_STATUSES = java.util.Set.of(STATUS_RUNNING,
            STATUS_SUSPENDED, "COMPLETED", STATUS_FAILED, "CANCELED", "TERMINATED", "TIMED_OUT");
    private static final java.util.Set<String> CLOSED_FAILURES = java.util.Set.of(STATUS_FAILED, "CANCELED",
            "TERMINATED", "TIMED_OUT");

    private InstanceReads() {
    }

    /**
     * What one describe said about the run holding an id.
     *
     * @param status the run's status name
     * @param runId  which run it was
     */
    private record RunStatus(String status, String runId) {
    }

    /**
     * The instance's status as the management API reports it, {@code SUSPENDED} included.
     *
     * @param client       the Temporal client
     * @param instanceId   the instance
     * @param expectedType the Temporal type the instance must have, or null for any
     * @return the status name, or an {@code InstanceNotFoundError}
     */
    public static Object statusOf(WorkflowClient client, String instanceId, String expectedType) {
        Object described = describe(client, instanceId, expectedType);
        return described instanceof RunStatus run ? run.status() : described;
    }

    // The status and run id of the latest run under the id, or a read error.
    private static Object describe(WorkflowClient client, String instanceId, String expectedType) {
        try {
            WorkflowExecutionInfo info = client.newUntypedWorkflowStub(instanceId).describe()
                    .getWorkflowExecutionInfo();
            if (expectedType != null && !expectedType.equals(info.getType().getName())) {
                return notFound(instanceId);
            }
            String status = WorkflowNative.convertStatus(info.getStatus());
            if (CONTINUED_AS_NEW.equals(status)) {
                // A run that continued is, to a caller, still running.
                status = STATUS_RUNNING;
            }
            if (STATUS_RUNNING.equals(status) && WorkflowWorkerNative.isSuspendedMemo(client, info)) {
                status = STATUS_SUSPENDED;
            }
            if (!INSTANCE_STATUSES.contains(status)) {
                return ErrorCreator.createError(StringUtils.fromString("The status of instance '" + instanceId
                        + "' is not known: the engine reported " + status));
            }
            return new RunStatus(status, info.getExecution().getRunId());
        } catch (Exception e) {
            return isNotFound(e) ? notFound(instanceId)
                    : ErrorCreator.createError(StringUtils.fromString("Failed to get the status of instance '"
                            + instanceId + "': " + e.getMessage()));
        }
    }

    /**
     * Reads an instance's result. Non-blocking, a running instance answers {@code WorkflowInProgressError};
     * blocking, the read waits up to {@code timeoutMillis} (null for as long as it takes) and answers the
     * same when the wait runs out.
     *
     * @param client        the Temporal client
     * @param instanceId    the instance
     * @param expectedType  the Temporal type the instance must have, or null for any
     * @param blocking      whether to wait for completion
     * @param timeoutMillis the wait bound, or null
     * @param typedesc      the caller's expected result type
     * @return the typed result or one of the read errors
     */
    public static Object read(WorkflowClient client, String instanceId, String expectedType, boolean blocking,
                              Long timeoutMillis, BTypedesc typedesc) {
        Object described = describe(client, instanceId, expectedType);
        if (described instanceof BError error) {
            return error;
        }
        RunStatus run = (RunStatus) described;
        if (!blocking && isOpen(run.status())) {
            return inProgress(instanceId);
        }
        try {
            // Pinned to the run the describe saw: an id reused for a new run between the two calls
            // must not turn a non-blocking read of a closed run into a wait on the new one.
            WorkflowStub stub = client.newUntypedWorkflowStub(instanceId, java.util.Optional.of(run.runId()),
                    java.util.Optional.empty());
            Object raw = blocking && timeoutMillis != null
                    ? stub.getResult(timeoutMillis, TimeUnit.MILLISECONDS, Object.class)
                    : stub.getResult(Object.class);
            Object value = TypesUtil.convertJavaToBallerinaType(raw);
            return TypesUtil.cloneWithType(value, typedesc.getDescribingType());
        } catch (TimeoutException e) {
            return inProgress(instanceId);
        } catch (WorkflowFailedException e) {
            Object closed = statusOf(client, instanceId, null);
            // Only a closed status can explain a failed read; anything else is reported as FAILED.
            return failed(instanceId, closed instanceof String s && CLOSED_FAILURES.contains(s) ? s : STATUS_FAILED,
                    failureMessage(e));
        } catch (Exception e) {
            if (isNotFound(e)) {
                return notFound(instanceId);
            }
            return ErrorCreator.createError(StringUtils.fromString("Failed to read the result of instance '"
                    + instanceId + "': " + e.getMessage()));
        }
    }

    /**
     * A wait bound in milliseconds from a {@code Duration?} argument, or null for no bound.
     *
     * @param duration the Ballerina Duration record, or null
     * @return the bound, or null
     */
    public static Long timeoutMillisOf(Object duration) {
        if (!(duration instanceof BMap<?, ?> map)) {
            return null;
        }
        @SuppressWarnings("unchecked") BMap<BString, Object> record = (BMap<BString, Object>) map;
        return WaitUtils.durationToMillis(record);
    }

    /** Builds a {@code workflow:WorkflowInProgressError}. */
    public static BError inProgress(String instanceId) {
        return typed(IN_PROGRESS_ERROR, "Instance '" + instanceId + "' is still in progress", null);
    }

    /** Builds a {@code workflow:InstanceFailedError} carrying the closed status. */
    public static BError failed(String instanceId, String status, String message) {
        BMap<BString, Object> detail = ValueCreator.createRecordValue(ModuleUtils.getModule(), FAILED_DETAIL);
        detail.put(INSTANCE_ID, StringUtils.fromString(instanceId));
        detail.put(STATUS, StringUtils.fromString(status));
        return typed(FAILED_ERROR, "Instance '" + instanceId + "' " + describeClosed(status) + ": " + message, detail);
    }

    /** Builds a {@code workflow:InstanceNotFoundError}. */
    public static BError notFound(String instanceId) {
        BMap<BString, Object> detail = ValueCreator.createRecordValue(ModuleUtils.getModule(), NOT_FOUND_DETAIL);
        detail.put(INSTANCE_ID, StringUtils.fromString(instanceId));
        return typed(NOT_FOUND_ERROR, "No instance with id '" + instanceId + "'", detail);
    }

    private static BError typed(String type, String message, BMap<BString, Object> detail) {
        try {
            return ErrorCreator.createError(ModuleUtils.getModule(), type, StringUtils.fromString(message), null,
                    detail);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(type + ": " + message), detail);
        }
    }

    private static boolean isOpen(String status) {
        return STATUS_RUNNING.equals(status) || STATUS_SUSPENDED.equals(status);
    }

    private static String describeClosed(String status) {
        return switch (status) {
            case "CANCELED" -> "was cancelled";
            case "TERMINATED" -> "was terminated";
            case "TIMED_OUT" -> "timed out";
            default -> "failed";
        };
    }

    private static String failureMessage(WorkflowFailedException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure failure) {
            return failure.getOriginalMessage();
        }
        return cause != null && cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }

    private static boolean isNotFound(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof WorkflowNotFoundException) {
                return true;
            }
            if (current instanceof StatusRuntimeException status
                    && status.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
