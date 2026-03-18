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

// ================================================================================
// HTTP ACTIVITY WORKFLOWS
// ================================================================================
// 
// Workflows that use callRemoteActivity and callResourceActivity to invoke
// methods on an HTTP client. These verify that client-based activity
// invocations work correctly through the Temporal workflow engine.
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// WORKFLOW INPUT TYPE
// ================================================================================

# Input for HTTP activity workflows.
#
# + id - The workflow identifier
type HttpActivityInput record {|
    string id;
|};

# Input for HTTP POST workflow.
#
# + id - The workflow identifier
# + name - The user name
# + email - The user email
type HttpPostInput record {|
    string id;
    string name;
    string email;
|};

// ================================================================================
// WORKFLOW DEFINITIONS - callRemoteActivity
// ================================================================================

# Workflow that uses callRemoteActivity to POST data via an HTTP client.
# The HTTP client's `post` remote method is called as an activity.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - The response JSON or error
@workflow:Workflow
function httpRemotePostWorkflow(workflow:Context ctx, HttpPostInput input) returns json|error {
    SimpleApiClient apiClient = check new (testServiceUrl);

    json payload = {id: input.id, name: input.name, email: input.email};

    json response = check ctx->callRemoteActivity(apiClient, "post",
        {"path": "/api/users", "message": payload});

    return response;
}

// ================================================================================
// WORKFLOW DEFINITIONS - callResourceActivity
// ================================================================================

# Workflow that uses callResourceActivity to GET data via resource method.
# The SimpleApiClient's `get api/users` resource method is called as an activity.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - The response JSON or error
@workflow:Workflow
function httpResourceGetWorkflow(workflow:Context ctx, HttpActivityInput input) returns json|error {
    SimpleApiClient apiClient = check new (testServiceUrl);

    json users = check ctx->callResourceActivity(apiClient, "get", "/api/users");

    return users;
}

# Workflow that uses callResourceActivity to POST data via resource method.
# The SimpleApiClient's `post api/echo` resource method is called as an activity.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - The echoed JSON or error
@workflow:Workflow
function httpResourcePostWorkflow(workflow:Context ctx, HttpPostInput input) returns json|error {
    SimpleApiClient apiClient = check new (testServiceUrl);

    json payload = {name: input.name, email: input.email};

    json response = check ctx->callResourceActivity(apiClient, "post", "/api/echo",
        {"payload": payload});

    return response;
}

# Workflow that uses callResourceActivity to GET a greeting via resource method.
# The SimpleApiClient's `get api/greet` resource method is called with a name arg.
#
# + ctx - The workflow context
# + input - The workflow input
# + return - The greeting string or error
@workflow:Workflow
function httpResourceGreetWorkflow(workflow:Context ctx, HttpPostInput input) returns string|error {
    SimpleApiClient apiClient = check new (testServiceUrl);

    string greeting = check ctx->callResourceActivity(apiClient, "get", "/api/greet",
        {"name": input.name});

    return greeting;
}
