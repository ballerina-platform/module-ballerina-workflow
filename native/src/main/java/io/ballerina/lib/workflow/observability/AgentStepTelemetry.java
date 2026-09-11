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

import io.opentelemetry.api.trace.Span;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

// Records a durable agent's steps from the workflow thread — metric, sample and span — skipping replays.
public final class AgentStepTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentStepTelemetry.class);

    private AgentStepTelemetry() {
    }

    // Opens the step's span under the run's trace; null under replay, so a step this worker did not see
    // from its start gets a span of its own at completion instead.
    public static Span begin(String operation) {
        try {
            if (Workflow.isReplaying()) {
                return null;
            }
            return WorkerSpans.begin(operation, WorkerSpans.runTags(Workflow.getInfo()));
        } catch (Exception e) {
            LOGGER.debug("Failed to open agent step span '{}'", operation, e);
            return null;
        }
    }

    // Records one completed step; a no-op under replay. Must run on the workflow thread.
    public static void record(AgentStep step, Span span) {
        try {
            if (Workflow.isReplaying()) {
                return;
            }
            WorkflowInfo info = Workflow.getInfo();
            WorkflowMetrics.recordAgentStep(step);
            WorkflowSampleLog.agentStep(step, info.getWorkflowId(), info.getRunId());
            Map<String, String> tags = WorkerSpans.runTags(info);
            tags.putAll(stepTags(step));
            Throwable failure = step.failed() ? new AgentStepFailure(step.errorType()) : null;
            if (span != null) {
                WorkerSpans.tag(span, tags);
                WorkerSpans.end(span, failure);
            } else {
                WorkerSpans.point(step.sampleName(), tags, failure);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to record agent step '{}'", step.event(), e);
        }
    }

    private static Map<String, String> stepTags(AgentStep step) {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("workflow.agent.step", step.event());
        if (!WorkflowMetrics.NONE.equals(step.activityType())) {
            tags.put("workflow.activity.type", step.activityType());
        }
        if (!WorkflowMetrics.NONE.equals(step.toolName())) {
            tags.put("workflow.agent.tool", step.toolName());
        }
        if (!WorkflowMetrics.NONE.equals(step.dataName())) {
            tags.put("workflow.data.name", step.dataName());
        }
        if (!WorkflowMetrics.NONE.equals(step.taskName())) {
            tags.put("workflow.task.name", step.taskName());
        }
        if (!WorkflowMetrics.NONE.equals(step.action())) {
            tags.put("workflow.task.action", step.action());
        }
        tags.put("workflow.step.duration.seconds", String.valueOf(step.durationMillis() / 1000.0));
        return tags;
    }

    // A step's failure as the span sees it: the type the step reported, no stack.
    private static final class AgentStepFailure extends Exception {
        private final String type;

        AgentStepFailure(String type) {
            super(type, null, false, false);
            this.type = type;
        }

        @Override
        public String toString() {
            return type;
        }
    }
}
