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
// HTTP TEST SERVICE
// ================================================================================
// 
// A local HTTP service used for testing callRemoteActivity and
// callResourceActivity. Provides simple JSON endpoints for
// GET, POST, PUT, and DELETE operations.
//
// ================================================================================

import ballerina/http;

// The port for the test HTTP service
const int TEST_HTTP_PORT = 9876;

// Base URL for the test HTTP service
final string testServiceUrl = string `http://localhost:${TEST_HTTP_PORT}`;

// In-memory store for test data
type UserRecord record {|
    string id;
    string name;
    string email;
|};

// ================================================================================
// HTTP SERVICE DEFINITION
// ================================================================================

listener http:Listener testListener = new (TEST_HTTP_PORT);

service /api on testListener {

    // GET /api/users - Returns a list of users
    resource function get users() returns UserRecord[] {
        return [
            {id: "1", name: "Alice", email: "alice@example.com"},
            {id: "2", name: "Bob", email: "bob@example.com"}
        ];
    }

    // GET /api/users/[id] - Returns a single user by ID
    resource function get users/[string id]() returns UserRecord|http:NotFound {
        if id == "1" {
            return {id: "1", name: "Alice", email: "alice@example.com"};
        }
        if id == "2" {
            return {id: "2", name: "Bob", email: "bob@example.com"};
        }
        return http:NOT_FOUND;
    }

    // POST /api/users - Creates a user and returns it
    resource function post users(UserRecord payload) returns UserRecord {
        return payload;
    }

    // GET /api/greet?name=X - Returns a greeting message
    resource function get greet(string name = "World") returns string {
        return "Hello, " + name + "!";
    }

    // POST /api/echo - Echoes back the JSON payload
    resource function post echo(@http:Payload json payload) returns json {
        return payload;
    }
}

// ================================================================================
// SIMPLE API CLIENT FOR RESOURCE ACTIVITY TESTS
// ================================================================================
// 
// A thin client with fixed-path resource methods that wraps http:Client.
// Unlike http:Client (which uses rest path parameters), this client has
// concrete resource method names (e.g. $get$api$users) that the workflow
// adapter can look up via ObjectType.getMethods() for correct arg ordering.
//
// ================================================================================

client class SimpleApiClient {
    private final http:Client httpClient;

    function init(string url) returns error? {
        self.httpClient = check new (url);
    }

    // Remote methods (for callRemoteActivity tests)
    remote function post(string path, json message) returns json|error {
        http:Response response = check self.httpClient->post(path, message);
        return response.getJsonPayload();
    }

    remote function get(string path) returns json|error {
        http:Response response = check self.httpClient->get(path);
        return response.getJsonPayload();
    }

    // Resource methods (for callResourceActivity tests)
    resource function get api/users() returns json|error {
        http:Response response = check self.httpClient->get("/api/users");
        return response.getJsonPayload();
    }

    resource function post api/users(json payload) returns json|error {
        http:Response response = check self.httpClient->post("/api/users", payload);
        return response.getJsonPayload();
    }

    resource function post api/echo(json payload) returns json|error {
        http:Response response = check self.httpClient->post("/api/echo", payload);
        return response.getJsonPayload();
    }

    resource function get api/greet(string name) returns string|error {
        http:Response response = check self.httpClient->get("/api/greet?name=" + name);
        return response.getTextPayload();
    }
}
