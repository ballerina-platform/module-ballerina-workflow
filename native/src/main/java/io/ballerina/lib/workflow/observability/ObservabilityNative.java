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
import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.workflow.Workflow;

import java.util.LinkedHashMap;
import java.util.Map;

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

    // Opens a client-side span recording; the span itself is built when it closes, in the instance's trace.
    public static long beginClientSpan(Environment env) {
        return ClientSpans.begin(env);
    }

    // Records the span `beginClientSpan` opened. An empty errorType means the call succeeded.
    public static void endClientSpan(long spanId, BString name, BMap<BString, Object> tags, BString instanceId,
                                     BString errorType, BString errorMessage) {
        Map<String, String> javaTags = new LinkedHashMap<>();
        for (Map.Entry<BString, Object> tag : tags.entrySet()) {
            javaTags.put(tag.getKey().getValue(), String.valueOf(tag.getValue()));
        }
        ClientSpans.end(spanId, name.getValue(), javaTags, instanceId.getValue(), errorType.getValue(),
                        errorMessage.getValue());
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
