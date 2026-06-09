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
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Native implementations for the {@code workflow.management} submodule.
 * <p>
 * Provides inspection and lifecycle-control operations:
 * <ul>
 *   <li>{@link #getWorkflowInfo} – describe a single execution</li>
 *   <li>{@link #listWorkflowDefinitions} – list registered workflow types</li>
 *   <li>{@link #suspendWorkflow} – send {@code __wf_suspend} signal</li>
 *   <li>{@link #resumeWorkflow} – send {@code __wf_resume} signal</li>
 * </ul>
 *
 * @since 0.4.0
 */
public final class ManagementNative {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementNative.class);

    private static final long GET_INFO_DEADLINE_SECONDS = 5;
    private static final String ERR_CLIENT_NOT_INIT = "Workflow client not initialized";

    private ManagementNative() {
        // Utility class — prevent instantiation
    }

    /**
     * Returns current execution info for a workflow without waiting for completion.
     * Delegates to {@link WorkflowNative#getWorkflowInfo(BString)}.
     *
     * @param workflowId the workflow ID
     * @return a Ballerina {@code WorkflowExecutionInfo} record or an error
     */
    public static Object getWorkflowInfo(BString workflowId) {
        return WorkflowNative.getWorkflowInfo(workflowId);
    }

    /**
     * Returns current execution info for a specific run of a workflow.
     * Unlike {@link #getWorkflowInfo} which targets the latest run, this method pins
     * the Describe call to the exact runId supplied by the caller.
     *
     * @param workflowId the workflow ID
     * @param runId the specific run ID
     * @return a Ballerina {@code WorkflowExecutionInfo} record or an error
     */
    public static Object getWorkflowInfoForRun(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String wfId = workflowId.getValue();
            String wfRunId = runId.getValue();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder()
                                    .setWorkflowId(wfId)
                                    .setRunId(wfRunId)
                                    .build())
                            .build();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse response =
                    client.getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            io.temporal.api.workflow.v1.WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            String workflowType = execInfo.getType().getName();
            String status = convertStatus(execInfo.getStatus());

            return WorkflowNative.buildWorkflowExecutionInfo(wfId, workflowType, status, null, null, client);
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow info: " + e.getMessage()));
        }
    }

    /**
     * Lists registered workflow types, for use in the workflow launcher UI.
     * Returns one entry per registered workflow function. The {@code inputSchema} field is
     * {@code null} until the compiler plugin generates JSON Schema at build time.
     *
     * @return a Ballerina {@code WorkflowDefinition[]} or an error
     */
    public static Object listWorkflowDefinitions() {
        try {
            RecordType defType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "WorkflowDefinition").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(defType));

            for (String workflowType : WorkflowWorkerNative.getProcessRegistry().keySet()) {
                BMap<BString, Object> def = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "WorkflowDefinition");
                def.put(StringUtils.fromString("workflowType"),
                        StringUtils.fromString(workflowType));
                def.put(StringUtils.fromString("inputSchema"), null);
                // All registered workflow types have an active worker (this worker)
                def.put(StringUtils.fromString("isActive"), true);
                def.put(StringUtils.fromString("workerCount"), 1L);
                result.append(def);
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list workflow definitions: " + e.getMessage()));
        }
    }

    /**
     * Requests a running workflow to suspend by sending a {@code __wf_suspend} signal.
     *
     * @param workflowId the workflow ID to suspend
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object suspendWorkflow(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(
                    workflowId.getValue(), "__wf_suspend", null);
            if (!delivered) {
            return ErrorCreator.createError(
                StringUtils.fromString("Failed to suspend workflow: workflow not found: "
                    + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to suspend workflow: " + e.getMessage()));
        }
    }

    /**
     * Suspends a specific run of a workflow by sending a {@code __wf_suspend} signal
     * to the exact (workflowId, runId) pair, rather than the latest run.
     *
     * @param workflowId the workflow ID
     * @param runId the specific run ID to suspend
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object suspendWorkflowRun(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            WorkflowExecution exec = WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowId.getValue())
                    .setRunId(runId.getValue())
                    .build();
            WorkflowStub stub = client.newUntypedWorkflowStub(exec, Optional.empty());
            stub.signal("__wf_suspend");
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to suspend workflow: " + e.getMessage()));
        }
    }

    /**
     * Resumes a previously suspended workflow by sending a {@code __wf_resume} signal.
     *
     * @param workflowId the workflow ID to resume
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object resumeWorkflow(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(
                    workflowId.getValue(), "__wf_resume", null);
            if (!delivered) {
            return ErrorCreator.createError(
                StringUtils.fromString("Failed to resume workflow: workflow not found: "
                    + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to resume workflow: " + e.getMessage()));
        }
    }

    /**
     * Resumes a specific run of a suspended workflow by sending a {@code __wf_resume} signal
     * to the exact (workflowId, runId) pair, rather than the latest run.
     *
     * @param workflowId the workflow ID
     * @param runId the specific run ID to resume
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object resumeWorkflowRun(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            WorkflowExecution exec = WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowId.getValue())
                    .setRunId(runId.getValue())
                    .build();
            WorkflowStub stub = client.newUntypedWorkflowStub(exec, Optional.empty());
            stub.signal("__wf_resume");
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to resume workflow: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // HUMAN TASKS
    // -------------------------------------------------------------------------

    /**
     * Lists all human task instances across all parent workflows via Temporal's visibility API.
     * Filters executions whose workflow ID starts with {@code humantask-}. Task name and parent
     * workflow ID are extracted from the task's Temporal memo.
     *
     * @param status optional status filter (Ballerina naming: PENDING maps to Running, etc.)
     * @return a Ballerina {@code HumanTaskSummary[]} or an error
     */
    public static Object listAllHumanTasks(Object status, Object startTimeFrom, Object startTimeTo,
            Object closeTimeFrom, Object closeTimeTo) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String statusFilter = status instanceof BString bs ? bs.getValue() : null;
            // PENDING maps to Running in Temporal status
            String temporalStatus = statusFilter != null ? toHumanTaskTemporalStatus(statusFilter) : null;

            List<String> clauses = new ArrayList<>();
            if (temporalStatus != null) {
                clauses.add(String.format("ExecutionStatus = \"%s\"", temporalStatus));
            }
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");
            String query = String.join(" AND ", clauses);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "HumanTaskSummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            com.google.protobuf.ByteString pageToken = com.google.protobuf.ByteString.EMPTY;
            do {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(query)
                        .setPageSize(100)
                        .setNextPageToken(pageToken)
                        .build();

                ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .listWorkflowExecutions(request);

                for (io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo : response.getExecutionsList()) {
                    String wfId = wfInfo.getExecution().getWorkflowId();
                    if (!wfId.startsWith("humantask-")) {
                        continue;
                    }
                    result.append(toHumanTaskSummaryRecord(client, wfInfo));
                }

                pageToken = response.getNextPageToken();
            } while (!pageToken.isEmpty());

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list human tasks: " + e.getMessage()));
        }
    }

    /**
     * Verifies that a workflow ID refers to a human task (workflowKind == "HUMAN_TASK").
     * Used by cancelHumanTask to validate kind without checking user roles, because the
     * process itself may cancel a task when an alternative path makes it irrelevant.
     *
     * @param taskId the child workflow ID to check
     * @return {@code null} on success, or a Ballerina error if the ID is not a human task
     */
    public static Object assertIsHumanTask(BString taskId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String id = taskId.getValue();
            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest req =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
                            .build();
            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse resp =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(req);
            Map<String, io.temporal.api.common.v1.Payload> memoFields =
                    resp.getWorkflowExecutionInfo().getMemo().getFieldsMap();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();
            String workflowKind = decodeMemoString(dc, memoFields, "workflowKind", null);
            if (!"HUMAN_TASK".equals(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "'" + id + "' is not a human task (workflowKind=" + workflowKind + ")"));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to verify human task '" + taskId.getValue() + "': " + e.getMessage()));
        }
    }

    /**
     * Returns detailed info for a single human task by calling DescribeWorkflowExecution
     * and reading the memo fields set by {@code callHumanTask} at task creation.
     *
     * @param taskId the child workflow ID of the human task
     * @return a Ballerina {@code HumanTaskInfo} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getHumanTaskInfo(BString taskId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String taskIdStr = taskId.getValue();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder()
                                    .setWorkflowId(taskIdStr)
                                    .build())
                            .build();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse response =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            io.temporal.api.workflow.v1.WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            Map<String, io.temporal.api.common.v1.Payload> memoFields =
                    execInfo.getMemo().getFieldsMap();

            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

            String taskName     = decodeMemoString(dc, memoFields, "taskName", "");
            String parentId     = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
            String title        = decodeMemoString(dc, memoFields, "title", taskName);
            String description  = decodeMemoString(dc, memoFields, "description", "");
            String createdAt    = decodeMemoString(dc, memoFields, "createdAt", "");
            String formSchema   = decodeMemoString(dc, memoFields, "formSchema", null);

            String[] userRolesArr = new String[0];
            try {
                io.temporal.api.common.v1.Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode userRoles memo field: {}", e.getMessage());
            }

            Object payloadRaw = null;
            try {
                io.temporal.api.common.v1.Payload payloadPl = memoFields.get("payload");
                if (payloadPl != null) {
                    payloadRaw = dc.fromPayload(payloadPl, Object.class, Object.class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode payload memo field: {}", e.getMessage());
            }

            // Status and timestamps from visibility info
            String statusStr = convertStatus(execInfo.getStatus());
            com.google.protobuf.Timestamp st = execInfo.getStartTime();
            String startTime = Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString();
            String closeTime = null;
            com.google.protobuf.Timestamp ct = execInfo.getCloseTime();
            if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                closeTime = Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString();
            }

            BMap<BString, Object> record = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "HumanTaskInfo");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskIdStr));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
            record.put(StringUtils.fromString("status"), StringUtils.fromString(statusStr));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(startTime));
            record.put(StringUtils.fromString("closeTime"),
                    closeTime != null ? StringUtils.fromString(closeTime) : null);
            record.put(StringUtils.fromString("title"), StringUtils.fromString(title));
            record.put(StringUtils.fromString("description"), StringUtils.fromString(description));

            BArray roles = ValueCreator.createArrayValue(
                    TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
            for (String role : userRolesArr) {
                roles.append(StringUtils.fromString(role));
            }
            record.put(StringUtils.fromString("userRoles"), roles);

            Object bPayload = payloadRaw != null
                    ? io.ballerina.lib.workflow.utils.TypesUtil.convertJavaToBallerinaType(payloadRaw)
                    : null;
            record.put(StringUtils.fromString("payload"), bPayload);
            record.put(StringUtils.fromString("createdAt"), StringUtils.fromString(createdAt));
            record.put(StringUtils.fromString("formSchema"),
                    formSchema != null ? StringUtils.fromString(formSchema) : null);

            // Audit fields from the taskCompletion signal stored in workflow history
            String completedBy = readSignalField(client, taskIdStr, "taskCompletion", "completedBy");
            String completedAt = readSignalField(client, taskIdStr, "taskCompletion", "completedAt");
            Object resultRaw   = readSignalPayloadField(client, taskIdStr, "taskCompletion", "result");

            record.put(StringUtils.fromString("completedBy"),
                    completedBy != null ? StringUtils.fromString(completedBy) : null);
            record.put(StringUtils.fromString("completedAt"),
                    completedAt != null ? StringUtils.fromString(completedAt) : null);
            record.put(StringUtils.fromString("result"),
                    resultRaw != null
                            ? io.ballerina.lib.workflow.utils.TypesUtil.convertJavaToBallerinaType(resultRaw)
                            : null);

            return record;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get human task info: " + e.getMessage()));
        }
    }

    /**
     * Scans the parent workflow's event history for child humantask workflows, then
     * groups their IDs by task name and returns them sorted alphabetically.
     * <p>
     * Child workflow ID format: {@code humantask-{parentId}-{taskName}-{uuid}}
     * where UUID is always 36 characters. The task name is extracted by stripping
     * the fixed prefix and the trailing {@code -{uuid}} (37 characters).
     *
     * @param parentWorkflowId the parent workflow ID
     * @return a Ballerina {@code HumanTaskGroup[]} sorted by task name, or an error
     */
    public static Object listPendingHumanTasks(BString parentWorkflowId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String parentId = parentWorkflowId.getValue();
            String prefix = "humantask-" + parentId + "-";

            // TreeMap keeps task names sorted alphabetically
            TreeMap<String, List<String>> byTaskName = new TreeMap<>();
            com.google.protobuf.ByteString nextPageToken = com.google.protobuf.ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setExecution(WorkflowExecution.newBuilder()
                                .setWorkflowId(parentId)
                                .build())
                        .setNextPageToken(nextPageToken)
                        .build();

                GetWorkflowExecutionHistoryResponse resp = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(req);

                for (HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType()
                            == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
                        String childId = event
                                .getStartChildWorkflowExecutionInitiatedEventAttributes()
                                .getWorkflowId();
                        if (childId.startsWith(prefix)) {
                            String remainder = childId.substring(prefix.length());
                            // remainder = "{taskName}-{uuid}", UUID is always 36 chars
                            String taskName = remainder.length() > 37
                                    ? remainder.substring(0, remainder.length() - 37)
                                    : remainder;
                            byTaskName.computeIfAbsent(taskName, k -> new ArrayList<>()).add(childId);
                        }
                    } else {
                        // Remove child workflows that have reached a terminal state
                        String completedChildId = getTerminalChildWorkflowId(event);
                        if (completedChildId != null && completedChildId.startsWith(prefix)) {
                            String remainder = completedChildId.substring(prefix.length());
                            String taskName = remainder.length() > 37
                                    ? remainder.substring(0, remainder.length() - 37)
                                    : remainder;
                            List<String> ids = byTaskName.get(taskName);
                            if (ids != null) {
                                ids.remove(completedChildId);
                                if (ids.isEmpty()) {
                                    byTaskName.remove(taskName);
                                }
                            }
                        }
                    }
                }
                nextPageToken = resp.getNextPageToken();
            } while (!nextPageToken.isEmpty());

            RecordType groupType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "HumanTaskGroup").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(groupType));

            for (Map.Entry<String, List<String>> entry : byTaskName.entrySet()) {
                BMap<BString, Object> group = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "HumanTaskGroup");
                group.put(StringUtils.fromString("taskName"),
                        StringUtils.fromString(entry.getKey()));

                BArray ids = ValueCreator.createArrayValue(
                        TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
                for (String id : entry.getValue()) {
                    ids.append(StringUtils.fromString(id));
                }
                group.put(StringUtils.fromString("taskIds"), ids);
                result.append(group);
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list pending human tasks: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the child workflow ID from a terminal child-workflow history event, or returns
     * {@code null} if the event is not a terminal child-workflow event type.
     */
    private static String getTerminalChildWorkflowId(HistoryEvent event) {
        return switch (event.getEventType()) {
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED ->
                    event.getChildWorkflowExecutionCompletedEventAttributes()
                            .getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED ->
                    event.getChildWorkflowExecutionFailedEventAttributes()
                            .getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT ->
                    event.getChildWorkflowExecutionTimedOutEventAttributes()
                            .getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED ->
                    event.getChildWorkflowExecutionCanceledEventAttributes()
                            .getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED ->
                    event.getChildWorkflowExecutionTerminatedEventAttributes()
                            .getWorkflowExecution().getWorkflowId();
            default -> null;
        };
    }

    /**
     * Converts a {@link io.temporal.api.workflow.v1.WorkflowExecutionInfo} to a Ballerina
     * {@code HumanTaskSummary} record. Reads {@code taskName} and {@code parentWorkflowId}
     * from the execution's Temporal memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> toHumanTaskSummaryRecord(
            WorkflowClient client,
            io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo) {

        String wfId = wfInfo.getExecution().getWorkflowId();
        Map<String, io.temporal.api.common.v1.Payload> memoFields = wfInfo.getMemo().getFieldsMap();
        io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

        String taskName          = decodeMemoString(dc, memoFields, "taskName", "");
        String parentId          = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
        String parentWorkflowType = decodeMemoString(dc, memoFields, "parentWorkflowType", null);

        String[] userRolesArr = new String[0];
        try {
            io.temporal.api.common.v1.Payload rolesPl = memoFields.get("userRoles");
            if (rolesPl != null) {
                userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not decode userRoles from summary memo: {}", e.getMessage());
        }

        BMap<BString, Object> record = ValueCreator.createRecordValue(
                ModuleUtils.getManagementModule(), "HumanTaskSummary");
        record.put(StringUtils.fromString("taskId"), StringUtils.fromString(wfId));
        record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
        record.put(StringUtils.fromString("parentWorkflowType"),
                parentWorkflowType != null ? StringUtils.fromString(parentWorkflowType) : null);
        record.put(StringUtils.fromString("status"),
                StringUtils.fromString(convertStatus(wfInfo.getStatus())));

        com.google.protobuf.Timestamp st = wfInfo.getStartTime();
        record.put(StringUtils.fromString("startTime"),
                StringUtils.fromString(Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

        com.google.protobuf.Timestamp ct = wfInfo.getCloseTime();
        if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
            record.put(StringUtils.fromString("closeTime"),
                    StringUtils.fromString(Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
        } else {
            record.put(StringUtils.fromString("closeTime"), null);
        }

        BArray roles = ValueCreator.createArrayValue(
                TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
        for (String role : userRolesArr) {
            roles.append(StringUtils.fromString(role));
        }
        record.put(StringUtils.fromString("userRoles"), roles);
        // canComplete defaults to false; the Ballerina service layer recomputes it per caller
        record.put(StringUtils.fromString("canComplete"), false);
        return record;
    }

    /**
     * Decodes a string-valued field from a Temporal memo map.
     * Returns {@code defaultValue} if the field is absent or decoding fails.
     */
    private static String decodeMemoString(
            io.temporal.common.converter.DataConverter dc,
            Map<String, io.temporal.api.common.v1.Payload> fields,
            String key,
            String defaultValue) {
        try {
            io.temporal.api.common.v1.Payload payload = fields.get(key);
            if (payload == null || payload.getData().isEmpty()) {
                return defaultValue;
            }
            return dc.fromPayload(payload, String.class, String.class);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Maps human task status names to Temporal execution status names for visibility queries.
     * PENDING maps to Running.
     */
    private static String toHumanTaskTemporalStatus(String status) {
        if ("PENDING".equalsIgnoreCase(status)) {
            return "Running";
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "RUNNING"    -> "Running";
            case "COMPLETED"  -> "Completed";
            case "FAILED"     -> "Failed";
            case "CANCELED"   -> "Canceled";
            case "TERMINATED" -> "Terminated";
            case "TIMED_OUT"  -> "TimedOut";
            default           -> status;
        };
    }

    /**
     * Maps a Temporal {@link WorkflowExecutionStatus} enum value to its Ballerina string name.
     */
    private static String convertStatus(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING          -> "RUNNING";
            case WORKFLOW_EXECUTION_STATUS_COMPLETED        -> "COMPLETED";
            case WORKFLOW_EXECUTION_STATUS_FAILED           -> "FAILED";
            case WORKFLOW_EXECUTION_STATUS_CANCELED         -> "CANCELED";
            case WORKFLOW_EXECUTION_STATUS_TERMINATED       -> "TERMINATED";
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> "CONTINUED_AS_NEW";
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT        -> "TIMED_OUT";
            default                                         -> "UNKNOWN";
        };
    }

    // -------------------------------------------------------------------------
    // completeHumanTask (management module entry point)
    // -------------------------------------------------------------------------

    /**
     * Completes a pending human task. Delegates to
     * {@link WorkflowNative#completeHumanTask(BString, Object, Object)}.
     *
     * @param taskWorkflowId the Temporal workflow ID of the human task child workflow
     * @param result         the value to return to the waiting workflow
     * @param callerRoles    optional caller roles for authorization enforcement
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object completeHumanTask(BString taskWorkflowId, Object result,
            Object callerRoles, Object userId) {
        return WorkflowNative.completeHumanTask(taskWorkflowId, result, callerRoles, userId);
    }

    // -------------------------------------------------------------------------
    // MANUAL RETRY TASKS
    // -------------------------------------------------------------------------

    /**
     * Sends a {@code "taskDecision"} signal to the retry task child workflow identified
     * by {@code taskWorkflowId}, resolving the manual retry with the supplied decision.
     *
     * @param taskWorkflowId the Temporal workflow ID of the retry task child workflow
     * @param decision       the {@code RetryDecision} BMap ({@code action} + optional {@code input})
     * @param callerRoles    optional caller roles for authorization enforcement
     * @return {@code null} on success, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object completeRetryTask(BString taskWorkflowId, BMap<BString, Object> decision,
            Object callerRoles, Object userId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            // Validate workflowKind and optionally enforce caller roles
            BArray callerRolesArray = (callerRoles instanceof BArray ba) ? ba : null;
            Object validationError = validateRetryTaskAndRoles(
                    client, taskWorkflowId.getValue(), callerRolesArray);
            if (validationError != null) {
                return validationError;
            }

            // Convert RetryDecision BMap → serializable Java map
            Map<String, Object> javaDecision = new java.util.LinkedHashMap<>();
            Object actionVal = decision.get(StringUtils.fromString("action"));
            javaDecision.put("action", actionVal != null ? actionVal.toString() : "fail");

            Object inputVal = decision.get(StringUtils.fromString("input"));
            if (inputVal != null) {
                javaDecision.put("input",
                        io.ballerina.lib.workflow.utils.TypesUtil.convertBallerinaToJavaType(inputVal));
            }
            // Embed audit fields so the history scan in getRetryTaskInfo can retrieve them
            javaDecision.put("decidedBy", userId instanceof BString bs ? bs.getValue() : "unknown");
            javaDecision.put("decidedAt", java.time.Instant.now().toString());

            boolean delivered = io.ballerina.lib.workflow.runtime.WorkflowRuntime.getInstance()
                    .sendSignalToWorkflow(taskWorkflowId.getValue(), "taskDecision", javaDecision);

            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to complete retry task: task '" + taskWorkflowId.getValue()
                                + "' was no longer running when signal was delivered"));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to complete retry task: " + e.getMessage()));
        }
    }

    /**
     * Validates that {@code taskWorkflowId} is a running RETRY_TASK workflow and optionally
     * checks that at least one of the caller's roles appears in the task's {@code userRoles}.
     *
     * @return {@code null} if all checks pass, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    private static Object validateRetryTaskAndRoles(WorkflowClient client, String taskWorkflowId,
            BArray callerRolesArray) {
        try {
            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest req =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                    .setWorkflowId(taskWorkflowId)
                                    .build())
                            .build();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse resp =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(req);

            io.temporal.api.workflow.v1.WorkflowExecutionInfo execInfo =
                    resp.getWorkflowExecutionInfo();

            WorkflowExecutionStatus execStatus = execInfo.getStatus();
            if (execStatus != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Retry task '" + taskWorkflowId + "' is not running (status="
                                + convertStatus(execStatus) + ")"));
            }

            Map<String, io.temporal.api.common.v1.Payload> memoFields =
                    execInfo.getMemo().getFieldsMap();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

            // workflowKind check
            String workflowKind = decodeMemoString(dc, memoFields, "workflowKind", null);
            if (!"RETRY_TASK".equals(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Invalid task: '" + taskWorkflowId
                                + "' is not a retry task workflow (workflowKind="
                                + workflowKind + ")"));
            }

            if (callerRolesArray == null) {
                return null;
            }

            java.util.Set<String> allowedRoles = new java.util.HashSet<>();
            try {
                io.temporal.api.common.v1.Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    String[] rolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                    allowedRoles.addAll(java.util.Arrays.asList(rolesArr));
                }
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to decode task roles for '" + taskWorkflowId + "': " + e.getMessage()));
            }

            if (allowedRoles.isEmpty()) {
                return null;
            }

            for (int i = 0; i < callerRolesArray.size(); i++) {
                if (allowedRoles.contains(callerRolesArray.get(i).toString())) {
                    return null;
                }
            }

            return ErrorCreator.createError(StringUtils.fromString(
                    "Unauthorized: caller does not have a required role to complete retry task '"
                            + taskWorkflowId + "'. Required one of: " + allowedRoles));

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to validate retry task '" + taskWorkflowId + "': " + e.getMessage()));
        }
    }

    /**
     * Scans the parent workflow's event history for child retry task workflows and
     * returns them as {@code RetryTaskSummary} records sorted alphabetically by task name.
     * <p>
     * Child workflow ID format: {@code retrytask-{parentId}-{taskName}-{uuid}}
     * where UUID is always 36 characters.
     *
     * @param parentWorkflowId the parent workflow ID
     * @return a Ballerina {@code RetryTaskSummary[]} or an error
     */
    public static Object listPendingRetryTasks(BString parentWorkflowId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String parentId = parentWorkflowId.getValue();
            String prefix = "retrytask-" + parentId + "-";

            java.util.TreeMap<String, List<String>> byTaskName = new java.util.TreeMap<>();
            com.google.protobuf.ByteString nextPageToken = com.google.protobuf.ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                .setWorkflowId(parentId)
                                .build())
                        .setNextPageToken(nextPageToken)
                        .build();

                GetWorkflowExecutionHistoryResponse resp = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(req);

                for (HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType()
                            == io.temporal.api.enums.v1.EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
                        String childId = event
                                .getStartChildWorkflowExecutionInitiatedEventAttributes()
                                .getWorkflowId();
                        if (childId.startsWith(prefix)) {
                            String remainder = childId.substring(prefix.length());
                            // remainder = "{taskName}-{uuid}", UUID is always 36 chars
                            String taskName = remainder.length() > 37
                                    ? remainder.substring(0, remainder.length() - 37)
                                    : remainder;
                            byTaskName.computeIfAbsent(taskName,
                                    k -> new ArrayList<>()).add(childId);
                        }
                    } else {
                        String completedChildId = getTerminalChildWorkflowId(event);
                        if (completedChildId != null && completedChildId.startsWith(prefix)) {
                            String remainder = completedChildId.substring(prefix.length());
                            String taskName = remainder.length() > 37
                                    ? remainder.substring(0, remainder.length() - 37)
                                    : remainder;
                            List<String> ids = byTaskName.get(taskName);
                            if (ids != null) {
                                ids.remove(completedChildId);
                                if (ids.isEmpty()) {
                                    byTaskName.remove(taskName);
                                }
                            }
                        }
                    }
                }
                nextPageToken = resp.getNextPageToken();
            } while (!nextPageToken.isEmpty());

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "RetryTaskSummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            for (Map.Entry<String, List<String>> entry : byTaskName.entrySet()) {
                for (String childId : entry.getValue()) {
                    result.append(buildRetryTaskSummaryFromId(client, childId, entry.getKey()));
                }
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list pending retry tasks: " + e.getMessage()));
        }
    }

    /**
     * Lists all manual retry task instances via Temporal's visibility API.
     * Filters executions whose workflow ID starts with {@code retrytask-}.
     *
     * @param status optional status filter
     * @return a Ballerina {@code RetryTaskSummary[]} or an error
     */
    public static Object listAllRetryTasks(Object status, Object startTimeFrom, Object startTimeTo,
            Object closeTimeFrom, Object closeTimeTo) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String statusFilter = status instanceof BString bs ? bs.getValue() : null;
            String temporalStatus = statusFilter != null
                    ? toHumanTaskTemporalStatus(statusFilter) : null;

            List<String> clauses = new ArrayList<>();
            if (temporalStatus != null) {
                clauses.add(String.format("ExecutionStatus = \"%s\"", temporalStatus));
            }
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");
            String query = String.join(" AND ", clauses);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "RetryTaskSummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            com.google.protobuf.ByteString pageToken = com.google.protobuf.ByteString.EMPTY;
            do {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(query)
                        .setPageSize(100)
                        .setNextPageToken(pageToken)
                        .build();

                ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .listWorkflowExecutions(request);

                for (io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo
                        : response.getExecutionsList()) {
                    String wfId = wfInfo.getExecution().getWorkflowId();
                    if (!wfId.startsWith("retrytask-")) {
                        continue;
                    }
                    result.append(toRetryTaskSummaryRecord(client, wfInfo));
                }

                pageToken = response.getNextPageToken();
            } while (!pageToken.isEmpty());

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list retry tasks: " + e.getMessage()));
        }
    }

    /**
     * Returns detailed info for a single retry task by reading its Temporal memo.
     *
     * @param taskId the child workflow ID of the retry task
     * @return a Ballerina {@code RetryTaskInfo} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getRetryTaskInfo(BString taskId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String taskIdStr = taskId.getValue();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                    .setWorkflowId(taskIdStr)
                                    .build())
                            .build();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse response =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            io.temporal.api.workflow.v1.WorkflowExecutionInfo execInfo =
                    response.getWorkflowExecutionInfo();
            Map<String, io.temporal.api.common.v1.Payload> memoFields =
                    execInfo.getMemo().getFieldsMap();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

            String activityName  = decodeMemoString(dc, memoFields, "activityName", "");
            String taskName      = decodeMemoString(dc, memoFields, "taskName", "");
            String parentId      = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
            String errorMessage  = decodeMemoString(dc, memoFields, "errorMessage", "");
            String createdAt     = decodeMemoString(dc, memoFields, "createdAt", "");

            String[] userRolesArr = new String[0];
            try {
                io.temporal.api.common.v1.Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode userRoles memo field: {}", e.getMessage());
            }

            Object activityArgsRaw = null;
            try {
                io.temporal.api.common.v1.Payload argsPl = memoFields.get("activityArgs");
                if (argsPl != null) {
                    activityArgsRaw = dc.fromPayload(argsPl, Object.class, Object.class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode activityArgs memo field: {}", e.getMessage());
            }

            String statusStr = convertStatus(execInfo.getStatus());
            com.google.protobuf.Timestamp st = execInfo.getStartTime();
            String startTime = Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString();
            String closeTime = null;
            com.google.protobuf.Timestamp ct = execInfo.getCloseTime();
            if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                closeTime = Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString();
            }

            BMap<BString, Object> record = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "RetryTaskInfo");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskIdStr));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
            record.put(StringUtils.fromString("activityName"), StringUtils.fromString(activityName));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
            record.put(StringUtils.fromString("status"), StringUtils.fromString(statusStr));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(startTime));
            record.put(StringUtils.fromString("closeTime"),
                    closeTime != null ? StringUtils.fromString(closeTime) : null);

            BArray roles = ValueCreator.createArrayValue(
                    TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
            for (String role : userRolesArr) {
                roles.append(StringUtils.fromString(role));
            }
            record.put(StringUtils.fromString("userRoles"), roles);
            record.put(StringUtils.fromString("errorMessage"), StringUtils.fromString(errorMessage));

            Object bArgs = activityArgsRaw != null
                    ? io.ballerina.lib.workflow.utils.TypesUtil.convertJavaToBallerinaType(activityArgsRaw)
                    : null;
            record.put(StringUtils.fromString("activityArgs"), bArgs);
            record.put(StringUtils.fromString("createdAt"), StringUtils.fromString(createdAt));

            // Audit fields from the taskDecision signal stored in workflow history
            String decidedBy = readSignalField(client, taskIdStr, "taskDecision", "decidedBy");
            String decidedAt = readSignalField(client, taskIdStr, "taskDecision", "decidedAt");
            record.put(StringUtils.fromString("decidedBy"),
                    decidedBy != null ? StringUtils.fromString(decidedBy) : null);
            record.put(StringUtils.fromString("decidedAt"),
                    decidedAt != null ? StringUtils.fromString(decidedAt) : null);

            return record;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get retry task info: " + e.getMessage()));
        }
    }

    /**
     * Builds a minimal {@code RetryTaskSummary} record from a known task ID by calling
     * {@code DescribeWorkflowExecution} to read status and timestamps. Reads {@code taskName}
     * and {@code activityName} from memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> buildRetryTaskSummaryFromId(
            WorkflowClient client, String taskId, String fallbackTaskName) {
        try {
            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse resp =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(
                                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                                            .setNamespace(client.getOptions().getNamespace())
                                            .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                                    .setWorkflowId(taskId).build())
                                            .build());
            return toRetryTaskSummaryRecord(client, resp.getWorkflowExecutionInfo());
        } catch (Exception e) {
            // Fallback: minimal record with the info we already have
            BMap<BString, Object> record = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "RetryTaskSummary");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskId));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(fallbackTaskName));
            record.put(StringUtils.fromString("activityName"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("status"), StringUtils.fromString("UNKNOWN"));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("closeTime"), null);
            return record;
        }
    }

    /**
     * Converts a {@link io.temporal.api.workflow.v1.WorkflowExecutionInfo} to a Ballerina
     * {@code RetryTaskSummary} record. Reads {@code taskName}, {@code activityName}, and
     * {@code parentWorkflowId} from the execution's Temporal memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> toRetryTaskSummaryRecord(
            WorkflowClient client,
            io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo) {

        String wfId = wfInfo.getExecution().getWorkflowId();
        Map<String, io.temporal.api.common.v1.Payload> memoFields = wfInfo.getMemo().getFieldsMap();
        io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

        String taskName     = decodeMemoString(dc, memoFields, "taskName", "");
        String activityName = decodeMemoString(dc, memoFields, "activityName", "");
        String parentId     = decodeMemoString(dc, memoFields, "parentWorkflowId", "");

        BMap<BString, Object> record = ValueCreator.createRecordValue(
                ModuleUtils.getManagementModule(), "RetryTaskSummary");
        record.put(StringUtils.fromString("taskId"), StringUtils.fromString(wfId));
        record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        record.put(StringUtils.fromString("activityName"), StringUtils.fromString(activityName));
        record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
        record.put(StringUtils.fromString("status"),
                StringUtils.fromString(convertStatus(wfInfo.getStatus())));

        com.google.protobuf.Timestamp st = wfInfo.getStartTime();
        record.put(StringUtils.fromString("startTime"),
                StringUtils.fromString(Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

        com.google.protobuf.Timestamp ct = wfInfo.getCloseTime();
        if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
            record.put(StringUtils.fromString("closeTime"),
                    StringUtils.fromString(Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
        } else {
            record.put(StringUtils.fromString("closeTime"), null);
        }
        return record;
    }

    // -------------------------------------------------------------------------
    // WORKFLOW LIFECYCLE — TERMINATE AND CANCEL
    // -------------------------------------------------------------------------

    /**
     * Terminates a running workflow immediately with an optional reason.
     *
     * @param workflowId the workflow ID to terminate
     * @param runId      the specific run ID (empty string → latest run)
     * @param reason     optional reason (BString or nil)
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object terminateWorkflow(BString workflowId, BString runId, Object reason) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String wfId = workflowId.getValue();
            String rid = runId.getValue().isEmpty() ? null : runId.getValue();
            String reasonStr = reason instanceof BString bs
                    ? bs.getValue()
                    : "Terminated via management API";
            WorkflowStub stub = rid != null
                    ? client.newUntypedWorkflowStub(wfId,
                            java.util.Optional.of(rid), java.util.Optional.empty())
                    : client.newUntypedWorkflowStub(wfId);
            stub.terminate(reasonStr);
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to terminate workflow: " + e.getMessage()));
        }
    }

    /**
     * Requests graceful cancellation of a running workflow.
     *
     * @param workflowId the workflow ID to cancel
     * @param runId      the specific run ID (empty string → latest run)
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object cancelWorkflow(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String wfId = workflowId.getValue();
            String rid = runId.getValue().isEmpty() ? null : runId.getValue();
            WorkflowStub stub = rid != null
                    ? client.newUntypedWorkflowStub(wfId,
                            java.util.Optional.of(rid), java.util.Optional.empty())
                    : client.newUntypedWorkflowStub(wfId);
            stub.cancel();
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to cancel workflow: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // WORKFLOW LISTING AND STARTING
    // -------------------------------------------------------------------------

    /**
     * Starts a new workflow instance by its registered type name.
     * Returns a {@code WorkflowHandle} record with {@code workflowId} and {@code runId}.
     *
     * @param workflowType    registered workflow type (function name)
     * @param input           workflow input (Ballerina value, may be null)
     * @param workflowIdParam optional explicit workflow ID (BString or nil)
     * @param timeoutSeconds  optional timeout in seconds (Long or nil)
     * @return a Ballerina {@code WorkflowHandle} record or an error
     */
    public static Object startWorkflowByType(BString workflowType, Object input,
            Object workflowIdParam, Object timeoutSeconds) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String taskQueue = WorkflowWorkerNative.getTaskQueue();
            if (taskQueue == null) {
                return ErrorCreator.createError(StringUtils.fromString("Task queue not configured"));
            }
            String type = workflowType.getValue();
            String wfId = workflowIdParam instanceof BString bs
                    ? bs.getValue()
                    : io.ballerina.lib.workflow.utils.CorrelationExtractor.generateWorkflowId();

            io.temporal.client.WorkflowOptions.Builder optBuilder =
                    io.temporal.client.WorkflowOptions.newBuilder()
                            .setWorkflowId(wfId)
                            .setTaskQueue(taskQueue);
            if (timeoutSeconds instanceof Long secs) {
                optBuilder.setWorkflowExecutionTimeout(java.time.Duration.ofSeconds(secs));
            }

            WorkflowStub stub = client.newUntypedWorkflowStub(type, optBuilder.build());
            Object javaInput = input != null
                    ? io.ballerina.lib.workflow.utils.TypesUtil.convertBallerinaToJavaType(input)
                    : null;
            io.temporal.api.common.v1.WorkflowExecution execution = stub.start(javaInput);

            BMap<BString, Object> handle = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "WorkflowHandle");
            handle.put(StringUtils.fromString("workflowId"),
                    StringUtils.fromString(execution.getWorkflowId()));
            handle.put(StringUtils.fromString("runId"),
                    StringUtils.fromString(execution.getRunId()));
            return handle;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to start workflow: " + e.getMessage()));
        }
    }

    /**
     * Lists workflow instances via Temporal's visibility API with optional filters and pagination.
     * Automatically excludes humantask- and retrytask- child workflows.
     *
     * @param status       optional status filter BString (RUNNING, COMPLETED, FAILED, …)
     * @param workflowType optional workflow type filter BString
     * @param workflowId   optional workflow ID prefix filter BString
     * @param limit        maximum results per page
     * @param pageToken    opaque Base64-encoded continuation token BString, or null
     * @return a Ballerina {@code WorkflowInstancePage} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object listWorkflowInstances(Object status, Object workflowType,
            Object workflowId, long limit, Object pageToken,
            Object startTimeFrom, Object startTimeTo, Object closeTimeFrom, Object closeTimeTo) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            // Build Temporal visibility query — exclude built-in child workflow types
            List<String> clauses = new ArrayList<>();
            clauses.add("NOT WorkflowId STARTS_WITH 'humantask-'");
            clauses.add("NOT WorkflowId STARTS_WITH 'retrytask-'");

            if (status instanceof BString bs) {
                String ts = toWorkflowTemporalStatus(bs.getValue());
                if (ts != null) {
                    clauses.add(String.format("ExecutionStatus = \"%s\"", ts));
                }
            }
            if (workflowType instanceof BString wt) {
                String safeWt = wt.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
                clauses.add(String.format("WorkflowType = \"%s\"", safeWt));
            }
            if (workflowId instanceof BString wi) {
                String safeId = wi.getValue().replace("\\", "\\\\").replace("'", "\\'");
                clauses.add(String.format("WorkflowId STARTS_WITH '%s'", safeId));
            }
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");

            String query = String.join(" AND ", clauses);
            int pageSize = (int) Math.min(limit, 100);

            com.google.protobuf.ByteString nextPageTokenBytes = com.google.protobuf.ByteString.EMPTY;
            if (pageToken instanceof BString pt && !pt.getValue().isEmpty()) {
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(pt.getValue());
                    nextPageTokenBytes = com.google.protobuf.ByteString.copyFrom(decoded);
                } catch (IllegalArgumentException ignored) {
                    // Invalid token — start from beginning
                }
            }

            ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setQuery(query)
                    .setPageSize(pageSize)
                    .setNextPageToken(nextPageTokenBytes)
                    .build();

            ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                    .blockingStub()
                    .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .listWorkflowExecutions(request);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "WorkflowInstanceSummary").getType();
            BArray items = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            for (io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo
                    : response.getExecutionsList()) {
                BMap<BString, Object> summary = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "WorkflowInstanceSummary");
                summary.put(StringUtils.fromString("workflowId"),
                        StringUtils.fromString(wfInfo.getExecution().getWorkflowId()));
                summary.put(StringUtils.fromString("runId"),
                        StringUtils.fromString(wfInfo.getExecution().getRunId()));
                summary.put(StringUtils.fromString("workflowType"),
                        StringUtils.fromString(wfInfo.getType().getName()));
                summary.put(StringUtils.fromString("status"),
                        StringUtils.fromString(convertStatus(wfInfo.getStatus())));

                com.google.protobuf.Timestamp st = wfInfo.getStartTime();
                summary.put(StringUtils.fromString("startTime"),
                        StringUtils.fromString(
                                Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

                com.google.protobuf.Timestamp ct = wfInfo.getCloseTime();
                if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                    summary.put(StringUtils.fromString("closeTime"),
                            StringUtils.fromString(
                                    Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
                } else {
                    summary.put(StringUtils.fromString("closeTime"), null);
                }
                summary.put(StringUtils.fromString("input"), null);
                items.append(summary);
            }

            com.google.protobuf.ByteString nextToken = response.getNextPageToken();
            boolean hasMore = !nextToken.isEmpty();
            String nextTokenStr = hasMore
                    ? java.util.Base64.getEncoder().encodeToString(nextToken.toByteArray())
                    : null;

            BMap<BString, Object> page = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "WorkflowInstancePage");
            page.put(StringUtils.fromString("items"), items);
            page.put(StringUtils.fromString("nextPageToken"),
                    nextTokenStr != null ? StringUtils.fromString(nextTokenStr) : null);
            page.put(StringUtils.fromString("hasMore"), hasMore);
            return page;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list workflow instances: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // HISTORY SCAN HELPERS — read audit data from signal events
    // -------------------------------------------------------------------------

    /**
     * Scans the execution history of {@code workflowId} for a {@code WorkflowExecutionSignaled}
     * event with the given {@code signalName} and returns the String value of {@code fieldKey}
     * from the signal payload map. Returns {@code null} if not found or on any error.
     */
    @SuppressWarnings("unchecked")
    static String readSignalField(WorkflowClient client, String workflowId,
            String signalName, String fieldKey) {
        try {
            Object raw = readSignalPayloadField(client, workflowId, signalName, fieldKey);
            return raw instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scans the execution history of {@code workflowId} for a {@code WorkflowExecutionSignaled}
     * event with the given {@code signalName} and returns the raw value of {@code fieldKey}
     * from the decoded signal payload map. Returns {@code null} if not found or on any error.
     */
    @SuppressWarnings("unchecked")
    static Object readSignalPayloadField(WorkflowClient client, String workflowId,
            String signalName, String fieldKey) {
        try {
            io.temporal.api.common.v1.WorkflowExecution execution =
                    io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .build();
            String namespace = client.getOptions().getNamespace();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();
            com.google.protobuf.ByteString pageToken = com.google.protobuf.ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest.newBuilder()
                        .setNamespace(namespace)
                        .setExecution(execution)
                        .setNextPageToken(pageToken)
                        .build();

                GetWorkflowExecutionHistoryResponse resp = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(req);

                for (io.temporal.api.history.v1.HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType()
                            != io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) {
                        continue;
                    }
                    io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes attrs =
                            event.getWorkflowExecutionSignaledEventAttributes();
                    if (!signalName.equals(attrs.getSignalName())) {
                        continue;
                    }
                    // Decode the first payload in the signal input
                    io.temporal.api.common.v1.Payloads payloads = attrs.getInput();
                    if (payloads.getPayloadsCount() == 0) {
                        continue;
                    }
                    Object decoded = dc.fromPayload(
                            payloads.getPayloads(0), Object.class, Object.class);
                    if (decoded instanceof java.util.Map<?, ?> m) {
                        return ((java.util.Map<String, Object>) m).get(fieldKey);
                    }
                }

                pageToken = resp.getNextPageToken();
            } while (!pageToken.isEmpty());

        } catch (Exception e) {
            LOGGER.debug("readSignalPayloadField failed for {}/{}/{}: {}",
                    workflowId, signalName, fieldKey, e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // EXECUTION VISUALIZATION — Phase 3
    // =========================================================================

    /**
     * Returns all execution history events for a workflow run in chronological order.
     * Each event includes event-type-specific attributes serialized as a Ballerina {@code map<json>}.
     *
     * @param workflowId the workflow instance ID
     * @param runId      the run ID, or empty string for the latest run
     * @return a Ballerina {@code HistoryEvent[]} or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getWorkflowHistory(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();
            String rid  = runId.getValue().isEmpty() ? null : runId.getValue();

            List<HistoryEvent> events = fetchFullHistory(client, wfId, rid);

            RecordType historyEventType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "HistoryEvent").getType();
            BArray result = ValueCreator.createArrayValue(
                    TypeCreator.createArrayType(historyEventType));

            com.google.protobuf.util.JsonFormat.Printer printer =
                    com.google.protobuf.util.JsonFormat.printer()
                            .omittingInsignificantWhitespace();
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            for (HistoryEvent event : events) {
                BMap<BString, Object> record = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "HistoryEvent");

                record.put(StringUtils.fromString("eventId"), event.getEventId());
                record.put(StringUtils.fromString("eventType"),
                        StringUtils.fromString(simplifyEventType(event.getEventType())));

                com.google.protobuf.Timestamp ts = event.getEventTime();
                record.put(StringUtils.fromString("timestamp"),
                        StringUtils.fromString(
                                Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString()));

                // Serialize event to JSON, extract the *EventAttributes sub-object
                Map<String, Object> attrMap = new java.util.LinkedHashMap<>();
                try {
                    String json = printer.print(event);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> eventMap = mapper.readValue(json, Map.class);
                    for (Map.Entry<String, Object> entry : eventMap.entrySet()) {
                        if (entry.getKey().endsWith("EventAttributes")
                                && entry.getValue() instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typedMap = (Map<String, Object>) m;
                            attrMap.putAll(typedMap);
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to serialize history event {}: {}", event.getEventId(),
                            e.getMessage());
                }
                record.put(StringUtils.fromString("attributes"),
                        io.ballerina.lib.workflow.utils.TypesUtil.convertJavaToBallerinaType(attrMap));
                result.append(record);
            }
            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow history: " + e.getMessage()));
        }
    }

    /**
     * Parses the workflow execution history and returns a flat ordered list of
     * {@code ActivityTreeNode} records representing activities, child workflows,
     * timers, and user-visible signals.
     *
     * @param workflowId the workflow instance ID
     * @param runId      the run ID, or empty string for the latest run
     * @return a Ballerina {@code ActivityTreeNode[]} or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getActivityTree(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String wfId = workflowId.getValue();
            String rid  = runId.getValue().isEmpty() ? null : runId.getValue();
            io.temporal.common.converter.DataConverter dc = client.getOptions().getDataConverter();

            List<HistoryEvent> events = fetchFullHistory(client, wfId, rid);

            // eventId → mutable node data; insertion order preserved
            java.util.LinkedHashMap<Long, java.util.LinkedHashMap<String, Object>> nodeByEventId =
                    new java.util.LinkedHashMap<>();
            List<Long> nodeOrder = new ArrayList<>();

            for (HistoryEvent event : events) {
                long eid = event.getEventId();
                String ts = Instant.ofEpochSecond(
                        event.getEventTime().getSeconds(),
                        event.getEventTime().getNanos()).toString();

                switch (event.getEventType()) {

                    case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                        var attrs = event.getActivityTaskScheduledEventAttributes();
                        var node = newNode(eid, attrs.getActivityType().getName(), "ACTIVITY", ts);
                        node.put("input", decodeFirstPayload(attrs.getInput(), dc));
                        nodeByEventId.put(eid, node);
                        nodeOrder.add(eid);
                    }

                    case EVENT_TYPE_ACTIVITY_TASK_STARTED -> {
                        var attrs = event.getActivityTaskStartedEventAttributes();
                        var node = nodeByEventId.get(attrs.getScheduledEventId());
                        if (node != null) {
                            node.put("attempt", attrs.getAttempt());
                            node.put("startTime", ts);
                        }
                    }

                    case EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> {
                        var attrs = event.getActivityTaskCompletedEventAttributes();
                        var node = nodeByEventId.get(attrs.getScheduledEventId());
                        if (node != null) {
                            node.put("status", "COMPLETED");
                            node.put("endTime", ts);
                            node.put("output", decodeFirstPayload(attrs.getResult(), dc));
                        }
                    }

                    case EVENT_TYPE_ACTIVITY_TASK_FAILED -> {
                        var attrs = event.getActivityTaskFailedEventAttributes();
                        var node = nodeByEventId.get(attrs.getScheduledEventId());
                        if (node != null) {
                            node.put("status", "FAILED");
                            node.put("endTime", ts);
                            if (attrs.hasFailure()) {
                                node.put("failureMessage", attrs.getFailure().getMessage());
                                node.put("failureType",
                                        attrs.getFailure().getApplicationFailureInfo().getType());
                                if (attrs.getFailure().hasCause()) {
                                    node.put("failureCause",
                                            attrs.getFailure().getCause().getMessage());
                                }
                            }
                        }
                    }

                    case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> {
                        var attrs = event.getActivityTaskTimedOutEventAttributes();
                        var node = nodeByEventId.get(attrs.getScheduledEventId());
                        if (node != null) {
                            node.put("status", "TIMED_OUT");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_ACTIVITY_TASK_CANCELED -> {
                        var attrs = event.getActivityTaskCanceledEventAttributes();
                        var node = nodeByEventId.get(attrs.getScheduledEventId());
                        if (node != null) {
                            node.put("status", "CANCELED");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> {
                        var attrs = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
                        String childId   = attrs.getWorkflowId();
                        String childType = attrs.getWorkflowType().getName();
                        String nodeType  = childNodeType(childId);
                        String nodeName  = shortTaskName(childType, childId);
                        var node = newNode(eid, nodeName, nodeType, ts);
                        node.put("childWorkflowId", childId);
                        node.put("input", decodeFirstPayload(attrs.getInput(), dc));
                        nodeByEventId.put(eid, node);
                        nodeOrder.add(eid);
                    }

                    case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED -> {
                        var attrs = event.getChildWorkflowExecutionStartedEventAttributes();
                        var node = nodeByEventId.get(attrs.getInitiatedEventId());
                        if (node != null) {
                            node.put("startTime", ts);
                        }
                    }

                    case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED -> {
                        var attrs = event.getChildWorkflowExecutionCompletedEventAttributes();
                        var node = nodeByEventId.get(attrs.getInitiatedEventId());
                        if (node != null) {
                            node.put("status", "COMPLETED");
                            node.put("endTime", ts);
                            node.put("output", decodeFirstPayload(attrs.getResult(), dc));
                        }
                    }

                    case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                        var attrs = event.getChildWorkflowExecutionFailedEventAttributes();
                        var node = nodeByEventId.get(attrs.getInitiatedEventId());
                        if (node != null) {
                            node.put("status", "FAILED");
                            node.put("endTime", ts);
                            if (attrs.hasFailure()) {
                                node.put("failureMessage", attrs.getFailure().getMessage());
                            }
                        }
                    }

                    case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT -> {
                        var attrs = event.getChildWorkflowExecutionTimedOutEventAttributes();
                        var node = nodeByEventId.get(attrs.getInitiatedEventId());
                        if (node != null) {
                            node.put("status", "TIMED_OUT");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED -> {
                        var attrs = event.getChildWorkflowExecutionCanceledEventAttributes();
                        var node = nodeByEventId.get(attrs.getInitiatedEventId());
                        if (node != null) {
                            node.put("status", "CANCELED");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_TIMER_STARTED -> {
                        var node = newNode(eid, "sleep", "TIMER", ts);
                        nodeByEventId.put(eid, node);
                        nodeOrder.add(eid);
                    }

                    case EVENT_TYPE_TIMER_FIRED -> {
                        var attrs = event.getTimerFiredEventAttributes();
                        var node = nodeByEventId.get(attrs.getStartedEventId());
                        if (node != null) {
                            node.put("status", "COMPLETED");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_TIMER_CANCELED -> {
                        var attrs = event.getTimerCanceledEventAttributes();
                        var node = nodeByEventId.get(attrs.getStartedEventId());
                        if (node != null) {
                            node.put("status", "CANCELED");
                            node.put("endTime", ts);
                        }
                    }

                    case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> {
                        var attrs = event.getWorkflowExecutionSignaledEventAttributes();
                        String sigName = attrs.getSignalName();
                        if (!isInternalSignal(sigName)) {
                            var node = newNode(eid, sigName, "SIGNAL", ts);
                            node.put("status", "COMPLETED");
                            node.put("endTime", ts);
                            nodeByEventId.put(eid, node);
                            nodeOrder.add(eid);
                        }
                    }

                    default -> { /* ignore workflow-level events */ }
                }
            }

            // Convert node data maps → Ballerina ActivityTreeNode records
            RecordType nodeType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "ActivityTreeNode").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(nodeType));
            for (long eid : nodeOrder) {
                var data = nodeByEventId.get(eid);
                if (data != null) {
                    result.append(buildTreeNode(data));
                }
            }
            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get activity tree: " + e.getMessage()));
        }
    }

    /**
     * Derives a directed execution graph from the workflow history.
     * Nodes represent execution steps; sequential edges connect them in order.
     *
     * @param workflowId the workflow instance ID
     * @param runId      the run ID, or empty string for the latest run
     * @return a Ballerina {@code ExecutionGraph} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getExecutionGraph(BString workflowId, BString runId) {
        try {
            // Reuse activity tree
            Object treeResult = getActivityTree(workflowId, runId);
            if (treeResult instanceof io.ballerina.runtime.api.values.BError) {
                return treeResult;
            }
            BArray treeNodes = (BArray) treeResult;

            RecordType graphNodeType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "GraphNode").getType();
            RecordType graphEdgeType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "GraphEdge").getType();
            BArray nodes = ValueCreator.createArrayValue(TypeCreator.createArrayType(graphNodeType));
            BArray edges = ValueCreator.createArrayValue(TypeCreator.createArrayType(graphEdgeType));

            // Add all tree nodes as graph nodes; build sequential edges
            String prevId = null;
            for (int i = 0; i < treeNodes.size(); i++) {
                @SuppressWarnings("unchecked")
                BMap<BString, Object> treeNode = (BMap<BString, Object>) treeNodes.get(i);

                String id     = ((BString) treeNode.get(StringUtils.fromString("id"))).getValue();
                String name   = ((BString) treeNode.get(StringUtils.fromString("name"))).getValue();
                String type   = ((BString) treeNode.get(StringUtils.fromString("type"))).getValue();
                String status = ((BString) treeNode.get(StringUtils.fromString("status"))).getValue();

                // Metadata: include childWorkflowId for human/retry tasks
                BMap<BString, Object> metadata = null;
                Object cwf = treeNode.get(StringUtils.fromString("childWorkflowId"));
                if (cwf instanceof BString cws) {
                    metadata = ValueCreator.createMapValue();
                    metadata.put(StringUtils.fromString("taskId"), cws);
                }

                BMap<BString, Object> gn = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "GraphNode");
                gn.put(StringUtils.fromString("id"), StringUtils.fromString(id));
                gn.put(StringUtils.fromString("label"), StringUtils.fromString(name));
                gn.put(StringUtils.fromString("type"), StringUtils.fromString(type));
                gn.put(StringUtils.fromString("status"), StringUtils.fromString(status));
                gn.put(StringUtils.fromString("metadata"), metadata);
                nodes.append(gn);

                if (prevId != null) {
                    BMap<BString, Object> edge = ValueCreator.createRecordValue(
                            ModuleUtils.getManagementModule(), "GraphEdge");
                    edge.put(StringUtils.fromString("source"), StringUtils.fromString(prevId));
                    edge.put(StringUtils.fromString("target"), StringUtils.fromString(id));
                    edge.put(StringUtils.fromString("label"), null);
                    edges.append(edge);
                }
                prevId = id;
            }

            BMap<BString, Object> graph = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "ExecutionGraph");
            graph.put(StringUtils.fromString("nodes"), nodes);
            graph.put(StringUtils.fromString("edges"), edges);
            return graph;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get execution graph: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // VISUALIZATION HELPERS
    // -------------------------------------------------------------------------

    /**
     * Fetches all history pages for a workflow run and returns them as a flat list.
     * Hard cap at 2000 events to prevent unbounded memory use.
     */
    private static List<HistoryEvent> fetchFullHistory(WorkflowClient client,
            String workflowId, String runId) throws Exception {
        List<HistoryEvent> events = new ArrayList<>();
        com.google.protobuf.ByteString pageToken = com.google.protobuf.ByteString.EMPTY;

        io.temporal.api.common.v1.WorkflowExecution.Builder execBuilder =
                io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                        .setWorkflowId(workflowId);
        if (runId != null) {
            execBuilder.setRunId(runId);
        }

        do {
            GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(execBuilder.build())
                    .setNextPageToken(pageToken)
                    .setMaximumPageSize(500)
                    .build();
            GetWorkflowExecutionHistoryResponse resp = client.getWorkflowServiceStubs()
                    .blockingStub()
                    .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .getWorkflowExecutionHistory(req);
            events.addAll(resp.getHistory().getEventsList());
            pageToken = resp.getNextPageToken();
            if (events.size() >= 2000) {
                throw new Exception("History for workflow '" + workflowId
                        + "' exceeds 2000 events and cannot be loaded in full");
            }
        } while (!pageToken.isEmpty());

        return events;
    }

    /** Strips the {@code EVENT_TYPE_} prefix for a cleaner event type string. */
    private static String simplifyEventType(EventType type) {
        String name = type.name();
        return name.startsWith("EVENT_TYPE_") ? name.substring("EVENT_TYPE_".length()) : name;
    }

    /** Creates a fresh mutable node data map with the fields common to all node types. */
    private static java.util.LinkedHashMap<String, Object> newNode(
            long scheduledEventId, String name, String type, String startTime) {
        java.util.LinkedHashMap<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", String.valueOf(scheduledEventId));
        node.put("name", name);
        node.put("type", type);
        node.put("status", "RUNNING");
        node.put("startTime", startTime);
        node.put("attempt", 1);
        return node;
    }

    /**
     * Determines the {@code ActivityNodeType} for a child workflow based on its ID prefix.
     */
    private static String childNodeType(String workflowId) {
        if (workflowId.startsWith("humantask-")) {
            return "HUMAN_TASK";
        }
        if (workflowId.startsWith("retrytask-")) {
            return "RETRY_TASK";
        }
        return "CHILD_WORKFLOW";
    }

    /**
     * Extracts a short human-readable task name from the workflow type (qualified name)
     * for human/retry task nodes. Falls back to the full type name for other child workflows.
     */
    private static String shortTaskName(String workflowType, String workflowId) {
        // Human tasks: workflowType = "workflowDefinition.taskName" → return "taskName"
        if (workflowId.startsWith("humantask-")) {
            int dot = workflowType.lastIndexOf('.');
            if (dot >= 0) {
                return workflowType.substring(dot + 1);
            } else {
                return workflowType;
            }
        }
        // Retry tasks: internal type is __workflow_retry_task__ → extract from ID
        if (workflowId.startsWith("retrytask-")) {
            // Format: retrytask-{parentId}-{workflowType}.{taskName}-{uuid}
            // UUID is 36 chars, preceded by '-'
            String rest = workflowId;
            if (rest.length() > 37) {
                rest = rest.substring(0, rest.length() - 37); // strip "-{uuid}"
                int lastDot = rest.lastIndexOf('.');
                if (lastDot >= 0) {
                    return rest.substring(lastDot + 1);
                }
            }
        }
        return workflowType;
    }

    /**
     * Returns {@code true} for Temporal-internal or framework-level signals that should
     * not appear as user-visible nodes in the activity tree.
     */
    private static boolean isInternalSignal(String signalName) {
        return signalName.startsWith("__wf_")
                || "taskCompletion".equals(signalName)
                || "taskDecision".equals(signalName);
    }

    /**
     * Decodes the first payload element from a {@code Payloads} envelope.
     * Returns {@code null} on any decoding failure.
     */
    private static Object decodeFirstPayload(io.temporal.api.common.v1.Payloads payloads,
            io.temporal.common.converter.DataConverter dc) {
        if (payloads == null || payloads.getPayloadsCount() == 0) {
            return null;
        }
        try {
            return dc.fromPayload(payloads.getPayloads(0), Object.class, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a mutable node data map into a Ballerina {@code ActivityTreeNode} record.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> buildTreeNode(
            java.util.LinkedHashMap<String, Object> data) {
        BMap<BString, Object> node = ValueCreator.createRecordValue(
                ModuleUtils.getManagementModule(), "ActivityTreeNode");

        node.put(StringUtils.fromString("id"),
                StringUtils.fromString((String) data.getOrDefault("id", "")));
        node.put(StringUtils.fromString("name"),
                StringUtils.fromString((String) data.getOrDefault("name", "")));
        node.put(StringUtils.fromString("type"),
                StringUtils.fromString((String) data.getOrDefault("type", "ACTIVITY")));
        node.put(StringUtils.fromString("status"),
                StringUtils.fromString((String) data.getOrDefault("status", "RUNNING")));

        String startTime = (String) data.get("startTime");
        node.put(StringUtils.fromString("startTime"),
                startTime != null ? StringUtils.fromString(startTime) : null);
        String endTime = (String) data.get("endTime");
        node.put(StringUtils.fromString("endTime"),
                endTime != null ? StringUtils.fromString(endTime) : null);

        Object input = data.get("input");
        node.put(StringUtils.fromString("input"),
                input != null ? io.ballerina.lib.workflow.utils.TypesUtil
                        .convertJavaToBallerinaType(input) : null);
        Object output = data.get("output");
        node.put(StringUtils.fromString("output"),
                output != null ? io.ballerina.lib.workflow.utils.TypesUtil
                        .convertJavaToBallerinaType(output) : null);

        // Failure
        String failMsg = (String) data.get("failureMessage");
        if (failMsg != null) {
            BMap<BString, Object> failure = ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "FailureInfo");
            failure.put(StringUtils.fromString("message"), StringUtils.fromString(failMsg));
            String failType = (String) data.get("failureType");
            failure.put(StringUtils.fromString("type"),
                    failType != null ? StringUtils.fromString(failType) : null);
            String failCause = (String) data.get("failureCause");
            failure.put(StringUtils.fromString("cause"),
                    failCause != null ? StringUtils.fromString(failCause) : null);
            node.put(StringUtils.fromString("failure"), failure);
        } else {
            node.put(StringUtils.fromString("failure"), null);
        }

        int attempt = data.get("attempt") instanceof Integer i ? i : 1;
        node.put(StringUtils.fromString("attempt"), (long) attempt);
        node.put(StringUtils.fromString("children"), null);
        return node;
    }

    /**
     * Maps a Ballerina workflow status string to a Temporal visibility execution status string.
     */
    /**
     * Appends a time-range clause to {@code clauses} when {@code param} is a non-empty BString.
     * Produces: {@code <field> <op> "<iso8601>"}  (e.g. {@code StartTime >= "2026-06-01T00:00:00Z"}).
     * The value is stripped of any embedded double-quotes to prevent query injection.
     */
    private static void addTimeClause(List<String> clauses, Object param, String field, String op) {
        if (param instanceof BString bs && !bs.getValue().isBlank()) {
            String value = bs.getValue().replace("\\", "\\\\").replace("\"", "");
            clauses.add(String.format("%s %s \"%s\"", field, op, value));
        }
    }

    private static String toWorkflowTemporalStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "RUNNING"    -> "Running";
            case "COMPLETED"  -> "Completed";
            case "FAILED"     -> "Failed";
            case "CANCELED"   -> "Canceled";
            case "TERMINATED" -> "Terminated";
            case "TIMED_OUT"  -> "TimedOut";
            default           -> null;
        };
    }
}
