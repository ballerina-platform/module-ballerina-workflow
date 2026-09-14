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

import static io.ballerina.lib.workflow.observability.WorkflowMetrics.NONE;

// One completed step of a durable agent's loop: one workflow_events_total increment under its own event value,
// one workflow_agent_step_duration_seconds observation and one agent.* sample. Recorded replay-gated at step end.
public record AgentStep(String event, String workflowType, String activityType, String toolName, String dataName,
                        String taskKind, String taskName, String action, long durationMillis, String errorType) {

    public static final String EVENT_MODEL_CALLED = "agent_model_called";
    public static final String EVENT_TOOL_CALLED = "agent_tool_called";
    public static final String EVENT_TASK_AWAITED = "agent_task_awaited";
    public static final String EVENT_EVENT_RECEIVED = "agent_event_received";
    public static final String EVENT_SLEPT = "agent_slept";
    public static final String EVENT_TOOL_REVIEWED = "agent_tool_reviewed";

    // The wait ran out before the event arrived.
    public static final String ERROR_EVENT_TIMEOUT = "TIMEOUT";
    // The agent hit its maxEventWaits safety cap.
    public static final String ERROR_EVENT_WAIT_CAP = "MAX_EVENT_WAITS";

    public static final String ACTION_COMPLETED = "completed";
    public static final String ACTION_INTERRUPTED = "interrupted";

    private static final String EVENT_PREFIX = "agent_";
    private static final String TASK_KIND_HUMAN_TASK = "HUMAN_TASK";
    private static final String TASK_KIND_REVIEW = "REVIEW_ACTIVITY";

    public AgentStep {
        activityType = orNone(activityType);
        toolName = orNone(toolName);
        dataName = orNone(dataName);
        taskKind = orNone(taskKind);
        taskName = orNone(taskName);
        action = orNone(action);
        errorType = orNone(errorType);
    }

    // A built-in model activity (llmChat, generate, generateResult) finished.
    public static AgentStep modelCall(String workflowType, String activityType, long durationMillis,
                                      String errorType) {
        return new AgentStep(EVENT_MODEL_CALLED, workflowType, activityType, null, null, null, null, null,
                             durationMillis, errorType);
    }

    // A tool the model called finished: an activity tool by its advertised name, or an AI tool via executeAgentTool.
    public static AgentStep toolCall(String workflowType, String activityType, String toolName, long durationMillis,
                                     String errorType) {
        return new AgentStep(EVENT_TOOL_CALLED, workflowType, activityType, toolName, null, null, null, null,
                             durationMillis, errorType);
    }

    // A human task the agent created was decided; the duration is creation to completion as the agent saw it.
    public static AgentStep taskAwaited(String workflowType, String toolName, String taskName, long durationMillis,
                                        String errorType) {
        return new AgentStep(EVENT_TASK_AWAITED, workflowType, null, toolName, null, TASK_KIND_HUMAN_TASK, taskName,
                             null, durationMillis, errorType);
    }

    // An event wait ended: the event arrived, the wait timed out, or the agent hit its wait cap.
    public static AgentStep eventReceived(String workflowType, String eventName, long durationMillis,
                                          String errorType) {
        return new AgentStep(EVENT_EVENT_RECEIVED, workflowType, null, null, eventName, null, null, null,
                             durationMillis, errorType);
    }

    // The built-in sleep tool returned, after the full duration or early on a wake signal.
    public static AgentStep slept(String workflowType, boolean interrupted, long durationMillis) {
        return new AgentStep(EVENT_SLEPT, workflowType, null, null, null, null, null,
                             interrupted ? ACTION_INTERRUPTED : ACTION_COMPLETED, durationMillis, null);
    }

    // A person decided on a gated tool call before it ran; action is the decision.
    public static AgentStep toolReviewed(String workflowType, String toolName, String taskName, String action,
                                         long durationMillis) {
        return new AgentStep(EVENT_TOOL_REVIEWED, workflowType, null, toolName, null, TASK_KIND_REVIEW, taskName,
                             action, durationMillis, null);
    }

    public boolean failed() {
        return !NONE.equals(errorType);
    }

    // The sample name of this step's log record: agent.<event without its prefix>.
    public String sampleName() {
        return "agent." + event.substring(EVENT_PREFIX.length());
    }

    private static String orNone(String value) {
        return value == null || value.isEmpty() ? NONE : value;
    }
}
