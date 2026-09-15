/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

package io.ballerina.lib.workflow.observability;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.temporal.activity.ActivityInfo;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

// One structured log record per workflow event under logger="workflow-metrics", like ballerinax/metrics.logs.
// Structural fields only. Off when publishMetricSamples is off.
public final class WorkflowSampleLog {

    // A child of the module logger, so the module's Ballerina-style console handler formats these.
    private static final Logger SAMPLES = Logger.getLogger("io.ballerina.lib.workflow.observability.samples");
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(WorkflowSampleLog.class);
    static final String LOGGER_TAG = "workflow-metrics";
    private static final String FIELD_WORKFLOW_ID = "workflow_id";
    private static final String FIELD_RUN_ID = "run_id";
    private static final String FIELD_ATTEMPT = "attempt";
    private static final String FIELD_DURATION_SECONDS = "duration_seconds";

    private WorkflowSampleLog() {
    }

    // A run began executing; callers gate on replay. Published from the adapter so every start path is one sample.
    public static void workflowStarted(String workflowType, String workflowId, String runId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(WorkflowMetrics.TAG_WORKFLOW_TYPE, workflowType);
        f.put(WorkflowMetrics.TAG_TASK_KIND, WorkflowMetrics.taskKindOf(workflowType));
        f.put(WorkflowMetrics.TAG_TASK_NAME, WorkflowMetrics.taskNameOf(workflowType));
        f.put(FIELD_WORKFLOW_ID, workflowId);
        f.put(FIELD_RUN_ID, runId);
        record("workflow.started", f);
    }

    // A run closed; callers gate on replay.
    public static void workflowClosed(String workflowType, String workflowId, String runId, long durationMillis,
                                      boolean failed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(WorkflowMetrics.TAG_WORKFLOW_TYPE, workflowType);
        f.put(WorkflowMetrics.TAG_TASK_KIND, WorkflowMetrics.taskKindOf(workflowType));
        f.put(WorkflowMetrics.TAG_TASK_NAME, WorkflowMetrics.taskNameOf(workflowType));
        f.put(FIELD_WORKFLOW_ID, workflowId);
        f.put(FIELD_RUN_ID, runId);
        f.put(WorkflowMetrics.TAG_OUTCOME, outcome(failed));
        f.put(FIELD_DURATION_SECONDS, durationMillis / 1000.0);
        record("workflow.closed", f);
    }

    // One activity attempt finished.
    public static void activityExecuted(ActivityInfo info, long durationMillis, boolean failed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(WorkflowMetrics.TAG_ACTIVITY_TYPE, info.getActivityType());
        f.put(FIELD_WORKFLOW_ID, info.getWorkflowId());
        f.put(FIELD_RUN_ID, info.getRunId());
        f.put(FIELD_ATTEMPT, info.getAttempt());
        f.put(WorkflowMetrics.TAG_OUTCOME, outcome(failed));
        f.put(FIELD_DURATION_SECONDS, durationMillis / 1000.0);
        record("activity.executed", f);
    }

    // A data event delivery, accepted or not; the name shares the registry's series budget. Control signals skipped.
    public static void dataSent(String dataName, String workflowId, boolean failed) {
        if (WorkflowWorkerNative.isFrameworkSignal(dataName)) {
            return;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(WorkflowMetrics.TAG_DATA_NAME, WorkflowMetrics.boundedDataName(dataName));
        f.put(FIELD_WORKFLOW_ID, workflowId);
        f.put(WorkflowMetrics.TAG_OUTCOME, outcome(failed));
        record("data.sent", f);
    }

    // A control operation (suspend, resume, terminate, cancel) attempted against an instance.
    public static void control(String event, String workflowId, boolean failed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(FIELD_WORKFLOW_ID, workflowId);
        f.put(WorkflowMetrics.TAG_OUTCOME, outcome(failed));
        record("workflow." + event, f);
    }

    // One agent step completed; callers gate on replay. Fields mirror the registry tags, none where absent.
    public static void agentStep(AgentStep step, String workflowId, String runId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(WorkflowMetrics.TAG_WORKFLOW_TYPE, step.workflowType());
        f.put(FIELD_WORKFLOW_ID, workflowId);
        f.put(FIELD_RUN_ID, runId);
        f.put(WorkflowMetrics.TAG_ACTIVITY_TYPE, step.activityType());
        f.put(WorkflowMetrics.TAG_TOOL_NAME, step.toolName());
        f.put(WorkflowMetrics.TAG_DATA_NAME, step.dataName());
        f.put(WorkflowMetrics.TAG_TASK_KIND, step.taskKind());
        f.put(WorkflowMetrics.TAG_TASK_NAME, step.taskName());
        f.put(WorkflowMetrics.TAG_ACTION, step.action());
        f.put(WorkflowMetrics.TAG_OUTCOME, outcome(step.failed()));
        f.put(WorkflowMetrics.TAG_ERROR_TYPE, step.errorType());
        f.put(FIELD_DURATION_SECONDS, step.durationMillis() / 1000.0);
        record(step.sampleName(), f);
    }

    private static String outcome(boolean failed) {
        return failed ? WorkflowMetrics.OUTCOME_FAILURE : WorkflowMetrics.OUTCOME_SUCCESS;
    }

    private static void record(String sample, Map<String, Object> fields) {
        if (!ObservabilityNative.areMetricSamplesPublished()) {
            return;
        }
        try {
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("logger", LOGGER_TAG);
            all.put("sample", sample);
            all.putAll(fields);
            // An empty message and the fields as the record's one parameter: the module's formatter
            // renders a Map parameter as top-level key=value pairs, as ballerina/log would.
            LogRecord entry = new LogRecord(Level.INFO, "");
            entry.setLoggerName(SAMPLES.getName());
            entry.setParameters(new Object[] {all});
            SAMPLES.log(entry);
        } catch (Exception e) {
            // A sample is never worth failing a workflow over.
            LOGGER.debug("Failed to publish workflow sample '{}'", sample, e);
        }
    }
}
