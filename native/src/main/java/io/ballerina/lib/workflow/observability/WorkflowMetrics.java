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
import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.runtime.observability.metrics.DefaultMetricRegistry;
import io.ballerina.runtime.observability.metrics.MetricId;
import io.ballerina.runtime.observability.metrics.MetricRegistry;
import io.ballerina.runtime.observability.metrics.StatisticConfig;
import io.ballerina.runtime.observability.metrics.Tag;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Workflow metrics in the Ballerina metric registry, per the integration observability standard: one
// workflow_events_total counter with uniform labels (none where a key does not apply). No-op when metrics are off.
public final class WorkflowMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowMetrics.class);

    // Sentinel for a label that does not apply to an event, keeping label sets uniform.
    public static final String NONE = "none";

    private static final String EVENTS_METRIC = "workflow_events_total";
    private static final String EVENTS_DESC = "Total workflow lifecycle events, distinguished by tags";

    // Identity tags, present on every sample.
    private static final String TAG_MODULE = "module";
    private static final String TAG_TYPE = "type";
    private static final String TAG_REMOTE_URL = "remote_url";
    private static final String TAG_TASK_QUEUE = "task_queue";
    private static final String TAG_HOST = "host";
    private static final String MODULE_VALUE = "workflow";
    private static final String TYPE_CLIENT = "client";
    private static final String TYPE_WORKER = "worker";

    // Event tags; non-applicable keys carry the sentinel so every increment has the same key set.
    private static final String TAG_EVENT = "event";
    static final String TAG_WORKFLOW_TYPE = "workflow_type";
    static final String TAG_ACTIVITY_TYPE = "activity_type";
    static final String TAG_DATA_NAME = "data_name";
    static final String TAG_TASK_KIND = "task_kind";
    static final String TAG_TASK_NAME = "task_name";
    static final String TAG_TOOL_NAME = "tool_name";
    static final String TAG_ACTION = "action";
    static final String TAG_OUTCOME = "outcome";
    static final String TAG_ERROR_TYPE = "error_type";

    private static final String EVENT_STARTED = "started";
    private static final String EVENT_CLOSED = "closed";
    private static final String EVENT_ACTIVITY = "activity_executed";
    private static final String EVENT_DATA_SENT = "data_sent";
    private static final String EVENT_TASK_DECIDED = "task_decided";
    public static final String EVENT_SUSPENDED = "suspended";
    public static final String EVENT_RESUMED = "resumed";
    public static final String EVENT_TERMINATED = "terminated";
    public static final String EVENT_CANCELLED = "cancelled";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_FAILURE = "failure";

    // Duration summaries publish p50/p75/p90/p95/p99 over a five-minute sliding window.
    private static final StatisticConfig DURATION_STATS = StatisticConfig.builder()
            .percentiles(0.5, 0.75, 0.9, 0.95, 0.99)
            .expiry(Duration.ofMinutes(5))
            .build();

    private static final String HOST_NAME = resolveHostName();

    private WorkflowMetrics() {
    }

    // A run began executing on this worker; callers gate on replay. Every start path converges here.
    public static void recordWorkflowStarted(String workflowType) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordWorkflowStarted(registry(), workflowType);
        } catch (Exception e) {
            LOGGER.debug("Failed to record workflow started metric", e);
        }
    }

    static void recordWorkflowStarted(MetricRegistry registry, String workflowType) {
        event(registry, EVENT_STARTED, TYPE_WORKER, workflowType, NONE, NONE, taskKindOf(workflowType),
              taskNameOf(workflowType), NONE, NONE, OUTCOME_SUCCESS, NONE).increment();
    }

    // A run closed on this worker with its duration (ignored when negative); callers gate on replay.
    public static void recordWorkflowClosed(String workflowType, long durationMillis, Throwable failure) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordWorkflowClosed(registry(), workflowType, durationMillis, failure);
        } catch (Exception e) {
            LOGGER.debug("Failed to record workflow closed metric", e);
        }
    }

    static void recordWorkflowClosed(MetricRegistry registry, String workflowType, long durationMillis,
                                     Throwable failure) {
        boolean failed = failure != null;
        String taskKind = taskKindOf(workflowType);
        String taskName = taskNameOf(workflowType);
        event(registry, EVENT_CLOSED, TYPE_WORKER, workflowType, NONE, NONE, taskKind, taskName, NONE, NONE,
              failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS, errorTypeOf(failure)).increment();
        if (durationMillis >= 0) {
            Set<Tag> tags = identityTags(TYPE_WORKER);
            tags.add(Tag.of(TAG_WORKFLOW_TYPE, workflowType));
            tags.add(Tag.of(TAG_TASK_KIND, taskKind));
            tags.add(Tag.of(TAG_TASK_NAME, taskName));
            tags.add(Tag.of(TAG_OUTCOME, failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS));
            registry.gauge(new MetricId("workflow_duration_seconds",
                                        "Workflow execution duration from run start to completion", tags),
                           DURATION_STATS)
                    .setValue(durationMillis / 1000.0);
        }
    }

    // HUMAN_TASK, REVIEW_ACTIVITY or none: task children run as prefixed child workflow types.
    static String taskKindOf(String workflowType) {
        if (workflowType.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX)) {
            return "HUMAN_TASK";
        }
        if (workflowType.startsWith(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX)
                || WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE.equals(workflowType)) {
            return "REVIEW_ACTIVITY";
        }
        return NONE;
    }

    // The declared task name a task workflow type carries (the type without its kind prefix), or none.
    static String taskNameOf(String workflowType) {
        if (workflowType.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX)) {
            return workflowType.substring(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX.length());
        }
        if (workflowType.startsWith(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX)) {
            return workflowType.substring(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX.length());
        }
        return NONE;
    }

    // One activity attempt on this worker; attempts are never replayed.
    public static void recordActivityExecution(String activityType, String workflowType, long durationMillis,
                                               Throwable failure) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordActivityExecution(registry(), activityType, workflowType, durationMillis, failure);
        } catch (Exception e) {
            LOGGER.debug("Failed to record activity execution metric", e);
        }
    }

    static void recordActivityExecution(MetricRegistry registry, String activityType, String workflowType,
                                        long durationMillis, Throwable failure) {
        boolean failed = failure != null;
        event(registry, EVENT_ACTIVITY, TYPE_WORKER, workflowType, activityType, NONE, NONE, NONE, NONE, NONE,
              failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS, errorTypeOf(failure)).increment();
        if (durationMillis >= 0) {
            Set<Tag> tags = identityTags(TYPE_WORKER);
            tags.add(Tag.of(TAG_WORKFLOW_TYPE, workflowType));
            tags.add(Tag.of(TAG_ACTIVITY_TYPE, activityType));
            tags.add(Tag.of(TAG_OUTCOME, failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS));
            registry.gauge(new MetricId("workflow_activity_duration_seconds",
                                        "Workflow activity execution duration", tags),
                           DURATION_STATS)
                    .setValue(durationMillis / 1000.0);
        }
    }

    // A data event delivery attempted by this runtime; framework signals (control, task decisions) are not counted.
    public static void recordDataSent(String dataName, Throwable failure) {
        if (!isMetricsEnabled() || WorkflowWorkerNative.isFrameworkSignal(dataName)) {
            return;
        }
        try {
            recordDataSent(registry(), dataName, failure);
        } catch (Exception e) {
            LOGGER.debug("Failed to record data event metric", e);
        }
    }

    static void recordDataSent(MetricRegistry registry, String dataName, Throwable failure) {
        boolean failed = failure != null;
        event(registry, EVENT_DATA_SENT, TYPE_CLIENT, NONE, NONE, boundedDataName(dataName), NONE, NONE, NONE, NONE,
              failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS, errorTypeOf(failure)).increment();
    }

    // A control operation (suspend, resume, terminate, cancel) attempted by this client; workflow_type is none.
    public static void recordControl(String event, String errorType) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordControl(registry(), event, errorType);
        } catch (Exception e) {
            LOGGER.debug("Failed to record workflow control metric", e);
        }
    }

    static void recordControl(MetricRegistry registry, String event, String errorType) {
        boolean failed = errorType != null && !errorType.isEmpty();
        event(registry, event, TYPE_CLIENT, NONE, NONE, NONE, NONE, NONE, NONE, NONE,
              failed ? OUTCOME_FAILURE : OUTCOME_SUCCESS, failed ? errorType : NONE).increment();
    }

    // One completed durable-agent step; callers gate on replay.
    public static void recordAgentStep(AgentStep step) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordAgentStep(registry(), step);
        } catch (Exception e) {
            LOGGER.debug("Failed to record agent step metric", e);
        }
    }

    static void recordAgentStep(MetricRegistry registry, AgentStep step) {
        String outcome = step.failed() ? OUTCOME_FAILURE : OUTCOME_SUCCESS;
        event(registry, step.event(), TYPE_WORKER, step.workflowType(), step.activityType(), step.dataName(),
              step.taskKind(), step.taskName(), step.toolName(), step.action(), outcome, step.errorType()).increment();
        if (step.durationMillis() >= 0) {
            Set<Tag> tags = identityTags(TYPE_WORKER);
            tags.add(Tag.of(TAG_WORKFLOW_TYPE, step.workflowType()));
            tags.add(Tag.of(TAG_EVENT, step.event()));
            tags.add(Tag.of(TAG_ACTIVITY_TYPE, step.activityType()));
            tags.add(Tag.of(TAG_TOOL_NAME, step.toolName()));
            tags.add(Tag.of(TAG_DATA_NAME, step.dataName()));
            tags.add(Tag.of(TAG_TASK_NAME, step.taskName()));
            tags.add(Tag.of(TAG_OUTCOME, outcome));
            registry.gauge(new MetricId("workflow_agent_step_duration_seconds",
                                        "Duration of one durable agent step, on the engine's clock", tags),
                           DURATION_STATS)
                    .setValue(step.durationMillis() / 1000.0);
        }
    }

    // One decision on a task, accepted or refused; who decided stays on the span and audit entry, not a tag.
    public static void recordTaskDecision(String taskKind, String taskName, String action, boolean accepted,
                                          String errorType) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            recordTaskDecision(registry(), taskKind, taskName, action, accepted, errorType);
        } catch (Exception e) {
            LOGGER.debug("Failed to record task decision metric", e);
        }
    }

    static void recordTaskDecision(MetricRegistry registry, String taskKind, String taskName, String action,
                                   boolean accepted, String errorType) {
        event(registry, EVENT_TASK_DECIDED, TYPE_CLIENT, NONE, NONE, NONE, taskKind, taskName, NONE, action,
              accepted ? OUTCOME_SUCCESS : OUTCOME_FAILURE,
              (errorType == null || errorType.isEmpty()) ? NONE : errorType).increment();
    }

    // The error_type tag value: the application failure type when present, else the class name; none on success.
    public static String errorTypeOf(Throwable failure) {
        if (failure == null) {
            return NONE;
        }
        if (failure instanceof ApplicationFailure applicationFailure && !applicationFailure.getType().isEmpty()) {
            return applicationFailure.getType();
        }
        return failure.getClass().getSimpleName();
    }

    // The counter cell for one event, with the full uniform label set.
    private static io.ballerina.runtime.observability.metrics.Counter event(MetricRegistry registry,
            String event, String type, String workflowType, String activityType, String dataName,
            String taskKind, String taskName, String toolName, String action, String outcome, String errorType) {
        Set<Tag> tags = identityTags(type);
        tags.add(Tag.of(TAG_EVENT, event));
        tags.add(Tag.of(TAG_WORKFLOW_TYPE, workflowType));
        tags.add(Tag.of(TAG_ACTIVITY_TYPE, activityType));
        tags.add(Tag.of(TAG_DATA_NAME, dataName));
        tags.add(Tag.of(TAG_TASK_KIND, taskKind));
        tags.add(Tag.of(TAG_TASK_NAME, taskName));
        tags.add(Tag.of(TAG_TOOL_NAME, toolName));
        tags.add(Tag.of(TAG_ACTION, action));
        tags.add(Tag.of(TAG_OUTCOME, outcome));
        tags.add(Tag.of(TAG_ERROR_TYPE, errorType));
        return registry.counter(new MetricId(EVENTS_METRIC, EVENTS_DESC, tags));
    }

    // Identity tags every sample carries: module, client/worker, engine endpoint, task queue, host.
    private static Set<Tag> identityTags(String type) {
        Set<Tag> tags = new HashSet<>();
        tags.add(Tag.of(TAG_MODULE, MODULE_VALUE));
        tags.add(Tag.of(TAG_TYPE, type));
        String url = WorkflowWorkerNative.getServerUrl();
        tags.add(Tag.of(TAG_REMOTE_URL, url == null || url.isEmpty() ? NONE : url));
        String queue = WorkflowWorkerNative.getTaskQueue();
        tags.add(Tag.of(TAG_TASK_QUEUE, queue == null || queue.isEmpty() ? NONE : queue));
        tags.add(Tag.of(TAG_HOST, HOST_NAME));
        return tags;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return NONE;
        }
    }

    // Cap on distinct data_name series: dynamic sendData names are unvalidated, and each tag set is a new series.
    private static final int MAX_DATA_NAME_SERIES = 64;
    private static final String OTHER_DATA_NAME = "__other__";
    private static final Set<String> SEEN_DATA_NAMES = ConcurrentHashMap.newKeySet();

    // The bounded data_name tag value; admission is atomic so concurrent senders cannot exceed the budget.
    static String boundedDataName(String dataName) {
        if (SEEN_DATA_NAMES.contains(dataName)) {
            return dataName;
        }
        synchronized (SEEN_DATA_NAMES) {
            if (SEEN_DATA_NAMES.contains(dataName)) {
                return dataName;
            }
            if (SEEN_DATA_NAMES.size() >= MAX_DATA_NAME_SERIES) {
                return OTHER_DATA_NAME;
            }
            SEEN_DATA_NAMES.add(dataName);
            return dataName;
        }
    }

    private static boolean isMetricsEnabled() {
        return ObserveUtils.isMetricsEnabled() && DefaultMetricRegistry.getInstance() != null;
    }

    private static MetricRegistry registry() {
        return DefaultMetricRegistry.getInstance();
    }
}
