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

import ballerina/email;
import ballerina/http;
import ballerina/jballerina.java;
import ballerina/mime;
import ballerina/soap.soap11;
import ballerina/soap.soap12;
import ballerina/workflow;

# HTTP method supported by the [`callRestAPI`](#callRestAPI) builtin activity.
public type RestMethod "GET"|"POST"|"PUT"|"DELETE"|"PATCH";

# Calls a REST API as a workflow activity.
#
# The `connection` argument must be a module-level `final` `http:Client`
# variable in the user's program. The compiler plugin generates the wiring
# that makes it available on the activity worker side.
#
# Example:
# ```ballerina
# final http:Client api = check new ("https://api.example.com");
#
# type User record {| int id; string name; |};
#
# @workflow:Workflow
# isolated function myWorkflow(workflow:Context ctx) returns error? {
#     User user = check ctx->callActivity(activity:callRestAPI, {
#         connection: api,
#         method: "GET",
#         path: "/users/1"
#     });
# }
# ```
#
# + connection - The HTTP client to use for the call
# + method - HTTP method to invoke
# + path - Resource path appended to the client's base URL
# + message - Request body (any HTTP-compatible payload type)
# + headers - Optional request headers
# + t - Expected payload type (inferred from context). The HTTP client performs
#       data binding into this type.
# + return - Response payload as `t`, or an error
@workflow:Activity
public isolated function callRestAPI(http:Client connection, RestMethod method,
        string path = "",
        http:Request|map<json>|json|xml|string|byte[]? message = (),
        map<string|string[]>? headers = (),
        typedesc<anydata> t = <>) returns t|error = @java:Method {
    'class: "io.ballerina.lib.workflow.activity.BuiltinActivities",
    name: "callRestAPI"
} external;

# Internal dispatcher invoked from the `callRestAPI` external implementation.
# Performs the actual HTTP method dispatch and delegates payload data binding
# to the underlying `http:Client`.
#
# + connection - The HTTP client to use for the call
# + method - HTTP method to invoke
# + path - Resource path appended to the client's base URL
# + message - Request body
# + headers - Optional request headers
# + t - Expected payload type (forwarded as `targetType` to the client)
# + return - Response payload bound to `t`, or an error
isolated function callRestAPIDispatch(http:Client connection, RestMethod method,
        string path,
        http:Request|map<json>|json|xml|string|byte[]? message,
        map<string|string[]>? headers,
        typedesc<anydata> t) returns anydata|error {
    match method {
        "GET" => {
            return connection->get(path, headers, targetType = t);
        }
        "POST" => {
            return connection->post(path, message, headers, targetType = t);
        }
        "PUT" => {
            return connection->put(path, message, headers, targetType = t);
        }
        "DELETE" => {
            return connection->delete(path, message, headers, targetType = t);
        }
        "PATCH" => {
            return connection->patch(path, message, headers, targetType = t);
        }
    }
    return error("Unsupported REST method: " + method);
}

# Sends a plain-text email as a workflow activity.
#
# The `connection` argument must be a module-level `final` `email:SmtpClient`
# variable in the user's program. The activity composes a simple text
# `email:Message` from the supplied parameters and submits it.
#
# Workflows that need rich email features (HTML body, attachments, custom
# headers, etc.) should write their own `@workflow:Activity` function that
# accepts the relevant `email:SmtpClient` and constructs the `email:Message`
# inline.
#
# + connection - The SMTP client to use for sending
# + to - Recipient address (or list of addresses)
# + subject - Subject line of the email
# + body - Plain-text body of the email
# + 'from - Optional `From` address; if omitted, the SMTP client default applies
# + cc - Optional CC recipient(s)
# + bcc - Optional BCC recipient(s)
# + return - An error if sending fails, otherwise `()`
@workflow:Activity
public isolated function sendEmail(email:SmtpClient connection, string|string[] to,
        string subject, string body, string? 'from = (),
        string|string[]? cc = (), string|string[]? bcc = ()) returns error? {
    email:Message message = {
        to: to,
        subject: subject,
        body: body
    };
    if 'from is string {
        message.'from = 'from;
    }
    if cc !is () {
        message.cc = cc;
    }
    if bcc !is () {
        message.bcc = bcc;
    }
    return connection->sendMessage(message);
}

# Calls a SOAP endpoint as a workflow activity.
#
# The `connection` argument must be a module-level `final` `soap11:Client` or
# `soap12:Client` variable in the user's program. Both SOAP 1.1 and 1.2 clients
# are supported. The `action` parameter is required for SOAP 1.1 and optional
# for SOAP 1.2.
#
# Example:
# ```ballerina
# final soap11:Client calc = check new ("https://calc.example.com/svc?WSDL");
#
# @workflow:Workflow
# isolated function addNumbers(workflow:Context ctx) returns error? {
#     xml envelope = xml `<soap:Envelope ...><soap:Body>...</soap:Body></soap:Envelope>`;
#     xml response = check ctx->callActivity(activity:callSoapAPI, {
#         connection: calc,
#         body: envelope,
#         action: "http://tempuri.org/Add"
#     });
# }
# ```
#
# + connection - The SOAP client (`soap11:Client` or `soap12:Client`) to use
# + body - SOAP envelope as `xml`
# + action - SOAP action header. Required for SOAP 1.1, optional for SOAP 1.2
# + headers - Additional HTTP headers
# + path - Optional resource path appended to the client's base URL
# + return - The SOAP response envelope as `xml`, or an error.
#           Workflows that need to consume multipart `mime:Entity[]` responses
#           should write a custom `@workflow:Activity` that wraps the
#           appropriate SOAP client.
@workflow:Activity
public isolated function callSoapAPI(soap11:Client|soap12:Client connection,
        xml body, string? action = (),
        map<string|string[]> headers = {}, string path = "")
        returns xml|error {
    if connection is soap11:Client {
        return invokeSoap11(connection, body, action, headers, path);
    }
    if connection is soap12:Client {
        return invokeSoap12(connection, body, action, headers, path);
    }
    return error("Unsupported SOAP client type");
}

isolated function invokeSoap11(soap11:Client connection, xml body,
        string? action, map<string|string[]> headers, string path)
        returns xml|error {
    if action is () {
        return error("SOAP 1.1 requires the 'action' parameter.");
    }
    xml|mime:Entity[] response = check connection->sendReceive(body, action, headers, path);
    if response is xml {
        return response;
    }
    return error("SOAP 1.1 response was a multipart message; "
            + "use a custom activity to consume mime:Entity[] payloads.");
}

isolated function invokeSoap12(soap12:Client connection, xml body,
        string? action, map<string|string[]> headers, string path)
        returns xml|error {
    xml|mime:Entity[] response = check connection->sendReceive(body, action, headers, path);
    if response is xml {
        return response;
    }
    return error("SOAP 1.2 response was a multipart message; "
            + "use a custom activity to consume mime:Entity[] payloads.");
}
