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

// HTTP Service for Order Processing
// Provides REST endpoints to trigger workflows

// HTTP service on port 9090
service /orders on new http:Listener(9090) {

    # Start an order processing workflow
    # POST /orders
    # Body: {"orderId": "ORD-001", "item": "laptop", "quantity": 2}
    resource function post .(OrderRequest request) returns json|error {
        // Start workflow using the @workflow:Process function
        string workflowId = check workflow:createInstance(processOrder, request);

        io:println(string `Started order processing workflow: ${workflowId}`);

        return {
            "status": "success",
            "workflowId": workflowId,
            "orderId": request.orderId,
            "message": "Order processing started"
        };
    }

    # Get workflow result
    # GET /orders/{workflowId}/result
    resource function get [string workflowId]/result() returns json|error {
        workflow:WorkflowExecutionInfo execInfo = check workflow:getWorkflowResult(workflowId, 30);
        
        json result = check execInfo.result.cloneWithType(json);
        return {
            workflowId: workflowId,
            status: execInfo.status.toString(),
            result: result
        };
    }

    # Health check endpoint
    # GET /orders/health
    resource function get health() returns string {
        return "Order Processing Service is running";
    }
}

# Module initialization
# Start worker before handling requests
public function main() returns error? {
    io:println("Starting Order Processing Sample...");
    io:println("Worker started. HTTP Service listening on http://localhost:9090/orders");
    io:println("Test with: curl -X POST http://localhost:9090/orders -H 'Content-Type: application/json' -d '{\"orderId\":\"ORD-001\",\"item\":\"laptop\",\"quantity\":2}'");
}
