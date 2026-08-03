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

import ballerina/ai;
import ballerina/http;
import ballerina/workflow;

final ai:Wso2ModelProvider chatModel = check new ("http://localhost:9099", "test-token");
final http:Client apiClient = check new ("http://localhost:8080");

// A connection-based activity: the model cannot supply the client, so it can only be
// exposed through registration-time bindings.
@workflow:Activity
isolated function httpGet(http:Client connection, string path) returns json|error {
    return connection->get(path);
}

@workflow:Activity
isolated function lookupOrder(string id) returns string {
    return "order " + id;
}

// A rest parameter is filled by the model too, so a non-data one is just as unusable.
@workflow:Activity
isolated function broadcast(string message, http:Client... targets) returns error? {
    return;
}

// ERROR x5: every entry leaves a parameter the model cannot supply — the bare reference and
// the ActivityDecl entry expose the client, empty bindings supply nothing, the rest parameter
// is non-data, and binding only the data parameter still leaves the rest parameter unbound.
// The fully bound entry and the data-only activity pass.
final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    activities: [
        httpGet,
        {activity: httpGet, name: "fetch"},
        // Binding the client at registration is the supported form, so this entry passes.
        {activity: httpGet, name: "bound", bindings: {connection: apiClient}},
        {activity: httpGet, name: "empty", bindings: {}},
        broadcast,
        {activity: broadcast, name: "partial", bindings: {message: "hi"}},
        lookupOrder
    ]
});
