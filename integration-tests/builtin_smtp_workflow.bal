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
// BUILT-IN SMTP CONNECTOR ACTIVITY — workflow tests
// ================================================================================
//
// Verifies that the `ballerina/workflow.activity:sendEmail` builtin can be
// invoked end-to-end through `ctx->callActivity(...)` against an in-process
// GreenMail SMTP server (started/stopped via Java interop). A module-level
// `final email:SmtpClient` is registered as a workflow connection by the
// compiler plugin via the generated `wfInternal:registerConnection(...)` call.
//
// GreenMail is bundled as a transitive dependency of `ballerina/email` so it
// is already present on the runtime classpath.
//
// ================================================================================

import ballerina/email;
import ballerina/jballerina.java;
import ballerina/workflow;
import ballerina/workflow.activity;

// --------------------------------------------------------------------------------
// GreenMail Java interop (SMTP test fixture)
// --------------------------------------------------------------------------------

# Opaque handle to an embedded GreenMail SMTP server.
public type GreenMail handle;

# SMTP port used by the in-process GreenMail server.
public const int SMTP_TEST_PORT = 3025;

# Constructs a `ServerSetup` configured for SMTP on the given port.
isolated function newServerSetup(int port, handle bindAddress, handle protocol)
        returns handle = @java:Constructor {
    'class: "com.icegreen.greenmail.util.ServerSetup",
    paramTypes: ["int", "java.lang.String", "java.lang.String"]
} external;

# Constructs a `GreenMail` instance with a single `ServerSetup`.
isolated function newGreenMail(handle setup) returns GreenMail = @java:Constructor {
    'class: "com.icegreen.greenmail.util.GreenMail",
    paramTypes: ["com.icegreen.greenmail.util.ServerSetup"]
} external;

# Starts the embedded server.
isolated function gmStart(handle gm) = @java:Method {
    name: "start",
    'class: "com.icegreen.greenmail.util.GreenMail",
    paramTypes: []
} external;

# Stops the embedded server.
isolated function gmStop(handle gm) = @java:Method {
    name: "stop",
    'class: "com.icegreen.greenmail.util.GreenMail",
    paramTypes: []
} external;

# Provisions a user mailbox so the SMTP server accepts authenticated submissions.
isolated function gmSetUser(handle gm, handle email, handle login, handle password)
        returns handle = @java:Method {
    name: "setUser",
    'class: "com.icegreen.greenmail.util.GreenMail",
    paramTypes: ["java.lang.String", "java.lang.String", "java.lang.String"]
} external;

# Returns a handle to the received SMTP messages array.
isolated function gmReceivedMessages(handle gm) returns handle = @java:Method {
    name: "getReceivedMessages",
    'class: "com.icegreen.greenmail.util.GreenMail",
    paramTypes: []
} external;

# Returns the length of a Java array referenced by the given handle.
isolated function arrayLength(handle array) returns int = @java:Method {
    name: "getLength",
    'class: "java.lang.reflect.Array",
    paramTypes: ["java.lang.Object"]
} external;

# Returns the count of received SMTP messages.
public isolated function gmReceivedCount(GreenMail gm) returns int {
    return arrayLength(gmReceivedMessages(gm));
}

# Convenience: starts a GreenMail SMTP server on the configured test port and
# provisions a single user.
public isolated function startSmtpFixture() returns GreenMail|error {
    handle setup = newServerSetup(SMTP_TEST_PORT, java:fromString("127.0.0.1"),
            java:fromString("smtp"));
    GreenMail gm = newGreenMail(setup);
    gmStart(gm);
    _ = gmSetUser(gm, java:fromString("recipient@example.com"),
            java:fromString("recipient"), java:fromString("secret"));
    _ = gmSetUser(gm, java:fromString("sender@example.com"),
            java:fromString("sender"), java:fromString("senderpw"));
    return gm;
}

// --------------------------------------------------------------------------------
// SMTP CLIENT REGISTERED AS WORKFLOW CONNECTION
// --------------------------------------------------------------------------------

# Module-level `final` `email:SmtpClient` — registered as a workflow connection
# by the compiler plugin. Points at the in-process GreenMail SMTP server.
final email:SmtpClient mockSmtp = check new ("127.0.0.1", "sender", "senderpw", {
    port: SMTP_TEST_PORT,
    security: email:START_TLS_NEVER
});

// --------------------------------------------------------------------------------
// TYPES
// --------------------------------------------------------------------------------

# Input for the email demo workflow.
#
# + id - Workflow identifier
# + recipient - Address to deliver the message to
# + subject - Email subject line
# + body - Plain-text body of the email
type EmailInput record {|
    string id;
    string recipient;
    string subject;
    string body;
|};

// --------------------------------------------------------------------------------
// WORKFLOW
// --------------------------------------------------------------------------------

# Workflow that uses the builtin `sendEmail` activity to deliver a plain-text
# email through the GreenMail SMTP fixture.
#
# + ctx - Workflow context
# + input - Workflow input
# + return - `()` on success, error otherwise
@workflow:Workflow
function sendEmailWorkflow(workflow:Context ctx, EmailInput input)
        returns error? {
    return ctx->callActivity(activity:sendEmail, {
        connection: mockSmtp,
        to: input.recipient,
        subject: input.subject,
        body: input.body,
        'from: "sender@example.com"
    });
}
