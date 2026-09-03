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

package io.ballerina.lib.workflow.observability;

import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.runtime.observability.metrics.DefaultMetricRegistry;
import io.ballerina.runtime.observability.metrics.MetricId;
import io.ballerina.runtime.observability.metrics.MetricRegistry;
import io.ballerina.runtime.observability.metrics.StatisticConfig;
import io.ballerina.runtime.observability.metrics.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records workflow runtime metrics through the Ballerina observability metric registry.
 * <p>
 * All metrics are published only when the program is built with {@code observabilityIncluded = true}
 * and metrics are enabled at runtime; otherwise every call is a no-op. Only structural identifiers
 * (workflow types, activity types, declared event names) are used as tags — never instance-level
 * IDs or business data, keeping tag cardinality bounded.
 * <p>
 * Recording must never affect workflow execution: every method swallows and logs unexpected errors.
 *
 * @since 0.9.0
 */
public final class WorkflowMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowMetrics.class);

    private static final String TAG_WORKFLOW_TYPE = "workflow_type";
    private static final String TAG_ACTIVITY_TYPE = "activity_type";
    private static final String TAG_DATA_NAME = "data_name";
    private static final String TAG_STATUS = "status";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private WorkflowMetrics() {
    }

    /**
     * Records that a new top-level workflow instance was started by this runtime.
     *
     * @param workflowType the workflow type name
     */
    public static void recordWorkflowStart(String workflowType) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            counter("workflow_starts_total", "Total workflow instances started by this runtime",
                    Set.of(Tag.of(TAG_WORKFLOW_TYPE, workflowType))).increment();
        } catch (Exception e) {
            LOGGER.debug("Failed to record workflow start metric", e);
        }
    }

    /**
     * Records the completion of a workflow execution on this worker, along with its duration
     * measured from the run start. Callers must gate this on {@code !Workflow.isReplaying()}
     * so replays never double-count completions.
     *
     * @param workflowType   the workflow type name
     * @param durationMillis run duration in milliseconds (ignored when negative)
     * @param failed         whether the execution failed
     */
    public static void recordWorkflowCompletion(String workflowType, long durationMillis, boolean failed) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            Set<Tag> tags = Set.of(Tag.of(TAG_WORKFLOW_TYPE, workflowType),
                                   Tag.of(TAG_STATUS, failed ? STATUS_FAILED : STATUS_COMPLETED));
            counter("workflow_completions_total", "Total workflow executions completed on this worker", tags)
                    .increment();
            if (durationMillis >= 0) {
                registry().gauge(new MetricId("workflow_duration_seconds",
                                              "Workflow execution duration from run start to completion", tags),
                                 StatisticConfig.DEFAULT)
                        .setValue(durationMillis / 1000.0);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to record workflow completion metric", e);
        }
    }

    /**
     * Records one activity execution attempt on this worker. Activity attempts are never
     * replayed, so every call represents a real execution.
     *
     * @param activityType   the activity type name
     * @param durationMillis execution duration in milliseconds
     * @param failed         whether the attempt failed
     */
    public static void recordActivityExecution(String activityType, long durationMillis, boolean failed) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            Set<Tag> tags = Set.of(Tag.of(TAG_ACTIVITY_TYPE, activityType),
                                   Tag.of(TAG_STATUS, failed ? STATUS_FAILED : STATUS_COMPLETED));
            counter("workflow_activity_executions_total",
                    "Total workflow activity execution attempts on this worker", tags).increment();
            if (durationMillis >= 0) {
                registry().gauge(new MetricId("workflow_activity_duration_seconds",
                                              "Workflow activity execution duration", tags),
                                 StatisticConfig.DEFAULT)
                        .setValue(durationMillis / 1000.0);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to record activity execution metric", e);
        }
    }

    /**
     * The most distinct {@code data_name} tag values given their own series. Declared event
     * names are compile-time constants for declared workflows, but a dynamic {@code sendData}
     * name skips that validation and reaches here as whatever the caller computed — and every
     * distinct tag set is a new series in the registry and the exporter. Past the cap, new
     * names collapse into {@link #OTHER_DATA_NAME}; the counter still counts, the name is the
     * only thing surrendered.
     */
    private static final int MAX_DATA_NAME_SERIES = 64;
    private static final String OTHER_DATA_NAME = "__other__";
    private static final Set<String> SEEN_DATA_NAMES = ConcurrentHashMap.newKeySet();

    /**
     * Records a data event delivered to a running workflow instance by this runtime.
     *
     * @param dataName the declared data/event name the payload was delivered to
     */
    public static void recordDataSent(String dataName) {
        if (!isMetricsEnabled()) {
            return;
        }
        try {
            counter("workflow_data_events_sent_total", "Total data events sent to workflow instances",
                    Set.of(Tag.of(TAG_DATA_NAME, boundedDataName(dataName)))).increment();
        } catch (Exception e) {
            LOGGER.debug("Failed to record data event metric", e);
        }
    }

    /**
     * The tag value for one delivery: the name itself while the distinct-name budget lasts,
     * {@value #OTHER_DATA_NAME} afterwards. The check-then-add race can overshoot the cap by
     * a few concurrent senders; the bound this exists for is "not one series per request",
     * and that holds either way.
     */
    private static String boundedDataName(String dataName) {
        if (SEEN_DATA_NAMES.contains(dataName)) {
            return dataName;
        }
        if (SEEN_DATA_NAMES.size() >= MAX_DATA_NAME_SERIES) {
            return OTHER_DATA_NAME;
        }
        SEEN_DATA_NAMES.add(dataName);
        return dataName;
    }

    private static boolean isMetricsEnabled() {
        return ObserveUtils.isMetricsEnabled() && DefaultMetricRegistry.getInstance() != null;
    }

    private static MetricRegistry registry() {
        return DefaultMetricRegistry.getInstance();
    }

    private static io.ballerina.runtime.observability.metrics.Counter counter(String name, String description,
                                                                              Set<Tag> tags) {
        return registry().counter(new MetricId(name, description, tags));
    }
}
