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
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.metrics.MetricRegistry;
import io.ballerina.runtime.observability.metrics.noop.NoOpMetricProvider;
import io.temporal.failure.ApplicationFailure;

// Test-only natives: drive the metric recorders through their registry seams against a no-op registry,
// since a multi-module test run cannot enable metrics for real.
public final class ObservabilityTestNatives {

    private ObservabilityTestNatives() {
    }

    // Drives every recorder through success, failure, filtered and unmeasured shapes; returns the error types seen.
    public static BArray exerciseMetricRecorders() {
        MetricRegistry registry = new MetricRegistry(new NoOpMetricProvider());

        WorkflowMetrics.recordWorkflowStarted(registry, "workflow-exercised");
        WorkflowMetrics.recordWorkflowClosed(registry, "workflow-exercised", 1200, null);
        WorkflowMetrics.recordWorkflowStarted(registry, "humantask-exercisedFlow.approve");
        WorkflowMetrics.recordWorkflowClosed(registry, "humantask-exercisedFlow.approve", 3600, null);
        WorkflowMetrics.recordWorkflowClosed(registry, "reviewactivity-exercisedFlow.step", 60,
                                             ApplicationFailure.newFailure("rejected", "HUMANTASK_REJECTED"));
        ApplicationFailure typedFailure = ApplicationFailure.newFailure("boom", "ExercisedFailure");
        WorkflowMetrics.recordWorkflowClosed(registry, "workflow-exercised", -1, typedFailure);
        WorkflowMetrics.recordActivityExecution(registry, "exercisedStep", "workflow-exercised", 5, null);
        WorkflowMetrics.recordActivityExecution(registry, "exercisedStep", "workflow-exercised", 5,
                                                new IllegalStateException("attempt failed"));
        WorkflowMetrics.recordDataSent(registry, "exercisedEvent", null);
        WorkflowMetrics.recordDataSent(registry, "exercisedEvent", new RuntimeException("gone"));
        WorkflowMetrics.recordTaskDecision(registry, "HUMAN_TASK", "exercised.approve", "complete", true, "");
        WorkflowMetrics.recordTaskDecision(registry, "HUMAN_TASK", "none", "complete", false, "error");
        WorkflowMetrics.recordControl(registry, WorkflowMetrics.EVENT_SUSPENDED, null);
        WorkflowMetrics.recordControl(registry, WorkflowMetrics.EVENT_TERMINATED, "WorkflowNotFound");
        for (AgentStep step : exercisedAgentSteps()) {
            WorkflowMetrics.recordAgentStep(registry, step);
        }

        return StringUtils.fromStringArray(new String[] {
                WorkflowMetrics.errorTypeOf(null),
                WorkflowMetrics.errorTypeOf(typedFailure),
                WorkflowMetrics.errorTypeOf(new IllegalStateException("attempt failed"))
        });
    }

    // Describes each agent step as sample|event|outcome|error_type|task_kind|action|tool_name|data_name|task_name.
    public static BArray describeAgentSteps() {
        AgentStep[] steps = exercisedAgentSteps();
        String[] described = new String[steps.length];
        for (int i = 0; i < steps.length; i++) {
            AgentStep step = steps[i];
            described[i] = String.join("|", step.sampleName(), step.event(), step.failed() ? "failure" : "success",
                                       step.errorType(), step.taskKind(), step.action(), step.toolName(),
                                       step.dataName(), step.taskName());
        }
        return StringUtils.fromStringArray(described);
    }

    private static AgentStep[] exercisedAgentSteps() {
        return new AgentStep[] {
                AgentStep.modelCall("exercisedAgent", "llmChat", 800, null),
                AgentStep.toolCall("exercisedAgent", "checkStock", "checkStock", 40, null),
                AgentStep.toolCall("exercisedAgent", "executeAgentTool", "quote", 40, "error"),
                AgentStep.taskAwaited("exercisedAgent", "signoff", "exercisedAgent.signoff", 90000,
                                      WorkflowWorkerNative.HUMANTASK_REJECTED_FAILURE_TYPE),
                AgentStep.eventReceived("exercisedAgent", "chat", 2000, null),
                AgentStep.eventReceived("exercisedAgent", "approval", 2000, AgentStep.ERROR_EVENT_TIMEOUT),
                AgentStep.slept("exercisedAgent", false, 1000),
                AgentStep.slept("exercisedAgent", true, 250),
                AgentStep.toolReviewed("exercisedAgent", "chargeCard", "exercisedAgent.chargeCard", "proceed", 5000),
        };
    }

    // The task kind and task name a workflow type resolves to.
    public static BArray deriveTaskDimensions(BString workflowType) {
        String type = workflowType.getValue();
        return StringUtils.fromStringArray(new String[] {
                WorkflowMetrics.taskKindOf(type),
                WorkflowMetrics.taskNameOf(type)
        });
    }

    // Admits more distinct data names than the series budget and reports what each resolved to.
    public static BArray exerciseBoundedDataNames(long count) {
        BArray bounded = ValueCreator.createArrayValue(
                io.ballerina.runtime.api.creators.TypeCreator.createArrayType(
                        io.ballerina.runtime.api.types.PredefinedTypes.TYPE_STRING));
        for (long i = 0; i < count; i++) {
            BString value = StringUtils.fromString(
                    WorkflowMetrics.boundedDataName("exercised-name-" + i));
            bounded.append(value);
        }
        return bounded;
    }
}
