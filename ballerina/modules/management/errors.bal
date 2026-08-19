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
// MANAGEMENT ERRORS
// ================================================================================
// Why an operation could not be carried out, described in terms of the management
// domain rather than any transport. Every error carries a machine-readable,
// protocol-independent reason (`ErrorCode`, via `errorCodeOf`); each transport
// adapter maps those reasons to its own vocabulary — the HTTP API in
// `workflow.management.rest` maps them to status codes, and other consumers map
// them to whatever their protocol uses. The module itself stays free of transport
// and vendor concepts.

# A management operation failed. Every error returned by an operation belongs to
# one of the distinct subtypes below, so consumers can branch on the reason
# without parsing messages.
public type Error distinct error;

# The addressed workflow, human task, or review activity does not exist.
public type NotFoundError distinct Error;

# The caller lacks the roles required to see or act on the target.
public type AccessDeniedError distinct Error;

# The request is malformed: a required parameter is missing, or a value is not of
# the expected shape.
public type InvalidRequestError distinct Error;

# The target exists but is not in a state that allows the operation — for example
# completing a task that has already been completed.
public type ConflictError distinct Error;

# The request is well formed, but the payload does not match the type the target
# declared (e.g. a human task completion value that is not assignable to the
# task's result type).
public type InvalidPayloadError distinct Error;

# The operation could not be completed because of a failure inside the workflow
# runtime or its backing service.
public type ExecutionError distinct Error;

# The canonical JSON representation of a management error:
# `{"error": {"message": "..."}}`. Every transport serializes errors this way, so
# a consumer sees the same payload whichever path the operation was invoked
# through.
#
# + err - The error to represent
# + return - The error as a `json` payload
public isolated function toErrorJson(Error err) returns json => {"error": {"message": err.message()}};

# The machine-readable, protocol-independent reason a management operation failed.
# One value per `Error` subtype, so a consumer that carries errors across a
# boundary (a wire format, generated code, a log pipeline) can branch on the
# reason without doing `is`-checks against this module's error types — and without
# this module knowing anything about the consumer's protocol. Adapters own the
# translation: `workflow.management.rest` maps these to HTTP status codes; other
# transports map them to their own vocabulary.
public enum ErrorCode {
    # The addressed workflow, human task, or review activity does not exist.
    NOT_FOUND,
    # The caller lacks the roles required to see or act on the target.
    ACCESS_DENIED,
    # The request is malformed.
    INVALID_REQUEST,
    # The target is not in a state that allows the operation.
    CONFLICT,
    # The payload does not match the type the target declared.
    INVALID_PAYLOAD,
    # A failure inside the workflow runtime or its backing service.
    EXECUTION_ERROR
}

# Classifies a management error into its `ErrorCode` reason.
#
# + err - The error a management operation returned
# + return - The error's protocol-independent reason
public isolated function errorCodeOf(Error err) returns ErrorCode {
    if err is NotFoundError {
        return NOT_FOUND;
    }
    if err is AccessDeniedError {
        return ACCESS_DENIED;
    }
    if err is InvalidRequestError {
        return INVALID_REQUEST;
    }
    if err is ConflictError {
        return CONFLICT;
    }
    if err is InvalidPayloadError {
        return INVALID_PAYLOAD;
    }
    return EXECUTION_ERROR;
}

// ── Internal constructors ─────────────────────────────────────────────────────
// Kept here so operations read as `return notFound("...")` rather than repeating
// the error-construction syntax at every call site.

isolated function notFound(string message) returns NotFoundError => error NotFoundError(message);

isolated function accessDenied(string message) returns AccessDeniedError => error AccessDeniedError(message);

isolated function invalidRequest(string message) returns InvalidRequestError => error InvalidRequestError(message);

isolated function stateConflict(string message) returns ConflictError => error ConflictError(message);

isolated function invalidPayload(string message) returns InvalidPayloadError => error InvalidPayloadError(message);

isolated function executionFailed(string message) returns ExecutionError => error ExecutionError(message);

// ── Classification of runtime errors ──────────────────────────────────────────

# Classifies a runtime error for operations that distinguish only "the target does
# not exist" from "the runtime failed" — the workflow lifecycle and detail
# operations, where the runtime raises nothing else the caller can act on.
#
# + err - The error the runtime raised
# + notFoundMessage - The message to use when the target is missing; the runtime's
#                     own message is used when omitted
# + executionPrefix - Prefix for the failure message, e.g. `"Failed to get history: "`
# + return - The classified management error
isolated function notFoundOrExecutionError(error err, string? notFoundMessage = (),
        string executionPrefix = "") returns Error {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return notFound(notFoundMessage ?: msg);
    }
    return executionFailed(executionPrefix + msg);
}

# Classifies an error raised by the workflow runtime into a management error, over
# the full range of reasons the task operations can surface. The runtime reports
# failures as plain errors whose message carries the reason, so the message is the
# only signal available.
#
# + err - The error the runtime raised
# + notFoundMessage - The message to use when the runtime reports a missing target
# + return - The classified management error
isolated function classifyRuntimeError(error err, string? notFoundMessage = ()) returns Error {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return notFound(notFoundMessage ?: msg);
    }
    if msg.includes("Unauthorized") || msg.includes("not authorized") {
        return accessDenied(msg);
    }
    if msg.includes("not running") || msg.includes("already completed") {
        return stateConflict(msg);
    }
    // A well-formed request whose payload does not match the target's declared type is
    // semantically invalid rather than malformed (ballerina-library#8866).
    if msg.includes("Invalid payload") {
        return invalidPayload(msg);
    }
    return executionFailed(msg);
}
