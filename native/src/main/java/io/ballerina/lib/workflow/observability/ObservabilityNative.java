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
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.workflow.Workflow;

// Natives backing the workflow.observe Ballerina submodule.
public final class ObservabilityNative {

    // Set once at module init from the workflow.observe configurables; volatile because activity threads read them.
    private static volatile boolean activityContentCaptured = true;
    private static volatile boolean metricSamplesPublished = true;

    private ObservabilityNative() {
    }

    // Records the worker-side switches from the workflow.observe configurables.
    public static void configure(boolean activityContent, boolean metricSamples) {
        activityContentCaptured = activityContent;
        metricSamplesPublished = metricSamples;
    }

    public static boolean areMetricSamplesPublished() {
        return metricSamplesPublished;
    }

    public static boolean isActivityContentCaptured() {
        return activityContentCaptured;
    }

    // Counts one task decision (the Ballerina side owns its audit entry and span); taskName is "none" if unresolved.
    public static void recordTaskDecisionMetric(BString taskKind, BString taskName, BString action,
                                                boolean accepted, BString errorType) {
        WorkflowMetrics.recordTaskDecision(taskKind.getValue(), taskName.getValue(), action.getValue(),
                                           accepted, errorType.getValue());
    }

    // Identity tags every span carries: module, caller side, engine endpoint, task queue, host.
    public static BMap<BString, Object> spanIdentityTags() {
        BMap<BString, Object> tags = ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_STRING));
        tags.put(StringUtils.fromString("module"), StringUtils.fromString("workflow"));
        tags.put(StringUtils.fromString("type"), StringUtils.fromString("client"));
        String url = WorkflowWorkerNative.getServerUrl();
        if (url != null && !url.isEmpty()) {
            tags.put(StringUtils.fromString("remote.url"), StringUtils.fromString(url));
        }
        String queue = WorkflowWorkerNative.getTaskQueue();
        if (queue != null && !queue.isEmpty()) {
            tags.put(StringUtils.fromString("task.queue"), StringUtils.fromString(queue));
        }
        tags.put(StringUtils.fromString("host"), StringUtils.fromString(hostName()));
        return tags;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "none";
        }
    }

    // Whether the current thread runs inside a workflow context; spans are suppressed there because bodies replay.
    public static boolean isInsideWorkflowContext() {
        try {
            Workflow.getInfo();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // The workflow type name the engine registers for a workflow function.
    public static BString workflowTypeNameOf(BFunctionPointer processFunction) {
        String functionName = processFunction.getType().getName();
        return StringUtils.fromString(
                WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + (functionName == null ? "" : functionName));
    }
}
