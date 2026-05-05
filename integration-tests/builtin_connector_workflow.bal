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
// BUILT-IN CONNECTOR ACTIVITIES — workflow tests
// ================================================================================
//
// Verifies that the `ballerina/workflow.activity` builtins (currently
// `callRestAPI`) can be invoked end-to-end through `ctx->callActivity(...)` with
// a module-level `final` connector client passed via the connection-marker
// wiring emitted by the workflow compiler plugin.
//
// The test runs an in-process mock HTTP service on port 9595 and points the
// HTTP client at it.
//
// ================================================================================

import ballerina/http;
import ballerina/workflow;
import ballerina/workflow.activity;

// ================================================================================
// MOCK HTTP SERVICE (in-process)
// ================================================================================

listener http:Listener mockApiListener = new (9595);

service /api on mockApiListener {

    // GET /api/users/1 → {"id": 1, "name": "Alice"}
    resource function get users/[int id]() returns json {
        return {id: id, name: id == 1 ? "Alice" : "Bob"};
    }

    // POST /api/echo → echoes the request body in {"echo": <body>}
    resource function post echo(@http:Payload json payload) returns json {
        return {echo: payload};
    }
}

// Module-level `final` `http:Client` — registered as a workflow connection by
// the compiler plugin via the generated `wfInternal:registerConnection(...)`
// calls in `__registerWorkflowsAndStart()`.
final http:Client mockApi = check new ("http://localhost:9595/api");

// ================================================================================
// TYPES
// ================================================================================

# Input for connector-activity workflows.
#
# + id - Workflow identifier (for log correlation)
# + userId - The user id to fetch
type ConnectorInput record {|
    string id;
    int userId;
|};

# Echo input passed via POST.
#
# + id - Workflow identifier
# + payload - Body to echo
type EchoInput record {|
    string id;
    json payload;
|};

// ================================================================================
// WORKFLOWS
// ================================================================================

# Workflow that uses the builtin `callRestAPI` activity to fetch a user via GET.
#
# + ctx - Workflow context
# + input - Workflow input
# + return - JSON user record on success, error otherwise
@workflow:Workflow
function fetchUserWorkflow(workflow:Context ctx, ConnectorInput input)
        returns json|error {
    json user = check ctx->callActivity(activity:callRestAPI, {
        connection: mockApi,
        method: "GET",
        path: "/users/" + input.userId.toString()
    });
    return user;
}

# Workflow that POSTs a payload through `callRestAPI` and returns the echoed
# response.
#
# + ctx - Workflow context
# + input - Workflow input
# + return - The echoed JSON, or an error
@workflow:Workflow
function echoWorkflow(workflow:Context ctx, EchoInput input)
        returns json|error {
    json result = check ctx->callActivity(activity:callRestAPI, {
        connection: mockApi,
        method: "POST",
        path: "/echo",
        message: input.payload
    });
    return result;
}
