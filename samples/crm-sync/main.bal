// Copyright (c) 2025 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/workflow;
import ballerina/io;

// HTTP Service for CRM Contact Sync
// Simulates webhook events from source CRM

// HTTP service on port 9091
service /sync on new http:Listener(9091) {

    # Trigger contact sync workflow
    # POST /sync/contact
    # Body: {"id": "...", "email": "...", "firstName": "...", "lastName": "...", "phone": "...", "company": "..."}
    #
    # + contact - Source contact data to sync
    # + return - Success response with workflow ID or error
    resource function post contact(SourceContact contact) returns json|error {
        // Start workflow using the @workflow:Process function
        string workflowId = check workflow:createInstance(syncContact, contact);

        io:println(string `Started CRM sync workflow: ${workflowId} for contact: ${contact.email}`);

        return {
            "status": "success",
            "workflowId": workflowId,
            "contactId": contact.id,
            "message": "Contact sync started"
        };
    }

    # Get workflow result
    # GET /sync/{workflowId}/result
    #
    # + workflowId - The workflow execution ID
    # + return - Workflow execution result or error
    resource function get [string workflowId]/result() returns json|error {
        workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
        
        json result = check execInfo.result.cloneWithType(json);
        return {
            workflowId: workflowId,
            status: execInfo.status.toString(),
            result: result
        };
    }

    # Health check
    # GET /sync/health
    #
    # + return - Service health status message
    resource function get health() returns string {
        return "CRM Sync Service is running";
    }

    # Reset CRM data (for testing)
    # POST /sync/reset
    #
    # + return - Success message
    resource function post reset() returns string {
        resetCrmData();
        return "CRM data reset successfully";
    }
}

# Module initialization
#
# + return - Error if initialization fails
public function main() returns error? {
    io:println("Starting CRM Sync Sample...");
    io:println("Worker started. HTTP Service listening on http://localhost:9091/sync");
    io:println("Test with: curl -X POST http://localhost:9091/sync/contact -H 'Content-Type: application/json' -d '{\"id\":\"CONT-001\",\"email\":\"user@example.com\",\"firstName\":\"John\",\"lastName\":\"Doe\",\"phone\":\"+1234567890\",\"company\":\"ACME Corp\"}'");
}
