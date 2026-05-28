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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public static Object listAllHumanTasks(Object status) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String statusFilter = status instanceof BString bs ? bs.getValue() : null;
            // PENDING maps to Running in Temporal status
            String temporalStatus = statusFilter != null ? toHumanTaskTemporalStatus(statusFilter) : null;
            String query = temporalStatus != null
                    ? String.format("ExecutionStatus = \"%s\"", temporalStatus) : "";

            ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setQuery(query)
                    .setPageSize(100)
                    .build();

            ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                    .blockingStub()
                    .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .listWorkflowExecutions(request);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "HumanTaskSummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            for (io.temporal.api.workflow.v1.WorkflowExecutionInfo wfInfo : response.getExecutionsList()) {
                String wfId = wfInfo.getExecution().getWorkflowId();
                if (!wfId.startsWith("humantask-")) {
                    continue;
                }
                result.append(toHumanTaskSummaryRecord(client, wfInfo));
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list human tasks: " + e.getMessage()));
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

        String taskName  = decodeMemoString(dc, memoFields, "taskName", "");
        String parentId  = decodeMemoString(dc, memoFields, "parentWorkflowId", "");

        BMap<BString, Object> record = ValueCreator.createRecordValue(
                ModuleUtils.getManagementModule(), "HumanTaskSummary");
        record.put(StringUtils.fromString("taskId"), StringUtils.fromString(wfId));
        record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
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
}
