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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.ballerina.lib.workflow.ModuleUtils;
import io.ballerina.lib.workflow.runtime.WorkflowRuntime;
import io.ballerina.lib.workflow.utils.CorrelationExtractor;
import io.ballerina.lib.workflow.utils.EventExtractor;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.IntersectionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.ReferenceType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * Returns current execution info for a workflow without waiting for completion. Delegates to
     * {@link WorkflowNative#getWorkflowInfo(BString)}.
     *
     * @param workflowId the workflow ID
     * @return a Ballerina {@code WorkflowExecutionInfo} record or an error
     */
    public static Object getWorkflowInfo(BString workflowId) {
        return WorkflowNative.getWorkflowInfo(workflowId);
    }

    /**
     * Returns current execution info for a specific run of a workflow. Unlike {@link #getWorkflowInfo} which targets
     * the latest run, this method pins the Describe call to the exact runId supplied by the caller.
     *
     * @param workflowId the workflow ID
     * @param runId      the specific run ID
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

            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(WorkflowExecution
                                                                             .newBuilder()
                                                                             .setWorkflowId(wfId)
                                                                             .setRunId(wfRunId)
                                                                             .build()).build();

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

            return WorkflowNative.buildWorkflowExecutionInfo(wfId, workflowType, status, null, null, client);
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get workflow info: " + e.getMessage()));
        }
    }

    /**
     * Returns the latest response recorded by a durable agent, or {@code null} when none is available yet. Prefers
     * the {@code agentResponse} workflow memo upserted by the agent loop (authoritative from any
     * process in the cluster), falling back to the in-JVM {@code AgentResponseStore} when the memo
     * has no entry yet or no workflow client is available (e.g. direct test bindings).
     *
     * @param agentId the agent's workflow ID
     * @return the latest response text (BString), {@code null}, or a Ballerina error
     */
    public static Object getAgentResponse(BString agentId) {
        // The agentResponse memo is the authoritative, cluster-wide source: the agent's turns may
        // have run on another worker, making this JVM's AgentResponseStore entry stale. The in-JVM
        // store only answers when no workflow client is available (e.g. direct test bindings).
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                Object stored = io.ballerina.lib.workflow.context.AgentResponseStore.getFinalResponse(agentId);
                return stored != null ? stored
                        : ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            DescribeWorkflowExecutionRequest req = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(agentId.getValue()).build()).build();
            DescribeWorkflowExecutionResponse resp = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(
                    GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS).describeWorkflowExecution(req);
            Map<String, Payload> memoFields = resp.getWorkflowExecutionInfo().getMemo().getFieldsMap();
            DataConverter dc = client.getOptions().getDataConverter();
            Payload responsePayload = memoFields.get("agentResponse");
            if (responsePayload == null || responsePayload.getData().isEmpty()) {
                // No memo yet (e.g. the agent has not answered a turn): the in-JVM store may
                // still know a response recorded in this process.
                return io.ballerina.lib.workflow.context.AgentResponseStore.getFinalResponse(agentId);
            }
            // The memo exists, so it is authoritative — a decode failure is an error, never a
            // reason to fall back to worker-local data that may be stale on this worker.
            try {
                String response = dc.fromPayload(responsePayload, String.class, String.class);
                return response == null ? null : StringUtils.fromString(response);
            } catch (Exception e) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to decode the agentResponse memo for '" + agentId.getValue() + "': "
                                + e.getMessage()));
            }
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read agent response for '" + agentId.getValue() + "': " + e.getMessage()));
        }
    }

    /**
     * Lists registered workflow types, for use in the workflow launcher UI. Returns one entry per registered workflow
     * function. The {@code inputSchema} field is {@code null} until the compiler plugin generates JSON Schema at build
     * time.
     *
     * @return a Ballerina {@code WorkflowDefinition[]} or an error
     */
    public static Object listWorkflowDefinitions() {
        try {
            RecordType defType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                             "WorkflowDefinition").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(defType));

            for (String workflowType : WorkflowWorkerNative.getProcessRegistry().keySet()) {
                io.ballerina.lib.workflow.worker.WorkflowFunctionRef processFn =
                        WorkflowWorkerNative.getProcessRegistry().get(workflowType);
                String displayType = workflowType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX) ?
                                     workflowType.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) :
                                     workflowType;
                // Workflows and durable agents list as one set of startable definitions: an
                // agent's input schema comes from its declared inputType (the shared runner's
                // signature is an internal envelope), everything else is identical.
                boolean agentType = WorkflowWorkerNative.isAgentWorkflowType(workflowType);
                String inputSchema = agentType
                        ? DurableAgentNative.startInputSchema(displayType)
                        : deriveWorkflowInputSchema(processFn);

                BMap<BString, Object> def = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                           "WorkflowDefinition");
                def.put(StringUtils.fromString("workflowType"), StringUtils.fromString(displayType));
                def.put(StringUtils.fromString("kind"), StringUtils.fromString(agentType ? "AGENT" : "WORKFLOW"));
                def.put(StringUtils.fromString("inputSchema"),
                        inputSchema != null ? StringUtils.fromString(inputSchema) : null);
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
     * Builds a JSON schema for workflow input based on the registered workflow function signature. Skips Context and
     * events parameters and returns the schema for the actual data input parameters. Package-visible so
     * {@link WorkflowMetadataNative} can reuse the derivation for metadata publishing.
     */
    static String deriveWorkflowInputSchema(io.ballerina.lib.workflow.worker.WorkflowFunctionRef processFunction) {
        if (processFunction == null) {
            return TypesUtil.toJsonSchemaForParameters(new Parameter[0], 0, 0);
        }

        FunctionType functionType = resolveFunctionType(processFunction.getType(), 0);
        if (functionType == null) {
            return TypesUtil.toJsonSchemaForParameters(new Parameter[0], 0, 0);
        }

        Parameter[] parameters = functionType.getParameters();
        if (parameters == null || parameters.length == 0) {
            return TypesUtil.toJsonSchemaForParameters(new Parameter[0], 0, 0);
        }

        int startIndex = EventExtractor.hasContextParameter(processFunction.getType()) ? 1 : 0;
        int endExclusive = parameters.length;
        if (EventExtractor.getEventsRecordType(processFunction.getType()) != null && endExclusive > startIndex) {
            endExclusive--;
        }

        if (endExclusive <= startIndex) {
            return TypesUtil.toJsonSchemaForParameters(new Parameter[0], 0, 0);
        }

        // Common path: one data parameter (typically a record input).
        if (endExclusive - startIndex == 1) {
            return TypesUtil.toJsonSchema(parameters[startIndex].type);
        }

        // Multi-parameter workflows are represented as an object keyed by parameter names.
        return TypesUtil.toJsonSchemaForParameters(parameters, startIndex, endExclusive);
    }

    private static FunctionType resolveFunctionType(Type type, int depth) {
        if (type == null || depth > 12) {
            return null;
        }

        if (type instanceof FunctionType functionType) {
            return functionType;
        }

        if (type instanceof ReferenceType referenceType) {
            Type referred = referenceType.getReferredType();
            if (referred != type) {
                FunctionType resolved = resolveFunctionType(referred, depth + 1);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        if (type instanceof IntersectionType intersectionType) {
            for (Type constituent : intersectionType.getConstituentTypes()) {
                FunctionType resolved = resolveFunctionType(constituent, depth + 1);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        return null;
    }

    /**
     * Requests a running workflow to suspend by sending a {@code __wf_suspend} signal.
     *
     * @param workflowId the workflow ID to suspend
     * @return {@code null} on success, or a Ballerina error
     */
    /**
     * Wakes a durable agent instance out of its built-in sleep tool by sending the
     * {@code __agent_wake} signal. Harmless when the instance is not sleeping.
     *
     * @param workflowId the agent instance ID
     * @return null on success, or a BError when the instance is not found
     */
    public static Object wakeAgent(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(workflowId.getValue(),
                    io.ballerina.lib.workflow.worker.WorkflowWorkerNative.AGENT_WAKE_SIGNAL_NAME, null);
            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to wake agent: workflow not found: " + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to wake agent: " + e.getMessage()));
        }
    }

    public static Object suspendWorkflow(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(workflowId.getValue(),
                                                                                   "__wf_suspend", null);
            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to suspend workflow: workflow not found: " + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to suspend workflow: " + e.getMessage()));
        }
    }

    /**
     * Suspends a specific run of a workflow by sending a {@code __wf_suspend} signal to the exact (workflowId, runId)
     * pair, rather than the latest run.
     *
     * @param workflowId the workflow ID
     * @param runId      the specific run ID to suspend
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object suspendWorkflowRun(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            WorkflowExecution exec = WorkflowExecution.newBuilder().setWorkflowId(workflowId.getValue()).setRunId(
                    runId.getValue()).build();
            WorkflowStub stub = client.newUntypedWorkflowStub(exec, Optional.empty());
            stub.signal("__wf_suspend");
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to suspend workflow: " + e.getMessage()));
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
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(workflowId.getValue(), "__wf_resume",
                                                                                   null);
            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to resume workflow: workflow not found: " + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to resume workflow: " + e.getMessage()));
        }
    }

    /**
     * Resumes a specific run of a suspended workflow by sending a {@code __wf_resume} signal to the exact (workflowId,
     * runId) pair, rather than the latest run.
     *
     * @param workflowId the workflow ID
     * @param runId      the specific run ID to resume
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object resumeWorkflowRun(BString workflowId, BString runId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            WorkflowExecution exec = WorkflowExecution.newBuilder().setWorkflowId(workflowId.getValue()).setRunId(
                    runId.getValue()).build();
            WorkflowStub stub = client.newUntypedWorkflowStub(exec, Optional.empty());
            stub.signal("__wf_resume");
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to resume workflow: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // HUMAN TASKS
    // -------------------------------------------------------------------------

    /**
     * Lists all human task instances across all parent workflows via Temporal's visibility API. Filters executions
     * whose workflow ID starts with {@code humantask-}. Task name and parent workflow ID are extracted from the task's
     * Temporal memo.
     *
     * @param status optional status filter (Ballerina naming: PENDING maps to Running, etc.)
     * @return a Ballerina {@code HumanTaskSummary[]} or an error
     */
    public static Object listAllHumanTasks(Object status, Object startTimeFrom, Object startTimeTo,
                                           Object closeTimeFrom, Object closeTimeTo, Object taskQueue) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String statusFilter = status instanceof BString bs ? bs.getValue() : null;
            List<String> clauses = new ArrayList<>();
            // PENDING maps to Running in Temporal status; FAILED expands to Failed OR TimedOut.
            addTaskStatusClause(clauses, statusFilter);
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");
            addTaskQueueClause(clauses, taskQueue);
            // The type is the classifier, and it is queryable — filter server-side instead of
            // scanning the namespace and discarding.
            clauses.add("WorkflowType STARTS_WITH '" + WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX + "'");
            String query = String.join(" AND ", clauses);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                 "HumanTaskSummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            ByteString pageToken = ByteString.EMPTY;
            do {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(query)
                        .setPageSize(100)
                        .setNextPageToken(pageToken)
                        .build();

                ListWorkflowExecutionsResponse response =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                                .listWorkflowExecutions(request);

                for (WorkflowExecutionInfo wfInfo : response.getExecutionsList()) {
                    if (!isHumanTaskType(wfInfo.getType().getName())) {
                        continue;
                    }
                    result.append(toHumanTaskSummaryRecord(client, wfInfo));
                }

                pageToken = response.getNextPageToken();
            } while (!pageToken.isEmpty());

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to list human tasks: " + e.getMessage()));
        }
    }

    /**
     * Returns detailed info for a single human task by calling DescribeWorkflowExecution and reading the memo fields
     * set by {@code awaitHumanTask} at task creation.
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

            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(taskIdStr).build()).build();

            DescribeWorkflowExecutionResponse response =
                    client
                            .getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            Map<String, Payload> memoFields = execInfo.getMemo().getFieldsMap();

            DataConverter dc = client.getOptions().getDataConverter();

            // Only human task workflows may be served here — a review activity or user workflow
            // ID must not leak through this endpoint (ballerina-library#8894).
            String workflowKind = decodeMemoString(dc, memoFields, "workflowKind", null);
            if (!"HUMAN_TASK".equals(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Human task not found: '" + taskIdStr + "' is not a human task workflow"));
            }

            String taskName = decodeMemoString(dc, memoFields, "taskName", "");
            String parentId = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
            String title = decodeMemoString(dc, memoFields, "title", taskName);
            String description = decodeMemoString(dc, memoFields, "description", "");
            String createdAt = decodeMemoString(dc, memoFields, "createdAt", "");
            String formSchema = decodeMemoString(dc, memoFields, "formSchema", null);

            String[] userRolesArr = new String[0];
            try {
                Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode userRoles memo field: {}", e.getMessage());
            }

            Object payloadRaw = null;
            try {
                Payload payloadPl = memoFields.get("payload");
                if (payloadPl != null) {
                    payloadRaw = dc.fromPayload(payloadPl, Object.class, Object.class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode payload memo field: {}", e.getMessage());
            }

            // Status and timestamps from visibility info
            String statusStr = taskStatusFromTemporal(execInfo.getStatus());
            Timestamp st = execInfo.getStartTime();
            String startTime = Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString();
            String closeTime = null;
            Timestamp ct = execInfo.getCloseTime();
            if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                closeTime = Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString();
            }

            BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                          "HumanTaskInfo");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskIdStr));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
            record.put(StringUtils.fromString("namespace"),
                       StringUtils.fromString(client.getOptions().getNamespace()));
            record.put(StringUtils.fromString("taskQueue"),
                       StringUtils.fromString(response.getExecutionConfig().getTaskQueue().getName()));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
            record.put(StringUtils.fromString("status"), StringUtils.fromString(statusStr));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(startTime));
            record.put(StringUtils.fromString("closeTime"),
                       closeTime != null ? StringUtils.fromString(closeTime) : null);
            record.put(StringUtils.fromString("title"), StringUtils.fromString(title));
            record.put(StringUtils.fromString("description"), StringUtils.fromString(description));

            BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
            for (String role : userRolesArr) {
                roles.append(StringUtils.fromString(role));
            }
            record.put(StringUtils.fromString("userRoles"), roles);

            Object bPayload = payloadRaw != null ? TypesUtil.convertJavaToBallerinaType(payloadRaw) : null;
            record.put(StringUtils.fromString("payload"), bPayload);
            record.put(StringUtils.fromString("createdAt"), StringUtils.fromString(createdAt));
            record.put(StringUtils.fromString("formSchema"),
                       formSchema != null ? StringUtils.fromString(formSchema) : null);

            // Audit fields from the taskCompletion signal stored in workflow history
            String completedBy = readSignalField(client, taskIdStr, "taskCompletion", "completedBy");
            String completedAt = readSignalField(client, taskIdStr, "taskCompletion", "completedAt");
            Object resultRaw = readSignalPayloadField(client, taskIdStr, "taskCompletion", "result");

            record.put(StringUtils.fromString("completedBy"),
                       completedBy != null ? StringUtils.fromString(completedBy) : null);
            record.put(StringUtils.fromString("completedAt"),
                       completedAt != null ? StringUtils.fromString(completedAt) : null);
            record.put(StringUtils.fromString("result"),
                       resultRaw != null ? TypesUtil.convertJavaToBallerinaType(resultRaw) : null);

            return record;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get human task info: " + e.getMessage()));
        }
    }

    /**
     * Scans the parent workflow's event history for child humantask workflows, then groups their IDs by task name and
     * returns them sorted alphabetically.
     * <p>
     * Child workflow ID format: {@code humantask-{parentId}-{taskName}-{uuid}} where UUID is always 36 characters. The
     * task name is extracted by stripping the fixed prefix and the trailing {@code -{uuid}} (37 characters).
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
            DataConverter dc = client.getOptions().getDataConverter();

            // TreeMap keeps task names sorted alphabetically
            TreeMap<String, List<String>> byTaskName = new TreeMap<>();
            // Track childId → taskName so terminal events can remove the right entry
            HashMap<String, String> childIdToTaskName = new HashMap<>();
            ByteString nextPageToken = ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(parentId).build())
                        .setNextPageToken(nextPageToken)
                        .build();

                GetWorkflowExecutionHistoryResponse resp = client
                        .getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(req);

                for (HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType() == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
                        var attrs = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
                        String childId = attrs.getWorkflowId();
                        if (isHumanTaskType(attrs.getWorkflowType().getName())) {
                            // Task name is stored in memo (not in the instance ID anymore)
                            String taskName = decodeMemoString(dc, attrs.getMemo().getFieldsMap(), "taskName", childId);
                            childIdToTaskName.put(childId, taskName);
                            byTaskName.computeIfAbsent(taskName, k -> new ArrayList<>()).add(childId);
                        }
                    } else {
                        // Remove child workflows that have reached a terminal state
                        String completedChildId = getTerminalChildWorkflowId(event);
                        if (completedChildId != null && childIdToTaskName.containsKey(completedChildId)) {
                            String taskName = childIdToTaskName.get(completedChildId);
                            if (taskName != null) {
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
                }
                nextPageToken = resp.getNextPageToken();
            } while (!nextPageToken.isEmpty());

            RecordType groupType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                               "HumanTaskGroup").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(groupType));

            for (Map.Entry<String, List<String>> entry : byTaskName.entrySet()) {
                BMap<BString, Object> group = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                             "HumanTaskGroup");
                group.put(StringUtils.fromString("taskName"), StringUtils.fromString(entry.getKey()));

                BArray ids = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
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
     * Extracts the child workflow ID from a terminal child-workflow history event, or returns {@code null} if the event
     * is not a terminal child-workflow event type.
     */
    private static String getTerminalChildWorkflowId(HistoryEvent event) {
        return switch (event.getEventType()) {
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED ->
                    event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED ->
                    event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT ->
                    event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED ->
                    event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution().getWorkflowId();
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED ->
                    event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution().getWorkflowId();
            default -> null;
        };
    }

    /**
     * Converts a {@link io.temporal.api.workflow.v1.WorkflowExecutionInfo} to a Ballerina {@code HumanTaskSummary}
     * record. Reads {@code taskName} and {@code parentWorkflowId} from the execution's Temporal memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> toHumanTaskSummaryRecord(WorkflowClient client, WorkflowExecutionInfo wfInfo) {

        String wfId = wfInfo.getExecution().getWorkflowId();
        Map<String, Payload> memoFields = wfInfo.getMemo().getFieldsMap();
        DataConverter dc = client.getOptions().getDataConverter();

        String taskName = decodeMemoString(dc, memoFields, "taskName", "");
        // A work queue reads titles, not type names — the title is optional at creation, so it
        // falls back to the task name rather than arriving empty.
        String title = decodeMemoString(dc, memoFields, "title", taskName);
        String parentId = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
        String parentWorkflowType = decodeMemoString(dc, memoFields, "parentWorkflowType", null);

        String[] userRolesArr = new String[0];
        try {
            Payload rolesPl = memoFields.get("userRoles");
            if (rolesPl != null) {
                userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not decode userRoles from summary memo: {}", e.getMessage());
        }

        BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                      "HumanTaskSummary");
        record.put(StringUtils.fromString("taskId"), StringUtils.fromString(wfId));
        record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        record.put(StringUtils.fromString("title"), StringUtils.fromString(title));
        // Identify the owning integration: callers in a shared namespace (project) route
        // follow-up operations to the integration serving this task queue.
        record.put(StringUtils.fromString("namespace"),
                   StringUtils.fromString(client.getOptions().getNamespace()));
        record.put(StringUtils.fromString("taskQueue"), StringUtils.fromString(wfInfo.getTaskQueue()));
        record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
        record.put(StringUtils.fromString("parentWorkflowType"),
                   parentWorkflowType != null ? StringUtils.fromString(parentWorkflowType) : null);
        record.put(StringUtils.fromString("status"),
                   StringUtils.fromString(taskStatusFromTemporal(wfInfo.getStatus())));

        Timestamp st = wfInfo.getStartTime();
        record.put(StringUtils.fromString("startTime"),
                   StringUtils.fromString(Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

        Timestamp ct = wfInfo.getCloseTime();
        if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
            record.put(StringUtils.fromString("closeTime"),
                       StringUtils.fromString(Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
        } else {
            record.put(StringUtils.fromString("closeTime"), null);
        }

        BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
        for (String role : userRolesArr) {
            roles.append(StringUtils.fromString(role));
        }
        record.put(StringUtils.fromString("userRoles"), roles);
        // canComplete defaults to false; the Ballerina service layer recomputes it per caller
        record.put(StringUtils.fromString("canComplete"), false);
        return record;
    }

    /**
     * Decodes a string-valued field from a Temporal memo map. Returns {@code defaultValue} if the field is absent or
     * decoding fails.
     */
    /**
     * Decodes the {@code wfWaitingEvents} memo payload into the list of awaited data-event names.
     * Returns an empty list on any decoding failure.
     */
    private static List<String> decodeWaitingEventNames(DataConverter dc, Payload payload) {
        try {
            List<?> decoded = dc.fromPayload(payload, List.class, List.class);
            List<String> names = new ArrayList<>();
            if (decoded != null) {
                for (Object name : decoded) {
                    if (name != null) {
                        names.add(String.valueOf(name));
                    }
                }
            }
            return names;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String decodeMemoString(DataConverter dc, Map<String, Payload> fields, String key,
                                           String defaultValue) {
        try {
            Payload payload = fields.get(key);
            if (payload == null || payload.getData().isEmpty()) {
                return defaultValue;
            }
            return dc.fromPayload(payload, String.class, String.class);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Maps human task status names to Temporal execution status names for visibility queries. PENDING maps to Running.
     */
    private static void addTaskStatusClause(List<String> clauses, String status) {
        if (status == null) {
            return;
        }
        switch (status.toUpperCase(Locale.ROOT)) {
            case "PENDING", "RUNNING" -> clauses.add("ExecutionStatus = \"Running\"");
            case "COMPLETED" -> clauses.add("ExecutionStatus = \"Completed\"");
            // FAILED covers both a rejected/timed-out task workflow (Failed) and the rare
            // execution-timeout case (TimedOut) — see taskStatusFromTemporal.
            case "FAILED" -> clauses.add("(ExecutionStatus = \"Failed\" OR ExecutionStatus = \"TimedOut\")");
            case "CANCELED" -> clauses.add("ExecutionStatus = \"Canceled\"");
            case "TERMINATED" -> clauses.add("ExecutionStatus = \"Terminated\"");
            default -> clauses.add(String.format("ExecutionStatus = \"%s\"", status.replace("\"", "")));
        }
    }

    /**
     * Returns {@code true} when the workflow type names a human task child. The type is the
     * classifier — instance ids are bare UUIDs and say nothing about what an instance is.
     */
    private static boolean isHumanTaskType(String workflowType) {
        return workflowType.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX);
    }

    /**
     * Returns {@code true} when the workflow type names a review activity — the current
     * {@code reviewactivity-} types or the pre-0.7.0 shared {@code retrytask} type. Legacy
     * persisted retry tasks remain visible and completable through both the review activity API
     * and the deprecated retry-task API.
     */
    private static boolean isReviewActivityType(String workflowType) {
        return workflowType.startsWith(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX)
                || WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE.equals(workflowType)
                || workflowType.startsWith(WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE + "-");
    }

    /**
     * Returns {@code true} when the memo kind marks a review activity — the current
     * {@code REVIEW_ACTIVITY} kind or the pre-0.7.0 {@code RETRY_TASK} kind.
     */
    private static boolean isReviewActivityKind(String workflowKind) {
        return "REVIEW_ACTIVITY".equals(workflowKind) || "RETRY_TASK".equals(workflowKind);
    }

    /**
     * Maps a Temporal execution status to the task status model shared by human tasks and review activities
     * (ballerina-library#8892): {@code PENDING} (awaiting a human), {@code COMPLETED} (a human completed it),
     * {@code FAILED} (rejected via the fail operation, or timed out before anyone acted), {@code CANCELED}
     * (retired internally because the parent workflow closed), {@code TERMINATED} (an admin terminated it).
     */
    private static String taskStatusFromTemporal(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> "PENDING";
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> "COMPLETED";
            case WORKFLOW_EXECUTION_STATUS_FAILED, WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> "FAILED";
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> "CANCELED";
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> "TERMINATED";
            default -> "UNKNOWN";
        };
    }

    /**
     * Maps a Temporal {@link WorkflowExecutionStatus} enum value to its Ballerina string name.
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

    // -------------------------------------------------------------------------
    // completeHumanTask (management module entry point)
    // -------------------------------------------------------------------------

    /**
     * Completes a pending human task. Delegates to {@link WorkflowNative#completeHumanTask(BString, Object, Object)}.
     *
     * @param taskWorkflowId the Temporal workflow ID of the human task child workflow
     * @param result         the value to return to the waiting workflow
     * @param callerRoles    optional caller roles for authorization enforcement
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object completeHumanTask(BString taskWorkflowId, Object result, Object callerRoles, Object userId) {
        return WorkflowNative.completeHumanTask(taskWorkflowId, result, callerRoles, userId);
    }

    // -------------------------------------------------------------------------
    // MANUAL RETRY TASKS
    // -------------------------------------------------------------------------

    /**
     * Sends a {@code "taskDecision"} signal to the retry task child workflow identified by {@code taskWorkflowId},
     * resolving the manual retry with the supplied decision.
     *
     * @param taskWorkflowId the Temporal workflow ID of the retry task child workflow
     * @param decision       the {@code ReviewDecision} BMap ({@code action} + optional {@code input})
     * @param callerRoles    optional caller roles for authorization enforcement
     * @return {@code null} on success, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    public static Object completeReviewActivity(BString taskWorkflowId, BMap<BString, Object> decision,
                                                Object callerRoles,
                                           Object userId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            // Validate workflowKind and optionally enforce caller roles
            BArray callerRolesArray = (callerRoles instanceof BArray ba) ? ba : null;
            Object validationError = validateReviewActivityAndRoles(client, taskWorkflowId.getValue(),
                    callerRolesArray);
            if (validationError != null) {
                return validationError;
            }

            // Convert ReviewDecision BMap → serializable Java map
            Map<String, Object> javaDecision = new LinkedHashMap<>();
            Object actionVal = decision.get(StringUtils.fromString("action"));
            javaDecision.put("action", actionVal != null ? actionVal.toString() : "reject");

            Object inputVal = decision.get(StringUtils.fromString("input"));
            if (inputVal != null) {
                javaDecision.put("input", TypesUtil.convertBallerinaToJavaType(inputVal));
            }
            Object feedbackVal = decision.get(StringUtils.fromString("feedback"));
            if (feedbackVal instanceof BString fb) {
                javaDecision.put("feedback", fb.getValue());
            }
            // Embed audit fields so the history scan in getReviewActivityInfo can retrieve them
            javaDecision.put("decidedBy", userId instanceof BString bs ? bs.getValue() : "unknown");
            javaDecision.put("decidedAt", Instant.now().toString());

            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(taskWorkflowId.getValue(),
                                                                                   "taskDecision", javaDecision);

            if (!delivered) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Failed to complete retry task: task '" + taskWorkflowId.getValue() +
                                "' was no longer running when signal was delivered"));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to complete retry task: " + e.getMessage()));
        }
    }

    /**
     * Validates that {@code taskWorkflowId} is a running review activity workflow (including pre-0.7.0
     * RETRY_TASK instances) and optionally checks that at least
     * one of
     * the caller's roles appears in the task's {@code userRoles}.
     *
     * @return {@code null} if all checks pass, or a Ballerina error
     */
    @SuppressWarnings("unchecked")
    private static Object validateReviewActivityAndRoles(WorkflowClient client, String taskWorkflowId,
                                                    BArray callerRolesArray) {
        try {
            DescribeWorkflowExecutionRequest req = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(taskWorkflowId).build()).build();

            DescribeWorkflowExecutionResponse resp = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(
                    GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS).describeWorkflowExecution(req);

            WorkflowExecutionInfo execInfo = resp.getWorkflowExecutionInfo();

            // Decisions must go to the integration serving the task's queue: the reviewer
            // roles and forms are configured there, not here. Reads stay namespace-wide.
            String owningQueue = resp.getExecutionConfig().getTaskQueue().getName();
            String localQueue = WorkflowWorkerNative.getTaskQueue();
            if (localQueue == null || localQueue.isBlank()) {
                // Fail closed: without a configured local queue, ownership cannot be verified.
                return ErrorCreator.createError(StringUtils.fromString(
                        "Unauthorized: the local task queue is not configured; cannot verify that review "
                                + "activity '" + taskWorkflowId + "' belongs to this integration"));
            }
            if (!localQueue.equals(owningQueue)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Unauthorized: review activity '" + taskWorkflowId + "' belongs to task queue '"
                                + owningQueue + "', which is served by a different integration"));
            }

            WorkflowExecutionStatus execStatus = execInfo.getStatus();
            if (execStatus != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Retry task '" + taskWorkflowId + "' is not running (status=" + convertStatus(execStatus) +
                                ")"));
            }

            Map<String, Payload> memoFields = execInfo.getMemo().getFieldsMap();
            DataConverter dc = client.getOptions().getDataConverter();

            // workflowKind check
            String workflowKind = decodeMemoString(dc, memoFields, "workflowKind", null);
            if (!isReviewActivityKind(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Invalid task: '" + taskWorkflowId + "' is not a review activity workflow (workflowKind=" +
                                workflowKind + ")"));
            }

            if (callerRolesArray == null) {
                return null;
            }

            Set<String> allowedRoles = new HashSet<>();
            try {
                Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    String[] rolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                    allowedRoles.addAll(Arrays.asList(rolesArr));
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
                    "Unauthorized: caller does not have a required role to complete retry task '" + taskWorkflowId +
                            "'. Required one of: " + allowedRoles));

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to validate retry task '" + taskWorkflowId + "': " + e.getMessage()));
        }
    }

    /**
     * Scans the parent workflow's event history for child retry task workflows and returns them as
     * {@code ReviewActivitySummary} records sorted alphabetically by task name.
     * <p>
     * Child workflow ID format: {@code reviewactivity-{parentId}-{taskName}-{uuid}} where UUID is always 36 characters.
     *
     * @param parentWorkflowId the parent workflow ID
     * @return a Ballerina {@code ReviewActivitySummary[]} or an error
     */
    public static Object listPendingReviewActivities(BString parentWorkflowId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String parentId = parentWorkflowId.getValue();
            DataConverter dc = client.getOptions().getDataConverter();

            TreeMap<String, List<String>> byTaskName = new TreeMap<>();
            HashMap<String, String> childIdToTaskName = new HashMap<>();
            ByteString nextPageToken = ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(parentId).build())
                        .setNextPageToken(nextPageToken)
                        .build();

                GetWorkflowExecutionHistoryResponse resp = client
                        .getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getWorkflowExecutionHistory(req);

                for (HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType() == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
                        var attrs = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
                        String childId = attrs.getWorkflowId();
                        if (isReviewActivityType(attrs.getWorkflowType().getName())) {
                            String taskName = decodeMemoString(dc, attrs.getMemo().getFieldsMap(), "taskName", childId);
                            childIdToTaskName.put(childId, taskName);
                            byTaskName.computeIfAbsent(taskName, k -> new ArrayList<>()).add(childId);
                        }
                    } else {
                        String completedChildId = getTerminalChildWorkflowId(event);
                        if (completedChildId != null && childIdToTaskName.containsKey(completedChildId)) {
                            String taskName = childIdToTaskName.get(completedChildId);
                            if (taskName != null) {
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
                }
                nextPageToken = resp.getNextPageToken();
            } while (!nextPageToken.isEmpty());

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                 "ReviewActivitySummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            for (Map.Entry<String, List<String>> entry : byTaskName.entrySet()) {
                for (String childId : entry.getValue()) {
                    result.append(buildReviewActivitySummaryFromId(client, childId, entry.getKey()));
                }
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list pending retry tasks: " + e.getMessage()));
        }
    }

    /**
     * Lists all manual retry task instances via Temporal's visibility API. Filters executions whose workflow ID starts
     * with {@code reviewactivity-}.
     *
     * @param status optional status filter
     * @return a Ballerina {@code ReviewActivitySummary[]} or an error
     */
    public static Object listAllReviewActivities(Object status, Object startTimeFrom, Object startTimeTo,
                                           Object closeTimeFrom, Object closeTimeTo, Object taskQueue) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String statusFilter = status instanceof BString bs ? bs.getValue() : null;
            List<String> clauses = new ArrayList<>();
            addTaskStatusClause(clauses, statusFilter);
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");
            addTaskQueueClause(clauses, taskQueue);
            // Server-side type filter, covering the pre-0.7.0 legacy retrytask forms too.
            clauses.add("(WorkflowType STARTS_WITH '" + WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX
                    + "' OR WorkflowType = '" + WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE
                    + "' OR WorkflowType STARTS_WITH '" + WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE + "-')");
            String query = String.join(" AND ", clauses);

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                 "ReviewActivitySummary").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            ByteString pageToken = ByteString.EMPTY;
            do {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(query)
                        .setPageSize(100)
                        .setNextPageToken(pageToken)
                        .build();

                ListWorkflowExecutionsResponse response =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                                .listWorkflowExecutions(request);

                for (WorkflowExecutionInfo wfInfo : response.getExecutionsList()) {
                    if (!isReviewActivityType(wfInfo.getType().getName())) {
                        continue;
                    }
                    result.append(toReviewActivitySummaryRecord(client, wfInfo));
                }

                pageToken = response.getNextPageToken();
            } while (!pageToken.isEmpty());

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to list retry tasks: " + e.getMessage()));
        }
    }

    /**
     * Returns detailed info for a single retry task by reading its Temporal memo.
     *
     * @param taskId the child workflow ID of the retry task
     * @return a Ballerina {@code ReviewActivityInfo} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object getReviewActivityInfo(BString taskId) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            String taskIdStr = taskId.getValue();

            DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder().setNamespace(
                    client.getOptions().getNamespace()).setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(taskIdStr).build()).build();

            DescribeWorkflowExecutionResponse response =
                    client
                            .getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            WorkflowExecutionInfo execInfo = response.getWorkflowExecutionInfo();
            Map<String, Payload> memoFields = execInfo.getMemo().getFieldsMap();
            DataConverter dc = client.getOptions().getDataConverter();

            // Only review activity workflows may be served here — a human task or user workflow
            // ID must not leak through this endpoint (ballerina-library#8894).
            String workflowKind = decodeMemoString(dc, memoFields, "workflowKind", null);
            if (!isReviewActivityKind(workflowKind)) {
                return ErrorCreator.createError(StringUtils.fromString(
                        "Review activity not found: '" + taskIdStr + "' is not a review activity workflow"));
            }

            String activityName = decodeMemoString(dc, memoFields, "activityName", "");
            String taskName = decodeMemoString(dc, memoFields, "taskName", "");
            String parentId = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
            String errorMessage = decodeMemoString(dc, memoFields, "errorMessage", "");
            String createdAt = decodeMemoString(dc, memoFields, "createdAt", "");
            // Older review activities (created before the trigger field) are all failure-driven.
            String trigger = decodeMemoString(dc, memoFields, "trigger", "ON_FAILURE");
            String title = decodeMemoString(dc, memoFields, "title",
                    ("ON_FAILURE".equals(trigger) ? "Review failed activity: " : "Approval required: ")
                            + activityName);
            String description = decodeMemoString(dc, memoFields, "description", "");
            String formSchema = decodeMemoString(dc, memoFields, "formSchema", null);

            String[] userRolesArr = new String[0];
            try {
                Payload rolesPl = memoFields.get("userRoles");
                if (rolesPl != null) {
                    userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode userRoles memo field: {}", e.getMessage());
            }

            Object activityArgsRaw = null;
            try {
                Payload argsPl = memoFields.get("activityArgs");
                if (argsPl != null) {
                    activityArgsRaw = dc.fromPayload(argsPl, Object.class, Object.class);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not decode activityArgs memo field: {}", e.getMessage());
            }

            String statusStr = taskStatusFromTemporal(execInfo.getStatus());
            Timestamp st = execInfo.getStartTime();
            String startTime = Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString();
            String closeTime = null;
            Timestamp ct = execInfo.getCloseTime();
            if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                closeTime = Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString();
            }

            BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                          "ReviewActivityInfo");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskIdStr));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
            record.put(StringUtils.fromString("namespace"),
                       StringUtils.fromString(client.getOptions().getNamespace()));
            record.put(StringUtils.fromString("taskQueue"),
                       StringUtils.fromString(response.getExecutionConfig().getTaskQueue().getName()));
            record.put(StringUtils.fromString("activityName"), StringUtils.fromString(activityName));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
            record.put(StringUtils.fromString("trigger"), StringUtils.fromString(trigger));
            record.put(StringUtils.fromString("title"), StringUtils.fromString(title));
            record.put(StringUtils.fromString("description"), StringUtils.fromString(description));
            record.put(StringUtils.fromString("status"), StringUtils.fromString(statusStr));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(startTime));
            record.put(StringUtils.fromString("closeTime"),
                       closeTime != null ? StringUtils.fromString(closeTime) : null);
            record.put(StringUtils.fromString("formSchema"),
                       formSchema != null ? StringUtils.fromString(formSchema) : null);

            BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
            for (String role : userRolesArr) {
                roles.append(StringUtils.fromString(role));
            }
            record.put(StringUtils.fromString("userRoles"), roles);
            record.put(StringUtils.fromString("errorMessage"), StringUtils.fromString(errorMessage));

            Object bArgs = activityArgsRaw != null ? TypesUtil.convertJavaToBallerinaType(activityArgsRaw) : null;
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
            return ErrorCreator.createError(StringUtils.fromString("Failed to get retry task info: " + e.getMessage()));
        }
    }

    /**
     * Builds a minimal {@code ReviewActivitySummary} record from a known task ID by calling
     * {@code DescribeWorkflowExecution} to read status and timestamps. Reads {@code taskName} and {@code activityName}
     * from memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> buildReviewActivitySummaryFromId(WorkflowClient client, String taskId,
                                                                     String fallbackTaskName) {
        try {
            DescribeWorkflowExecutionResponse resp = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(
                    GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS).describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest
                            .newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(taskId).build())
                            .build());
            return toReviewActivitySummaryRecord(client, resp.getWorkflowExecutionInfo());
        } catch (Exception e) {
            // Fallback: minimal record with the info we already have
            BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                          "ReviewActivitySummary");
            record.put(StringUtils.fromString("taskId"), StringUtils.fromString(taskId));
            record.put(StringUtils.fromString("taskName"), StringUtils.fromString(fallbackTaskName));
            record.put(StringUtils.fromString("activityName"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("trigger"), StringUtils.fromString("ON_FAILURE"));
            record.put(StringUtils.fromString("title"),
                       StringUtils.fromString("Review failed activity: " + fallbackTaskName));
            record.put(StringUtils.fromString("status"), StringUtils.fromString("UNKNOWN"));
            record.put(StringUtils.fromString("startTime"), StringUtils.fromString(""));
            record.put(StringUtils.fromString("closeTime"), null);
            record.put(StringUtils.fromString("userRoles"),
                       ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING)));
            return record;
        }
    }

    /**
     * Converts a {@link io.temporal.api.workflow.v1.WorkflowExecutionInfo} to a Ballerina {@code ReviewActivitySummary}
     * record. Reads {@code taskName}, {@code activityName}, and {@code parentWorkflowId} from the execution's Temporal
     * memo.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> toReviewActivitySummaryRecord(WorkflowClient client,
                                                                        WorkflowExecutionInfo wfInfo) {

        String wfId = wfInfo.getExecution().getWorkflowId();
        Map<String, Payload> memoFields = wfInfo.getMemo().getFieldsMap();
        DataConverter dc = client.getOptions().getDataConverter();

        String taskName = decodeMemoString(dc, memoFields, "taskName", "");
        String activityName = decodeMemoString(dc, memoFields, "activityName", "");
        String parentId = decodeMemoString(dc, memoFields, "parentWorkflowId", "");
        String trigger = decodeMemoString(dc, memoFields, "trigger", "ON_FAILURE");
        String title = decodeMemoString(dc, memoFields, "title",
                ("ON_FAILURE".equals(trigger) ? "Review failed activity: " : "Approval required: ") + activityName);
        String[] userRolesArr = new String[0];
        try {
            Payload rolesPl = memoFields.get("userRoles");
            if (rolesPl != null) {
                userRolesArr = dc.fromPayload(rolesPl, String[].class, String[].class);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not decode userRoles from review summary memo: {}", e.getMessage());
        }

        BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                      "ReviewActivitySummary");
        record.put(StringUtils.fromString("taskId"), StringUtils.fromString(wfId));
        record.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        record.put(StringUtils.fromString("namespace"),
                   StringUtils.fromString(client.getOptions().getNamespace()));
        record.put(StringUtils.fromString("taskQueue"), StringUtils.fromString(wfInfo.getTaskQueue()));
        record.put(StringUtils.fromString("activityName"), StringUtils.fromString(activityName));
        record.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentId));
        record.put(StringUtils.fromString("trigger"), StringUtils.fromString(trigger));
        record.put(StringUtils.fromString("title"), StringUtils.fromString(title));
        record.put(StringUtils.fromString("status"),
                   StringUtils.fromString(taskStatusFromTemporal(wfInfo.getStatus())));

        Timestamp st = wfInfo.getStartTime();
        record.put(StringUtils.fromString("startTime"),
                   StringUtils.fromString(Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

        Timestamp ct = wfInfo.getCloseTime();
        if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
            record.put(StringUtils.fromString("closeTime"),
                       StringUtils.fromString(Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
        } else {
            record.put(StringUtils.fromString("closeTime"), null);
        }

        BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
        for (String role : userRolesArr) {
            roles.append(StringUtils.fromString(role));
        }
        record.put(StringUtils.fromString("userRoles"), roles);
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
            String reasonStr = reason instanceof BString bs ? bs.getValue() : "Terminated via management API";
            WorkflowStub stub = rid != null ? client.newUntypedWorkflowStub(wfId, Optional.of(rid), Optional.empty()) :
                                client.newUntypedWorkflowStub(wfId);
            stub.terminate(reasonStr);
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to terminate workflow: " + e.getMessage()));
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
            WorkflowStub stub = rid != null ? client.newUntypedWorkflowStub(wfId, Optional.of(rid), Optional.empty()) :
                                client.newUntypedWorkflowStub(wfId);
            stub.cancel();
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to cancel workflow: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // WORKFLOW LISTING AND STARTING
    // -------------------------------------------------------------------------

    /**
     * Starts a new workflow instance by its registered type name. Returns a {@code WorkflowHandle} record with
     * {@code workflowId} and {@code runId}.
     *
     * @param workflowType    registered workflow type (function name)
     * @param input           workflow input (Ballerina value, may be null)
     * @param workflowIdParam optional explicit workflow ID (BString or nil)
     * @param timeoutSeconds  optional timeout in seconds (Long or nil)
     * @param startedBy       optional starter user ID stored in workflow memo
     * @return a Ballerina {@code WorkflowHandle} record or an error
     */
    public static Object startWorkflowByType(BString workflowType, Object input, Object workflowIdParam,
                                             Object timeoutSeconds, Object startedBy) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }
            String taskQueue = WorkflowWorkerNative.getTaskQueue();
            if (taskQueue == null) {
                return ErrorCreator.createError(StringUtils.fromString("Task queue not configured"));
            }
            String type = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + workflowType.getValue();
            String wfId =
                    workflowIdParam instanceof BString bs ? bs.getValue() : CorrelationExtractor.generateWorkflowId();

            WorkflowOptions.Builder optBuilder = WorkflowOptions.newBuilder().setWorkflowId(wfId).setTaskQueue(
                    taskQueue);
            if (timeoutSeconds instanceof Long secs) {
                optBuilder.setWorkflowExecutionTimeout(Duration.ofSeconds(secs));
            }
            // Every instance says what it is, in its memo: consumers route to the right UI by
            // asking the instance, never by parsing its id — the prefixes stay for human eyes only.
            String kind = WorkflowWorkerNative.isAgentWorkflowType(type) ? "AGENT" : "WORKFLOW";
            Map<String, Object> memo = new HashMap<>();
            memo.put("workflowKind", kind);
            if (startedBy instanceof BString starter && !starter.getValue().isBlank()) {
                memo.put("startedBy", starter.getValue());
            }
            optBuilder.setMemo(memo);
            if (WorkflowWorkerNative.isKindSearchAttributeReady()) {
                // The indexed copy, so visibility queries can filter by kind. Only when the
                // cluster confirmed the attribute — an unknown attribute fails the start.
                optBuilder.setTypedSearchAttributes(io.temporal.common.SearchAttributes.newBuilder()
                        .set(WorkflowWorkerNative.WORKFLOW_KIND_KEY, kind).build());
            }

            WorkflowStub stub = client.newUntypedWorkflowStub(type, optBuilder.build());
            Object javaInput;
            if (WorkflowWorkerNative.isAgentWorkflowType(type)) {
                // Starting a durable agent goes through the same endpoint as a workflow: the
                // posted input is mapped onto the agent's declared inputType and wrapped in
                // the runner envelope the shared runner workflow expects.
                Object envelope = DurableAgentNative.buildStartRunInput(workflowType.getValue(), input);
                if (envelope instanceof BError inputError) {
                    return inputError;
                }
                javaInput = envelope;
            } else {
                javaInput = input != null ? TypesUtil.convertBallerinaToJavaType(input) : null;
            }
            WorkflowExecution execution = stub.start(javaInput);

            BMap<BString, Object> handle = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                          "WorkflowHandle");
            handle.put(StringUtils.fromString("workflowId"), StringUtils.fromString(execution.getWorkflowId()));
            handle.put(StringUtils.fromString("runId"), StringUtils.fromString(execution.getRunId()));
            return handle;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to start workflow: " + e.getMessage()));
        }
    }

    /**
     * Lists workflow instances via Temporal's visibility API with optional filters and pagination. Automatically
     * excludes humantask- and reviewactivity- child workflows.
     *
     * @param status       optional status filter BString (RUNNING, COMPLETED, FAILED, …)
     * @param workflowType optional workflow type filter BString
     * @param workflowId   optional workflow ID prefix filter BString
     * @param startedBy    optional starter user ID filter BString (memo field)
     * @param limit        maximum results per page
     * @param pageToken    opaque Base64-encoded continuation token BString, or null
     * @return a Ballerina {@code WorkflowInstancePage} record or an error
     */
    @SuppressWarnings("unchecked")
    public static Object listWorkflowInstances(Object status, Object workflowType, Object workflowId, Object startedBy,
                                               long limit, Object pageToken, Object startTimeFrom, Object startTimeTo,
                                               Object closeTimeFrom, Object closeTimeTo,
                                               Object taskQueue, Object kind) {
        try {
            WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return ErrorCreator.createError(StringUtils.fromString(ERR_CLIENT_NOT_INIT));
            }

            // Build Temporal visibility query.
            // User workflow types are registered with the "workflow-" prefix so a simple
            // STARTS_WITH clause filters out internal humantask-* and reviewactivity-* children
            // without needing the NOT operator (unsupported on standard visibility stores).
            List<String> clauses = new ArrayList<>();

            // RUNNING and SUSPENDED share Temporal's Running execution status; the memo flag
            // upserted by the suspend signal handler splits them client-side below.
            String statusFilter = status instanceof BString bs ? bs.getValue().toUpperCase(Locale.ROOT) : null;
            boolean suspendedOnly = "SUSPENDED".equals(statusFilter);
            boolean runningOnly = "RUNNING".equals(statusFilter);
            if (statusFilter != null) {
                String ts = toWorkflowTemporalStatus(statusFilter);
                if (ts != null) {
                    clauses.add(String.format("ExecutionStatus = \"%s\"", ts));
                }
            }
            if (workflowType instanceof BString wt) {
                // User provides the display name; add the internal prefix for the query.
                String prefixedType = WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + wt.getValue();
                String safeWt = prefixedType.replace("\\", "\\\\").replace("\"", "\\\"");
                clauses.add(String.format("WorkflowType = \"%s\"", safeWt));
            } else if (!(kind instanceof BString k && !k.getValue().isBlank()
                    && WorkflowWorkerNative.isKindSearchAttributeReady())) {
                // The pre-kind way of excluding task and review children. A kind filter says
                // precisely what the caller wants — including those very children — so it
                // replaces this heuristic rather than being overridden by it. But only a kind
                // filter that will actually be applied replaces it: on a server without the
                // WorkflowKind attribute the kind clause is dropped (see addKindClause), and
                // dropping this exclusion too would leak every task and review child.
                clauses.add("WorkflowType STARTS_WITH 'workflow-'");
            }
            if (workflowId instanceof BString wi) {
                String safeId = wi.getValue().replace("\\", "\\\\").replace("'", "\\'");
                clauses.add(String.format("WorkflowId STARTS_WITH '%s'", safeId));
            }
            addTimeClause(clauses, startTimeFrom, "StartTime", ">=");
            addTimeClause(clauses, startTimeTo, "StartTime", "<=");
            addTimeClause(clauses, closeTimeFrom, "CloseTime", ">=");
            addTimeClause(clauses, closeTimeTo, "CloseTime", "<=");
            addTaskQueueClause(clauses, taskQueue);
            addKindClause(clauses, kind);

            String query = String.join(" AND ", clauses);
            int pageSize = (int) Math.min(limit, 100);

            ByteString nextPageTokenBytes = ByteString.EMPTY;
            if (pageToken instanceof BString pt && !pt.getValue().isEmpty()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(pt.getValue());
                    nextPageTokenBytes = ByteString.copyFrom(decoded);
                } catch (IllegalArgumentException ignored) {
                    // Invalid token — start from beginning
                }
            }

            RecordType summaryType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                 "WorkflowInstanceSummary").getType();
            BArray items = ValueCreator.createArrayValue(TypeCreator.createArrayType(summaryType));

            // `startedBy` is stored in memo, so Temporal visibility cannot filter it server-side.
            // Scan additional pages until we collect enough matching items or exhaust results.
            boolean hasStartedByFilter = startedBy instanceof BString starter && !starter.getValue().isBlank();
            String startedByValue = hasStartedByFilter ? ((BString) startedBy).getValue() : null;
            int matchedCount = 0;
            ByteString nextToken = nextPageTokenBytes;

            while (matchedCount < pageSize) {
                int temporalPageSize = pageSize;
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest
                        .newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(query)
                        .setPageSize(temporalPageSize)
                        .setNextPageToken(nextToken)
                        .build();

                ListWorkflowExecutionsResponse response =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                                .listWorkflowExecutions(request);

                for (WorkflowExecutionInfo wfInfo : response.getExecutionsList()) {
                    if (hasStartedByFilter) {
                        String startedByMemo = decodeMemoString(client.getOptions().getDataConverter(),
                                                                wfInfo.getMemo().getFieldsMap(), "startedBy", null);
                        if (!startedByValue.equals(startedByMemo)) {
                            continue;
                        }
                    }

                    String displayStatus = convertStatus(wfInfo.getStatus());
                    if ("RUNNING".equals(displayStatus)
                            && WorkflowWorkerNative.isSuspendedMemo(client, wfInfo)) {
                        displayStatus = "SUSPENDED";
                    }
                    if (suspendedOnly && !"SUSPENDED".equals(displayStatus)) {
                        continue;
                    }
                    if (runningOnly && "SUSPENDED".equals(displayStatus)) {
                        continue;
                    }

                    BMap<BString, Object> summary = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                   "WorkflowInstanceSummary");
                    summary.put(StringUtils.fromString("workflowId"),
                                StringUtils.fromString(wfInfo.getExecution().getWorkflowId()));
                    summary.put(StringUtils.fromString("runId"),
                                StringUtils.fromString(wfInfo.getExecution().getRunId()));
                    String rawType = wfInfo.getType().getName();
                    String displayType = rawType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX) ?
                                         rawType.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) :
                                         rawType;
                    summary.put(StringUtils.fromString("workflowType"), StringUtils.fromString(displayType));
                    summary.put(StringUtils.fromString("namespace"),
                                StringUtils.fromString(client.getOptions().getNamespace()));
                    summary.put(StringUtils.fromString("taskQueue"),
                                StringUtils.fromString(wfInfo.getTaskQueue()));
                    summary.put(StringUtils.fromString("status"), StringUtils.fromString(displayStatus));

                    Timestamp st = wfInfo.getStartTime();
                    summary.put(StringUtils.fromString("startTime"), StringUtils.fromString(
                            Instant.ofEpochSecond(st.getSeconds(), st.getNanos()).toString()));

                    Timestamp ct = wfInfo.getCloseTime();
                    if (ct.getSeconds() > 0 || ct.getNanos() > 0) {
                        summary.put(StringUtils.fromString("closeTime"), StringUtils.fromString(
                                Instant.ofEpochSecond(ct.getSeconds(), ct.getNanos()).toString()));
                    } else {
                        summary.put(StringUtils.fromString("closeTime"), null);
                    }
                    summary.put(StringUtils.fromString("input"), null);
                    // The kind rides the visibility row's memo, so a listing can say what each
                    // row is without a describe per row. Legacy rows without the memo fall back
                    // to the type prefixes their era still used.
                    String rowKind = decodeMemoString(client.getOptions().getDataConverter(),
                                                      wfInfo.getMemo().getFieldsMap(), "workflowKind", null);
                    if (rowKind == null) {
                        rowKind = isHumanTaskType(rawType) ? "HUMAN_TASK"
                                : isReviewActivityType(rawType) ? "REVIEW_ACTIVITY"
                                : rawType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX)
                                        ? "WORKFLOW" : "CHILD_WORKFLOW";
                    }
                    summary.put(StringUtils.fromString("kind"), StringUtils.fromString(rowKind));
                    items.append(summary);
                    matchedCount++;
                    if (matchedCount >= pageSize) {
                        break;
                    }
                }

                nextToken = response.getNextPageToken();
                if (nextToken.isEmpty()) {
                    break;
                }
            }

            boolean hasMore = !nextToken.isEmpty();
            String nextTokenStr = hasMore ? Base64.getEncoder().encodeToString(nextToken.toByteArray()) : null;

            BMap<BString, Object> page = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                        "WorkflowInstancePage");
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
     * Scans the execution history of {@code workflowId} for a {@code WorkflowExecutionSignaled} event with the given
     * {@code signalName} and returns the String value of {@code fieldKey} from the signal payload map. Returns
     * {@code null} if not found or on any error.
     */
    @SuppressWarnings("unchecked")
    static String readSignalField(WorkflowClient client, String workflowId, String signalName, String fieldKey) {
        try {
            Object raw = readSignalPayloadField(client, workflowId, signalName, fieldKey);
            return raw instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scans the execution history of {@code workflowId} for a {@code WorkflowExecutionSignaled} event with the given
     * {@code signalName} and returns the raw value of {@code fieldKey} from the decoded signal payload map. Returns
     * {@code null} if not found or on any error.
     */
    @SuppressWarnings("unchecked")
    static Object readSignalPayloadField(WorkflowClient client, String workflowId, String signalName, String fieldKey) {
        try {
            WorkflowExecution execution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
            String namespace = client.getOptions().getNamespace();
            DataConverter dc = client.getOptions().getDataConverter();
            ByteString pageToken = ByteString.EMPTY;

            do {
                GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest.newBuilder().setNamespace(
                        namespace).setExecution(execution).setNextPageToken(pageToken).build();

                GetWorkflowExecutionHistoryResponse resp =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                                .getWorkflowExecutionHistory(req);

                for (HistoryEvent event : resp.getHistory().getEventsList()) {
                    if (event.getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) {
                        continue;
                    }
                    WorkflowExecutionSignaledEventAttributes attrs =
                            event.getWorkflowExecutionSignaledEventAttributes();
                    if (!signalName.equals(attrs.getSignalName())) {
                        continue;
                    }
                    // Decode the first payload in the signal input
                    Payloads payloads = attrs.getInput();
                    if (payloads.getPayloadsCount() == 0) {
                        continue;
                    }
                    Object decoded = dc.fromPayload(payloads.getPayloads(0), Object.class, Object.class);
                    if (decoded instanceof Map<?, ?> m) {
                        return ((Map<String, Object>) m).get(fieldKey);
                    }
                }

                pageToken = resp.getNextPageToken();
            } while (!pageToken.isEmpty());

        } catch (Exception e) {
            LOGGER.debug("readSignalPayloadField failed for {}/{}/{}: {}", workflowId, signalName, fieldKey,
                         e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // EXECUTION VISUALIZATION — Phase 3
    // =========================================================================

    /**
     * Returns all execution history events for a workflow run in chronological order. Each event includes
     * event-type-specific attributes serialized as a Ballerina {@code map<json>}.
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
            String rid = runId.getValue().isEmpty() ? null : runId.getValue();

            List<HistoryEvent> events = fetchFullHistory(client, wfId, rid);

            RecordType historyEventType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                      "HistoryEvent").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(historyEventType));

            JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
            ObjectMapper mapper = new ObjectMapper();

            for (HistoryEvent event : events) {
                BMap<BString, Object> record = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                              "HistoryEvent");

                record.put(StringUtils.fromString("eventId"), event.getEventId());
                record.put(StringUtils.fromString("eventType"),
                           StringUtils.fromString(simplifyEventType(event.getEventType())));

                Timestamp ts = event.getEventTime();
                record.put(StringUtils.fromString("timestamp"),
                           StringUtils.fromString(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString()));

                // Serialize event to JSON, extract the *EventAttributes sub-object
                Map<String, Object> attrMap = new LinkedHashMap<>();
                try {
                    String json = printer.print(event);
                    @SuppressWarnings("unchecked") Map<String, Object> eventMap = mapper.readValue(json, Map.class);
                    for (Map.Entry<String, Object> entry : eventMap.entrySet()) {
                        if (entry.getKey().endsWith("EventAttributes") && entry.getValue() instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked") Map<String, Object> typedMap = (Map<String, Object>) m;
                            attrMap.putAll(typedMap);
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to serialize history event {}: {}", event.getEventId(), e.getMessage());
                }
                record.put(StringUtils.fromString("attributes"), TypesUtil.convertJavaToBallerinaType(attrMap));
                result.append(record);
            }
            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow history: " + e.getMessage()));
        }
    }

    /**
     * Parses the workflow execution history and returns a flat ordered list of {@code ActivityTreeNode} records
     * representing activities, child workflows, timers, and user-visible signals.
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
            String rid = runId.getValue().isEmpty() ? null : runId.getValue();
            DataConverter dc = client.getOptions().getDataConverter();

            List<HistoryEvent> events = fetchFullHistory(client, wfId, rid);

            // eventId → mutable node data; insertion order preserved
            LinkedHashMap<Long, LinkedHashMap<String, Object>> nodeByEventId = new LinkedHashMap<>();
            List<Long> nodeOrder = new ArrayList<>();
            // Data-event waits published via the wfWaitingEvents memo: name → its WAITING node,
            // completed in place when the matching signal arrives.
            LinkedHashMap<String, LinkedHashMap<String, Object>> pendingDataNodes = new LinkedHashMap<>();

            for (HistoryEvent event : events) {
                long eid = event.getEventId();
                String ts = Instant
                        .ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos())
                        .toString();

                switch (event.getEventType()) {

                    case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                        var attrs = event.getActivityTaskScheduledEventAttributes();
                        var node = newNode(eid, attrs.getActivityType().getName(), "ACTIVITY", ts);
                        node.put("input", decodeFirstPayload(attrs.getInput(), dc));
                        // Which call site scheduled this activity — the identity that places the
                        // execution on the descriptor's graph. Absent for executions started
                        // before the runtime carried it, which is why `site` stays optional.
                        node.put("stepId", decodeStepId(attrs.getInput(), dc));
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
                                node.put("failureType", attrs.getFailure().getApplicationFailureInfo().getType());
                                if (attrs.getFailure().hasCause()) {
                                    node.put("failureCause", attrs.getFailure().getCause().getMessage());
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
                        String childId = attrs.getWorkflowId();
                        String childType = attrs.getWorkflowType().getName();
                        String nodeType = childNodeType(childType);
                        // A review child's task name lives in its memo; human tasks use
                        // shortTaskName(), which parses the "humantask-workflowDef.taskName" type.
                        String nodeName = isReviewActivityType(childType) ? decodeMemoString(dc, attrs
                                .getMemo()
                                .getFieldsMap(), "taskName", childType) : shortTaskName(childType);
                        var node = newNode(eid, nodeName, nodeType, ts);
                        node.put("childWorkflowId", childId);
                        node.put("input", decodeFirstPayload(attrs.getInput(), dc));
                        node.put("stepId", decodeMemoString(dc, attrs.getMemo().getFieldsMap(),
                                io.ballerina.lib.workflow.context.WorkflowContextNative.STEP_ID_KEY, null));
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
                        // The step id rides as the timer's summary: a timer id is replay-validated
                        // and SDK-assigned, so user metadata is the only carrier available.
                        node.put("stepId", decodeSummary(event, dc));
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
                        // A workflow-to-agent sendData travels as the __agent_event transport
                        // signal whose envelope carries the actual channel name — decode it so
                        // the channel's WAITING node completes under its own name instead of a
                        // bogus node named after the transport signal.
                        if (WorkflowWorkerNative.AGENT_EVENT_SIGNAL_NAME.equals(sigName)) {
                            Object envelope = decodeFirstPayload(attrs.getInput(), dc);
                            if (envelope instanceof Map<?, ?> eventMap
                                    && eventMap.get("eventName") != null) {
                                sigName = String.valueOf(eventMap.get("eventName"));
                            } else {
                                break;
                            }
                        }
                        if (!isInternalSignal(sigName)) {
                            // Ballerina surfaces Temporal signals as data events (workflow:sendData
                            // -> `wait dataEvents.<name>`), so the node type says DATA, not SIGNAL.
                            // A wait published for this event completes in place, keeping its
                            // start time; an unawaited (buffered) event gets its own node.
                            var pending = pendingDataNodes.remove(sigName);
                            if (pending != null) {
                                pending.put("status", "COMPLETED");
                                pending.put("endTime", ts);
                            } else {
                                var node = newNode(eid, sigName, "DATA", ts);
                                node.put("status", "COMPLETED");
                                node.put("endTime", ts);
                                nodeByEventId.put(eid, node);
                                nodeOrder.add(eid);
                            }
                        }
                    }

                    case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> {
                        // A durable agent's data events can also arrive as Temporal updates
                        // (DurableAgent.sendData -> the agentSendData dynamic update handler).
                        // Mirror the SIGNALED handling: a published wait for the event channel
                        // completes in place; an unawaited (buffered) event gets its own node.
                        var attrs = event.getWorkflowExecutionUpdateAcceptedEventAttributes();
                        var input = attrs.getAcceptedRequest().getInput();
                        if (!WorkflowWorkerNative.AGENT_SEND_DATA_UPDATE.equals(input.getName())
                                || input.getArgs().getPayloadsCount() == 0) {
                            break;
                        }
                        String updEventName;
                        try {
                            updEventName = dc.fromPayload(input.getArgs().getPayloads(0),
                                    String.class, String.class);
                        } catch (Exception e) {
                            break;
                        }
                        if (updEventName == null) {
                            break;
                        }
                        var pending = pendingDataNodes.remove(updEventName);
                        if (pending != null) {
                            pending.put("status", "COMPLETED");
                            pending.put("endTime", ts);
                        } else {
                            var node = newNode(eid, updEventName, "DATA", ts);
                            node.put("status", "COMPLETED");
                            node.put("endTime", ts);
                            nodeByEventId.put(eid, node);
                            nodeOrder.add(eid);
                        }
                    }

                    case EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED -> {
                        // The workflow publishes the data events it is blocked on via the
                        // wfWaitingEvents memo (see WaitingEventsTracker). New names become DATA
                        // nodes with status WAITING so a halted workflow shows where it waits;
                        // a name removed while still WAITING means the wait was abandoned
                        // without its event (e.g. cancellation) and reports CANCELED.
                        var attrs = event.getWorkflowPropertiesModifiedEventAttributes();
                        Payload waitingPayload = attrs.getUpsertedMemo().getFieldsMap()
                                .get(io.ballerina.lib.workflow.context.WaitingEventsTracker.WAITING_EVENTS_MEMO_KEY);
                        if (waitingPayload == null) {
                            break;
                        }
                        List<String> waitingNames = decodeWaitingEventNames(dc, waitingPayload);
                        for (String waitingName : waitingNames) {
                            if (!pendingDataNodes.containsKey(waitingName)) {
                                var node = newNode(eid, waitingName, "DATA", ts);
                                node.put("status", "WAITING");
                                pendingDataNodes.put(waitingName, node);
                                nodeByEventId.put(eid, node);
                                nodeOrder.add(eid);
                            }
                        }
                        var abandoned = pendingDataNodes.entrySet().iterator();
                        while (abandoned.hasNext()) {
                            var entry = abandoned.next();
                            if (!waitingNames.contains(entry.getKey())) {
                                entry.getValue().put("status", "CANCELED");
                                entry.getValue().put("endTime", ts);
                                abandoned.remove();
                            }
                        }
                    }

                    default -> { /* ignore workflow-level events */ }
                }
            }

            // Convert node data maps → Ballerina ActivityTreeNode records
            RecordType nodeType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                              "ActivityTreeNode").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(nodeType));
            for (long eid : nodeOrder) {
                var data = nodeByEventId.get(eid);
                if (data != null) {
                    result.append(buildTreeNode(data));
                }
            }
            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get activity tree: " + e.getMessage()));
        }
    }

    /**
     * Derives a directed execution graph from the workflow history. Nodes represent execution steps; sequential edges
     * connect them in order.
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
            if (treeResult instanceof BError) {
                return treeResult;
            }
            BArray treeNodes = (BArray) treeResult;

            RecordType graphNodeType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                   "GraphNode").getType();
            RecordType graphEdgeType = (RecordType) ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                   "GraphEdge").getType();
            BArray nodes = ValueCreator.createArrayValue(TypeCreator.createArrayType(graphNodeType));
            BArray edges = ValueCreator.createArrayValue(TypeCreator.createArrayType(graphEdgeType));

            // Add all tree nodes as graph nodes; build sequential edges
            String prevId = null;
            for (int i = 0; i < treeNodes.size(); i++) {
                @SuppressWarnings("unchecked") BMap<BString, Object> treeNode = (BMap<BString, Object>) treeNodes.get(
                        i);

                String id = ((BString) treeNode.get(StringUtils.fromString("id"))).getValue();
                String name = ((BString) treeNode.get(StringUtils.fromString("name"))).getValue();
                String type = ((BString) treeNode.get(StringUtils.fromString("type"))).getValue();
                String status = ((BString) treeNode.get(StringUtils.fromString("status"))).getValue();

                // Metadata: the childWorkflowId for human/retry tasks, and the call site that
                // ran — what lets a viewer highlight this node on the static graph.
                BMap<BString, Object> metadata = null;
                Object cwf = treeNode.get(StringUtils.fromString("childWorkflowId"));
                if (cwf instanceof BString cws) {
                    metadata = ValueCreator.createMapValue();
                    metadata.put(StringUtils.fromString("taskId"), cws);
                }
                Object stepIdValue = treeNode.get(StringUtils.fromString("stepId"));
                if (stepIdValue instanceof BString stepIdStr) {
                    if (metadata == null) {
                        metadata = ValueCreator.createMapValue();
                    }
                    metadata.put(StringUtils.fromString("stepId"), stepIdStr);
                }

                BMap<BString, Object> gn = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                          "GraphNode");
                gn.put(StringUtils.fromString("id"), StringUtils.fromString(id));
                gn.put(StringUtils.fromString("label"), StringUtils.fromString(name));
                gn.put(StringUtils.fromString("type"), StringUtils.fromString(type));
                gn.put(StringUtils.fromString("status"), StringUtils.fromString(status));
                gn.put(StringUtils.fromString("metadata"), metadata);
                nodes.append(gn);

                if (prevId != null) {
                    BMap<BString, Object> edge = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                                "GraphEdge");
                    edge.put(StringUtils.fromString("source"), StringUtils.fromString(prevId));
                    edge.put(StringUtils.fromString("target"), StringUtils.fromString(id));
                    edge.put(StringUtils.fromString("label"), null);
                    edges.append(edge);
                }
                prevId = id;
            }

            BMap<BString, Object> graph = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                         "ExecutionGraph");
            graph.put(StringUtils.fromString("nodes"), nodes);
            graph.put(StringUtils.fromString("edges"), edges);
            return graph;

        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Failed to get execution graph: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // VISUALIZATION HELPERS
    // -------------------------------------------------------------------------

    /**
     * Fetches all history pages for a workflow run and returns them as a flat list. Hard cap at 2000 events to prevent
     * unbounded memory use.
     */
    private static List<HistoryEvent> fetchFullHistory(WorkflowClient client, String workflowId, String runId)
            throws Exception {
        List<HistoryEvent> events = new ArrayList<>();
        ByteString pageToken = ByteString.EMPTY;

        WorkflowExecution.Builder execBuilder = WorkflowExecution.newBuilder().setWorkflowId(workflowId);
        if (runId != null) {
            execBuilder.setRunId(runId);
        }

        do {
            GetWorkflowExecutionHistoryRequest req = GetWorkflowExecutionHistoryRequest
                    .newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setExecution(execBuilder.build())
                    .setNextPageToken(pageToken)
                    .setMaximumPageSize(500)
                    .build();
            GetWorkflowExecutionHistoryResponse resp =
                    client
                            .getWorkflowServiceStubs()
                            .blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .getWorkflowExecutionHistory(req);
            events.addAll(resp.getHistory().getEventsList());
            pageToken = resp.getNextPageToken();
            if (events.size() >= 2000) {
                throw new Exception(
                        "History for workflow '" + workflowId + "' exceeds 2000 events and cannot be loaded in full");
            }
        } while (!pageToken.isEmpty());

        return events;
    }

    /**
     * Strips the {@code EVENT_TYPE_} prefix for a cleaner event type string.
     */
    private static String simplifyEventType(EventType type) {
        String name = type.name();
        return name.startsWith("EVENT_TYPE_") ? name.substring("EVENT_TYPE_".length()) : name;
    }

    /**
     * Creates a fresh mutable node data map with the fields common to all node types.
     */
    private static LinkedHashMap<String, Object> newNode(long scheduledEventId, String name, String type,
                                                         String startTime) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("id", String.valueOf(scheduledEventId));
        node.put("name", name);
        node.put("type", type);
        node.put("status", "RUNNING");
        node.put("startTime", startTime);
        node.put("attempt", 1);
        return node;
    }

    /**
     * Determines the {@code ActivityNodeType} for a child workflow from its workflow type.
     */
    private static String childNodeType(String workflowType) {
        if (isHumanTaskType(workflowType)) {
            return "HUMAN_TASK";
        }
        if (isReviewActivityType(workflowType)) {
            return "REVIEW_ACTIVITY";
        }
        return "CHILD_WORKFLOW";
    }

    /**
     * Extracts a short human-readable task name from the workflow type (qualified name) for human/retry task nodes.
     * Falls back to the full type name for other child workflows.
     */
    private static String shortTaskName(String workflowType) {
        // Human tasks and review activities carry the qualified name in their type
        // ("humantask-workflowDefinition.taskName") → return "taskName". Pre-rename children used
        // the single shared "retrytask" type, whose task name only lives in the memo — callers
        // with memo access should use decodeMemoString("taskName") for those.
        if (isHumanTaskType(workflowType) || isReviewActivityType(workflowType)) {
            int dot = workflowType.lastIndexOf('.');
            if (dot >= 0) {
                return workflowType.substring(dot + 1);
            }
        }
        return workflowType;
    }

    /**
     * Returns {@code true} for Temporal-internal or framework-level signals that should not appear as user-visible
     * nodes in the activity tree.
     */
    private static boolean isInternalSignal(String signalName) {
        return signalName.startsWith("__wf_") || "taskCompletion".equals(signalName) || "taskDecision".equals(
                signalName);
    }

    /**
     * Decodes the first payload element from a {@code Payloads} envelope. Returns {@code null} on any decoding
     * failure.
     */
    private static Object decodeFirstPayload(Payloads payloads, DataConverter dc) {
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
     * Reads an event's user-metadata summary — where a timer carries its step id, since a timer's id
     * is replay-validated and assigned by the SDK. Absent on servers that do not record user
     * metadata (before 1.27) and on executions from before the runtime set it, which is why the
     * step id stays optional.
     *
     * @param event the history event
     * @param dc    the data converter
     * @return the summary, or {@code null}
     */
    private static String decodeSummary(HistoryEvent event, DataConverter dc) {
        if (!event.hasUserMetadata() || !event.getUserMetadata().hasSummary()) {
            return null;
        }
        try {
            return dc.fromPayload(event.getUserMetadata().getSummary(), String.class, String.class);
        } catch (Exception e) {
            // Unreadable metadata is reported as no step id, never as a failed tree.
            return null;
        }
    }

    /**
     * Reads the call-site identity out of an activity invocation: {@code callActivity} sends
     * {@code [namedArgs, callConfig]} and the config carries {@code site}. Returns {@code null}
     * for an invocation that carries none — an older runtime, or a hand-written call.
     */
    private static String decodeStepId(Payloads payloads, DataConverter dc) {
        if (payloads == null || payloads.getPayloadsCount() < 2) {
            return null;
        }
        try {
            Object config = dc.fromPayload(payloads.getPayloads(1), Object.class, Object.class);
            if (config instanceof java.util.Map<?, ?> configMap
                    && configMap.get(io.ballerina.lib.workflow.context.WorkflowContextNative.STEP_ID_KEY)
                            instanceof String site) {
                return site;
            }
        } catch (Exception e) {
            // A payload we cannot read is reported as no site, never as a failed tree.
        }
        return null;
    }

    /**
     * Converts a mutable node data map into a Ballerina {@code ActivityTreeNode} record.
     */
    @SuppressWarnings("unchecked")
    private static BMap<BString, Object> buildTreeNode(LinkedHashMap<String, Object> data) {
        BMap<BString, Object> node = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                    "ActivityTreeNode");

        node.put(StringUtils.fromString("id"), StringUtils.fromString((String) data.getOrDefault("id", "")));
        node.put(StringUtils.fromString("name"), StringUtils.fromString((String) data.getOrDefault("name", "")));
        node.put(StringUtils.fromString("type"),
                 StringUtils.fromString((String) data.getOrDefault("type", "ACTIVITY")));
        node.put(StringUtils.fromString("status"),
                 StringUtils.fromString((String) data.getOrDefault("status", "RUNNING")));

        String startTime = (String) data.get("startTime");
        node.put(StringUtils.fromString("startTime"), startTime != null ? StringUtils.fromString(startTime) : null);
        String endTime = (String) data.get("endTime");
        node.put(StringUtils.fromString("endTime"), endTime != null ? StringUtils.fromString(endTime) : null);

        Object input = data.get("input");
        node.put(StringUtils.fromString("input"), input != null ? TypesUtil.convertJavaToBallerinaType(input) : null);
        Object output = data.get("output");
        node.put(StringUtils.fromString("output"),
                 output != null ? TypesUtil.convertJavaToBallerinaType(output) : null);

        // Failure
        String failMsg = (String) data.get("failureMessage");
        if (failMsg != null) {
            BMap<BString, Object> failure = ValueCreator.createRecordValue(ModuleUtils.getManagementModule(),
                                                                           "FailureInfo");
            failure.put(StringUtils.fromString("message"), StringUtils.fromString(failMsg));
            String failType = (String) data.get("failureType");
            failure.put(StringUtils.fromString("type"), failType != null ? StringUtils.fromString(failType) : null);
            String failCause = (String) data.get("failureCause");
            failure.put(StringUtils.fromString("cause"), failCause != null ? StringUtils.fromString(failCause) : null);
            node.put(StringUtils.fromString("failure"), failure);
        } else {
            node.put(StringUtils.fromString("failure"), null);
        }

        int attempt = data.get("attempt") instanceof Integer i ? i : 1;
        node.put(StringUtils.fromString("attempt"), (long) attempt);
        String stepId = (String) data.get("stepId");
        node.put(StringUtils.fromString("stepId"), stepId != null ? StringUtils.fromString(stepId) : null);
        node.put(StringUtils.fromString("children"), null);
        return node;
    }

    /**
     * Maps a Ballerina workflow status string to a Temporal visibility execution status string.
     */
    /**
     * Appends a time-range clause to {@code clauses} when {@code param} is a non-empty BString. Produces:
     * {@code <field> <op> "<iso8601>"}  (e.g. {@code StartTime >= "2026-06-01T00:00:00Z"}). The value is stripped of
     * any embedded double-quotes to prevent query injection.
     */
    // Scopes a visibility query to one integration's task queue. Callers within the same
    // namespace (project) share visibility; the TaskQueue attribute separates integrations.
    private static void addTaskQueueClause(List<String> clauses, Object taskQueue) {
        if (taskQueue instanceof BString queue && !queue.getValue().isBlank()) {
            clauses.add("TaskQueue = '" + queue.getValue().replace("'", "''") + "'");
        }
    }

    // Scopes a visibility query to one kind of instance — WORKFLOW, AGENT, HUMAN_TASK,
    // REVIEW_ACTIVITY, CHILD_WORKFLOW — via the WorkflowKind search attribute the starts stamp.
    // Only instances started after the attribute existed match; a server without the attribute
    // rejects the query, which is the honest answer where filtering is genuinely unavailable.
    private static void addKindClause(List<String> clauses, Object kind) {
        if (kind instanceof BString value && !value.getValue().isBlank()) {
            if (!WorkflowWorkerNative.isKindSearchAttributeReady()) {
                // The clause would make the whole query fail on a server without the attribute —
                // notably the in-memory dev server, which does not support custom search
                // attributes. Listing unfiltered (and saying so) beats failing the listing.
                LOGGER.warn("Kind filtering requires the WorkflowKind search attribute, which this "
                        + "server does not provide (the in-memory dev server does not support custom "
                        + "search attributes); listing without the kind filter.");
                return;
            }
            clauses.add("WorkflowKind = '" + value.getValue().replace("'", "''") + "'");
        }
    }

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
            case "RUNNING" -> "Running";
            case "COMPLETED" -> "Completed";
            case "FAILED" -> "Failed";
            case "CANCELED" -> "Canceled";
            case "TERMINATED" -> "Terminated";
            case "TIMED_OUT" -> "TimedOut";
            default -> null;
        };
    }
}
