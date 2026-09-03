// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

# Represents a tracing span for starting a workflow instance.
public isolated distinct class StartWorkflowSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string workflowType) {
        self.baseSpan = new (string `${START_WORKFLOW} ${workflowType}`);
        self.baseSpan.addTag(OPERATION_NAME, START_WORKFLOW);
        self.baseSpan.addTag(WORKFLOW_TYPE, workflowType);
    }

    # Records the instance ID assigned to the started workflow.
    #
    # + instanceId - The workflow instance identifier
    public isolated function addInstanceId(string instanceId) {
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for sending data to a running workflow instance.
public isolated distinct class SendDataSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string instanceId, string dataName) {
        self.baseSpan = new (string `${SEND_DATA} ${dataName}`);
        self.baseSpan.addTag(OPERATION_NAME, SEND_DATA);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
        self.baseSpan.addTag(DATA_NAME, dataName);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for waiting on a workflow instance's result.
public isolated distinct class GetWorkflowResultSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string instanceId) {
        self.baseSpan = new (string `${GET_WORKFLOW_RESULT} ${instanceId}`);
        self.baseSpan.addTag(OPERATION_NAME, GET_WORKFLOW_RESULT);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for completing a pending human task.
public isolated distinct class CompleteHumanTaskSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string taskWorkflowId) {
        self.baseSpan = new (string `${COMPLETE_HUMAN_TASK} ${taskWorkflowId}`);
        self.baseSpan.addTag(OPERATION_NAME, COMPLETE_HUMAN_TASK);
        self.baseSpan.addTag(HUMAN_TASK_ID, taskWorkflowId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for starting a durable agent instance.
public isolated distinct class StartAgentSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string agentName) {
        self.baseSpan = new (string `${START_AGENT} ${agentName}`);
        self.baseSpan.addTag(OPERATION_NAME, START_AGENT);
        self.baseSpan.addTag(AGENT_NAME, agentName);
    }

    # Records the instance ID assigned to the started agent.
    #
    # + instanceId - The agent instance identifier
    public isolated function addInstanceId(string instanceId) {
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Represents a tracing span for sending an event to a running durable agent.
public isolated distinct class SendAgentEventSpan {
    *WorkflowSpan;
    private final BaseSpanImp baseSpan;

    isolated function init(string agentName, string instanceId, string eventName) {
        self.baseSpan = new (string `${SEND_AGENT_EVENT} ${eventName}`);
        self.baseSpan.addTag(OPERATION_NAME, SEND_AGENT_EVENT);
        self.baseSpan.addTag(AGENT_NAME, agentName);
        self.baseSpan.addTag(INSTANCE_ID, instanceId);
        self.baseSpan.addTag(EVENT_NAME, eventName);
    }

    # Closes the span and records its final status.
    #
    # + err - Optional error that indicates if the operation failed
    public isolated function close(error? err = ()) {
        self.baseSpan.close(err);
    }
}

# Creates a span representing the start of a workflow instance.
#
# + workflowType - The workflow type name being started
# + return - A `StartWorkflowSpan` instance representing the span
public isolated function createStartWorkflowSpan(string workflowType) returns StartWorkflowSpan {
    return new (workflowType);
}

# Creates a span representing sending data to a workflow instance.
#
# + instanceId - The target workflow instance ID
# + dataName - The events record field the data is sent to
# + return - A `SendDataSpan` instance representing the span
public isolated function createSendDataSpan(string instanceId, string dataName) returns SendDataSpan {
    return new (instanceId, dataName);
}

# Creates a span representing waiting for a workflow instance's result.
#
# + instanceId - The target workflow instance ID
# + return - A `GetWorkflowResultSpan` instance representing the span
public isolated function createGetWorkflowResultSpan(string instanceId) returns GetWorkflowResultSpan {
    return new (instanceId);
}

# Creates a span representing the completion of a pending human task.
#
# + taskWorkflowId - The human task's workflow ID
# + return - A `CompleteHumanTaskSpan` instance representing the span
public isolated function createCompleteHumanTaskSpan(string taskWorkflowId) returns CompleteHumanTaskSpan {
    return new (taskWorkflowId);
}

# Creates a span representing the start of a durable agent instance.
#
# + agentName - The name of the agent being started
# + return - A `StartAgentSpan` instance representing the span
public isolated function createStartAgentSpan(string agentName) returns StartAgentSpan {
    return new (agentName);
}

# Creates a span representing sending an event to a durable agent instance.
#
# + agentName - The name of the target agent
# + instanceId - The agent instance ID
# + eventName - The declared event channel name
# + return - A `SendAgentEventSpan` instance representing the span
public isolated function createSendAgentEventSpan(string agentName, string instanceId,
        string eventName) returns SendAgentEventSpan {
    return new (agentName, instanceId, eventName);
}
