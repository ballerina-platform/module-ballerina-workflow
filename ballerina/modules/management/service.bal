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

import ballerina/http;
import ballerina/time;

// All configurable variables are scoped to [ballerina.workflow.management] in Config.toml.
//
// K8s-internal (no auth, no TLS):
//   [ballerina.workflow.management]
//   enableManagementApi = true
//   enableBasicAuth     = false
//
// Externally-exposed (Basic Auth + TLS):
//   [ballerina.workflow.management]
//   enableManagementApi  = true
//   enableTls            = true
//   certFile             = "/etc/certs/tls.crt"
//   keyFile              = "/etc/certs/tls.key"
//   basicAuthUsername    = "ops"
//   basicAuthPassword    = "s3cret!"

# Master switch for the management HTTP API.
# When `false` (the default), the listener starts and reserves the port but
# returns `503 Service Unavailable` for every request. Workflow execution runs
# independently of this flag.
# Set to `true` in Config.toml to activate the API.
configurable boolean enableManagementApi = false;

# TCP port the management service listens on.
# Default is 7234 (Temporal's default port 7233 + 1).
configurable int port = 7234;

# Maximum number of items returned per page in list operations.
configurable int maxPageSize = 100;

# Enables HTTPS on the listener.
# Suitable for external deployments; leave `false` for K8s-internal services
# where TLS termination is handled by the ingress controller.
# When `true`, both `certFile` and `keyFile` must be non-empty or the program
# panics at startup with a descriptive error.
configurable boolean enableTls = false;

# Path to the PEM-encoded TLS certificate file.
# Required when `enableTls = true`.
configurable string certFile = "";

# Path to the PEM-encoded TLS private key file.
# Required when `enableTls = true`.
configurable string keyFile = "";

# Enables CORS headers on the listener.
# Set to `false` if CORS is handled upstream (e.g. by an API gateway).
configurable boolean enableCors = true;

# Allowed CORS origins.
# Defaults to `["*"]` (allow all origins). Restrict to specific origins
# in production, e.g. `["https://portal.example.com"]`.
configurable string[] corsAllowOrigins = ["*"];

# Allowed HTTP methods for CORS requests.
# Defaults to all standard REST methods.
configurable string[] corsAllowMethods = ["GET", "POST", "PUT", "DELETE", "OPTIONS"];

# Allowed request headers for CORS requests.
# Defaults to common headers used by the management API.
# If you customize `apiKeyHeader`, ensure it's included in this list.
configurable string[] corsAllowHeaders = ["Content-Type", "x-user-id", "x-user-roles", "Authorization", "x-api-key"];

# Whether to allow credentials (cookies, authorization headers) in CORS requests.
# Set to `true` if your frontend needs to send credentials.
configurable boolean corsAllowCredentials = false;

# Maximum age (in seconds) for caching CORS preflight responses.
# Defaults to ~24 hours (84900 seconds).
configurable decimal corsMaxAge = 84900;

# Enables HTTP Basic Authentication via Ballerina's built-in file user store.
# Defaults to `true` so that accidentally enabling the management API without
# any auth is caught at startup rather than silently exposing an endpoint.
# Set to `false` for K8s-internal deployments (zero-trust / service mesh).
#
# When `true`, user credentials must be configured in Config.toml using the
# standard Ballerina user store format:
# ```toml
# [[ballerina.auth.users]]
# username = "admin"
# password = "workflowadmin"
# scopes   = ["admin"]
# ```
# Authentication is delegated to Ballerina HTTP's `fileUserStoreConfig` handler,
# which implements the standard HTTP Basic scheme including proper challenge
# headers and error responses.
configurable boolean enableBasicAuth = true;

# Enables JWT Bearer token authentication (`Authorization: Bearer <token>`).
# Tokens are validated against the JWKS endpoint specified by `jwksUrl`.
# When `true`, `jwtIssuer`, `jwtAudience`, and `jwksUrl` must all be non-empty
# or the program panics at startup.
#
# **Note:** Full JWT signature verification requires the `ballerina/jwt` module.
# The current implementation performs a presence and format check only.
configurable boolean enableJwtAuth = false;

# Expected issuer (`iss`) claim value for JWT validation.
# Required when `enableJwtAuth = true`.
configurable string jwtIssuer = "";

# Expected audience (`aud`) claim value for JWT validation.
# Required when `enableJwtAuth = true`.
configurable string jwtAudience = "";

# JWKS endpoint URL used to fetch public keys for JWT signature verification.
# Required when `enableJwtAuth = true`.
configurable string jwksUrl = "";

# Enables OAuth2 Bearer token authentication via token introspection.
# When `true`, `oauth2IntrospectionUrl` must be non-empty or the program
# panics at startup.
#
# **Note:** The OAuth2 introspection HTTP call is not yet implemented.
# The config is validated at startup; add an HTTP client for production use.
configurable boolean enableOAuth = false;

# OAuth2 token introspection endpoint URL.
# Required when `enableOAuth = true`.
configurable string oauth2IntrospectionUrl = "";

# Enables API key authentication via a custom request header.
# When `true`, `apiKeyValue` must be non-empty or the program panics at startup.
configurable boolean enableApiKey = false;

# Name of the HTTP header that carries the API key.
# Defaults to `x-api-key`.
configurable string apiKeyHeader = "x-api-key";

# Expected API key value.
# Required when `enableApiKey = true`.
configurable string apiKeyValue = "";

# Validates the management API configuration at module startup.
# Called from `management.bal`'s `init()` after `initManagementModule()`.
# When `enableManagementApi = true`, every enabled auth and TLS option is
# checked; if its required parameters are empty the program panics with a
# descriptive message so that the misconfiguration is caught immediately
# rather than causing a silent security vulnerability at runtime.
isolated function validateManagementApiConfig() {
    if !enableManagementApi {
        return; // Nothing to validate when the API is disabled
    }

    if enableTls {
        if certFile == "" || keyFile == "" {
            panic error("workflow.management: TLS is enabled (enableTls = true) " +
                "but 'certFile' and/or 'keyFile' are not configured. " +
                "Set both paths or disable TLS with enableTls = false.");
        }
    }

    if enableJwtAuth {
        if jwtIssuer == "" || jwtAudience == "" || jwksUrl == "" {
            panic error("workflow.management: JWT auth is enabled (enableJwtAuth = true) " +
                "but one or more of 'jwtIssuer', 'jwtAudience', 'jwksUrl' are not set.");
        }
    }

    if enableOAuth {
        if oauth2IntrospectionUrl == "" {
            panic error("workflow.management: OAuth2 auth is enabled (enableOAuth = true) " +
                "but 'oauth2IntrospectionUrl' is not set.");
        }
    }

    if enableApiKey {
        if apiKeyValue == "" {
            panic error("workflow.management: API key auth is enabled (enableApiKey = true) " +
                "but 'apiKeyValue' is not set.");
        }
    }
}

# Builds the `http:ListenerConfiguration` from the configurable variables.
# Wires TLS only — CORS is configured at service level via `@http:ServiceConfig`.
# + return - Listener configuration with TLS wired when `enableTls` is true.
isolated function buildMgmtListenerConfig() returns http:ListenerConfiguration {
    http:ListenerConfiguration cfg = {host: "0.0.0.0"};
    if enableTls && certFile != "" && keyFile != "" {
        cfg.secureSocket = {
            key: {certFile: certFile, keyFile: keyFile}
        };
    }
    return cfg;
}

listener http:Listener mgmtListener = check new (port, buildMgmtListenerConfig());

// ── Service-level auth configuration ─────────────────────────────────────────────
// Ballerina HTTP evaluates @http:ServiceConfig (including the `auth` field) at
// module initialization time, so a module-level `final` variable computed from
// configurables can be referenced directly in the annotation.
//
// BasicAuth  → Ballerina FileUserStoreConfig (credentials from [[ballerina.auth.users]])
// JWT        → Ballerina JwtValidatorConfig  (validated against the JWKS endpoint)
// OAuth2     → Ballerina OAuth2IntrospectionConfig
// API key    → handled separately in ManagementGatewayInterceptor (no built-in HTTP support)
//
// OR logic: a request passes if it satisfies any ONE of the enabled handlers.
// An empty array (all disabled) means no auth is required — suitable for
// K8s-internal deployments where the service mesh handles identity.

# Builds the `http:ListenerAuthConfig[]` from the configurable auth flags.
# Called once at module initialization; the result is bound to `mgmtAuthConfigs`.
# + return - Array of auth configs for the enabled auth types, or nil if none are enabled.
isolated function buildCorsConfig() returns http:CorsConfig {
    if !enableCors {
        return {allowOrigins: []};
    }
    return {
        allowOrigins: corsAllowOrigins,
        allowHeaders: corsAllowHeaders,
        allowMethods: corsAllowMethods,
        allowCredentials: corsAllowCredentials,
        maxAge: corsMaxAge
    };
}

isolated function buildAuthConfigs() returns http:ListenerAuthConfig[]? {
    http:ListenerAuthConfig[] configs = [];

    if enableBasicAuth {
        configs.push({fileUserStoreConfig: {}});
    }

    if enableJwtAuth {
        configs.push({
            jwtValidatorConfig: {
                issuer: jwtIssuer,
                audience: jwtAudience,
                signatureConfig: {jwksConfig: {url: jwksUrl}}
            }
        });
    }

    if enableOAuth {
        configs.push({
            oauth2IntrospectionConfig: {
                url: oauth2IntrospectionUrl,
                tokenTypeHint: "access_token"
            }
        });
    }

    return configs.length() > 0 ? configs : ();
}

# Request interceptor that enforces the management API master switch and
# API key authentication (which has no built-in Ballerina HTTP handler).
# Registered via createInterceptors on the `http:InterceptableService`.
#
# 1. **Master switch** — returns `503` when `enableManagementApi = false`.
# 2. **API key** — validates the configured header when `enableApiKey = true`;
#    returns `401` only when API key is the sole auth type and validation fails.
#    When other auth types are also enabled, a failed key falls through so that
#    `@http:ServiceConfig { auth: ... }` can still admit the request.
service class ManagementGatewayInterceptor {
    *http:RequestInterceptor;

    resource function 'default [string... path](
            http:RequestContext ctx,
            http:Request req)
            returns http:ServiceUnavailable|http:Unauthorized|http:NextService|error? {

        // Master switch
        if !enableManagementApi {
            return <http:ServiceUnavailable>{
                body: {"error": {"message": "Management API is disabled. " +
                    "Set enableManagementApi = true in Config.toml to enable."}}
            };
        }

        // API key auth (no built-in Ballerina HTTP handler)
        if enableApiKey {
            string|http:HeaderNotFoundError keyHeader = req.getHeader(apiKeyHeader);
            if keyHeader is string && keyHeader == apiKeyValue {
                return ctx.next();
            }
            if !enableBasicAuth && !enableJwtAuth && !enableOAuth {
                return <http:Unauthorized>{
                    headers: {"WWW-Authenticate": string `ApiKey header="${apiKeyHeader}"`},
                    body: {"error": {"message": "Unauthorized: valid API key required"}}
                };
            }
            // Fall through — let @http:ServiceConfig auth attempt to admit the request
        }

        return ctx.next();
    }
}

// Service declaration — base path `/workflow`.
// Implements `http:InterceptableService` to register the `ManagementGatewayInterceptor`.
// `@http:ServiceConfig` handles CORS and the built-in auth types (BasicAuth, JWT, OAuth2).
@http:ServiceConfig {
    cors: buildCorsConfig(),
    auth: buildAuthConfigs()
}
service http:InterceptableService /workflow on mgmtListener {

    # Returns the interceptor pipeline for this service.
    # + return - The `ManagementGatewayInterceptor` that enforces the API master switch and API key auth.
    public function createInterceptors() returns ManagementGatewayInterceptor {
        return new ManagementGatewayInterceptor();
    }

    // ── Definitions ──────────────────────────────────────────────────────────

    # Lists all registered workflow types with schema and worker info.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - List of workflow definitions as JSON, or an internal server error.
    resource isolated function get definitions(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:InternalServerError {
        WorkflowDefinition[]|error defs = listWorkflowDefinitions();
        if defs is error {
            return <http:InternalServerError>{body: errorBody("Failed to list definitions: " + defs.message())};
        }
        return {definitions: defs.toJson()};
    }

    // ── Workflow Instances — List & Start ─────────────────────────────────────

    # Lists workflow instances with optional status/type/id/time filters and pagination.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + status - Filter by workflow status (e.g. `RUNNING`, `COMPLETED`).
    # + workflowType - Filter by workflow type name.
    # + workflowId - Filter by workflow ID prefix.
    # + limit - Maximum number of results to return (capped at `maxPageSize`).
    # + pageToken - Pagination cursor from a previous response.
    # + startTimeFrom - Optional ISO-8601 lower bound on workflow start time.
    # + startTimeTo - Optional ISO-8601 upper bound on workflow start time.
    # + closeTimeFrom - Optional ISO-8601 lower bound on workflow close time.
    # + closeTimeTo - Optional ISO-8601 upper bound on workflow close time.
    # + return - Paginated workflow instances as JSON, or an internal server error.
    resource isolated function get workflows(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            string? status = (),
            string? workflowType = (),
            string? workflowId = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = ())
            returns json|http:InternalServerError {
        int effectiveLimit = clampLimit('limit, maxPageSize);
        WorkflowInstancePage|error page = listWorkflowInstances(
                status, workflowType, workflowId, effectiveLimit, pageToken,
                startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo);
        if page is error {
            return <http:InternalServerError>{body: errorBody("Failed to list workflows: " + page.message())};
        }
        return page.toJson();
    }

    # Starts a new workflow instance.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing `workflowType`, optional `input`, `workflowId`, and `timeoutSeconds`.
    # + return - Created workflow handle as JSON, a bad request error if `workflowType` is missing, or an internal server error.
    resource isolated function post workflows(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:Created|http:BadRequest|http:InternalServerError {
        json? wfTypeJson = body["workflowType"];
        if wfTypeJson is () {
            return <http:BadRequest>{body: errorBody("workflowType is required")};
        }
        if wfTypeJson !is string {
            return <http:BadRequest>{body: errorBody("workflowType must be a string")};
        }
        string wfType = wfTypeJson;
        json? input = body["input"];
        string? wfId = body["workflowId"] is string ? <string>body["workflowId"] : ();
        int? timeout = body["timeoutSeconds"] is int ? <int>body["timeoutSeconds"] : ();
        WorkflowHandle|error wfHandle = startWorkflowByType(wfType, input, wfId, timeout);
        if wfHandle is error {
            return <http:InternalServerError>{body: errorBody("Failed to start workflow: " + wfHandle.message())};
        }
        return <http:Created>{body: wfHandle.toJson()};
    }

    // ── Workflow Instance — Detail ────────────────────────────────────────────

    # Returns execution info for a specific workflow run.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Workflow execution info as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/[string runId](
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        WorkflowExecutionInfo|error info = getWorkflowInfo(workflowId);
        if info is error {
            string msg = info.message();
            if msg.includes("not found") || msg.includes("NOT_FOUND") {
                return <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)};
            }
            return <http:InternalServerError>{body: errorBody(msg)};
        }
        return info.toJson();
    }

    // ── Workflow Lifecycle Operations ─────────────────────────────────────────

    # Suspends a running workflow.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/[string runId]/suspend(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        error? result = suspendWorkflow(workflowId);
        if result is error {
            string msg = result.message();
            return msg.includes("not found")
                ? <http:NotFound>{body: errorBody(msg)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return {success: true};
    }

    # Resumes a suspended workflow.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/[string runId]/resume(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        error? result = resumeWorkflow(workflowId);
        if result is error {
            string msg = result.message();
            return msg.includes("not found")
                ? <http:NotFound>{body: errorBody(msg)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return {success: true};
    }

    # Terminates a workflow immediately.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Optional request body with a `reason` string.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/[string runId]/terminate(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json>? body = ())
            returns json|http:NotFound|http:InternalServerError {
        string? reason = body is map<json> && body["reason"] is string
                ? <string>(<map<json>>body)["reason"] : ();
        error? result = terminateWorkflow(workflowId, runId, reason);
        if result is error {
            string msg = result.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody(msg)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return {success: true};
    }

    # Requests graceful cancellation of a workflow.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/[string runId]/cancel(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        error? result = cancelWorkflow(workflowId, runId);
        if result is error {
            string msg = result.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody(msg)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return {success: true};
    }

    // ── Workflow Execution Visualization ─────────────────────────────────────

    # Returns all execution history events for a workflow run.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - History events as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/[string runId]/history(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        HistoryEvent[]|error events = getWorkflowHistory(workflowId, runId);
        if events is error {
            string msg = events.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
                : <http:InternalServerError>{body: errorBody("Failed to get history: " + msg)};
        }
        return {events: events.toJson()};
    }

    # Returns the activity tree for a workflow run.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Activity tree nodes as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/[string runId]/activity\-tree(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        ActivityTreeNode[]|error nodes = getActivityTree(workflowId, runId);
        if nodes is error {
            string msg = nodes.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
                : <http:InternalServerError>{body: errorBody("Failed to get activity tree: " + msg)};
        }
        return {nodes: nodes.toJson()};
    }

    # Returns the execution graph for rendering with D3.js or React Flow.
    # + workflowId - The workflow instance ID.
    # + runId - The specific run ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Execution graph as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/[string runId]/execution\-graph(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        ExecutionGraph|error graph = getExecutionGraph(workflowId, runId);
        if graph is error {
            string msg = graph.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody("Workflow not found: " + workflowId)}
                : <http:InternalServerError>{body: errorBody("Failed to get execution graph: " + msg)};
        }
        return graph.toJson();
    }

    // ── Human Tasks — List & Count ────────────────────────────────────────────

    # Lists human tasks with optional filters and pagination.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + status - Filter by task status (e.g. `PENDING`, `COMPLETED`).
    # + parentWorkflowId - Filter by parent workflow ID.
    # + parentWorkflowType - Filter by parent workflow type.
    # + taskName - Filter by task name.
    # + userRole - Filter to tasks assigned to this role.
    # + onlyMyTasks - When `true`, returns only tasks assigned to the calling user.
    # + limit - Maximum number of results to return (capped at `maxPageSize`).
    # + pageToken - Pagination cursor from a previous response.
    # + startTimeFrom - Optional ISO-8601 lower bound on task start time.
    # + startTimeTo - Optional ISO-8601 upper bound on task start time.
    # + closeTimeFrom - Optional ISO-8601 lower bound on task close time.
    # + closeTimeTo - Optional ISO-8601 upper bound on task close time.
    # + return - Paginated human tasks as JSON, or an internal server error.
    resource isolated function get human\-tasks(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            string? status = (),
            string? parentWorkflowId = (),
            string? parentWorkflowType = (),
            string? taskName = (),
            string? userRole = (),
            boolean onlyMyTasks = false,
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = ())
            returns json|http:InternalServerError {
        HumanTaskSummary[]|error all = listAllHumanTasks(status,
                startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo);
        if all is error {
            return <http:InternalServerError>{body: errorBody("Failed to list human tasks: " + all.message())};
        }
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        // Apply lambda-safe filters first
        HumanTaskSummary[] preFiltered = all
            .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
            .filter(t => parentWorkflowType is () || t.parentWorkflowType == parentWorkflowType)
            .filter(t => taskName is () || t.taskName == taskName)
            .filter(t => userRole is () || t.userRoles.some(r => r == userRole));
        // Apply onlyMyTasks and canComplete in a foreach (avoids lambda isolation constraint
        // on computed local variables in this Ballerina version)
        HumanTaskSummary[] enriched = [];
        foreach HumanTaskSummary t in preFiltered {
            if onlyMyTasks {
                boolean hasMatchingRole = false;
                if callerRoles is [string, string...] {
                    foreach string role in t.userRoles {
                        if callerRoles.indexOf(role) != () {
                            hasMatchingRole = true;
                            break;
                        }
                    }
                }
                if !hasMatchingRole {
                    continue;
                }
            }
            boolean canComplete = false;
            if callerRoles is [string, string...] {
                foreach string role in t.userRoles {
                    if callerRoles.indexOf(role) != () {
                        canComplete = true;
                        break;
                    }
                }
            }
            t.canComplete = canComplete;
            enriched.push(t);
        }
        return paginateHumanTasks(enriched, clampLimit('limit, maxPageSize), pageToken).toJson();
    }

    # Returns count of pending human tasks (for UI badge).
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{count: N}` JSON object, or an internal server error.
    resource isolated function get human\-tasks/pending\-count(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:InternalServerError {
        HumanTaskSummary[]|error pending = listAllHumanTasks("PENDING");
        if pending is error {
            return <http:InternalServerError>{body: errorBody("Failed to count pending tasks: " + pending.message())};
        }
        return {count: pending.length()};
    }

    // ── Human Tasks — Detail & Operations ────────────────────────────────────

    # Returns detailed info for a single human task.
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Human task detail as JSON, a not-found error, or an internal server error.
    resource isolated function get human\-tasks/[string taskId](
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        HumanTaskInfo|error info = getHumanTaskInfo(taskId);
        if info is error {
            string msg = info.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody("Human task not found: " + taskId)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return info.toJson();
    }

    # Completes a human task with the given result.
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the task `result`.
    # + return - Completion info as JSON, or a not-found, forbidden, conflict, or internal server error.
    resource isolated function post human\-tasks/[string taskId]/complete(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        error? err = completeHumanTask(taskId, body["result"], callerRoles, userId);
        if err is error {
            return humanTaskErrorResponse(err);
        }
        return buildCompletionResponse(userId).toJson();
    }

    # Fails/rejects a human task with a reason.
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the `reason` string and optional `details` object.
    # + return - Completion info as JSON, or a bad request, not-found, forbidden, conflict, or internal server error.
    resource isolated function post human\-tasks/[string taskId]/'fail(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        if body["reason"] is () {
            return <http:BadRequest>{body: errorBody("reason is required")};
        }
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        map<json>? details = body["details"] is map<json> ? <map<json>>body["details"] : ();
        error? err = failHumanTask(taskId, body["reason"].toString(), details, callerRoles, userId);
        if err is error {
            return humanTaskErrorResponse(err);
        }
        return buildCompletionResponse(userId).toJson();
    }

    # Cancels a human task (terminates the child workflow).
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, or a not-found, conflict, or internal server error.
    resource isolated function post human\-tasks/[string taskId]/cancel(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Conflict|http:InternalServerError {
        error? err = cancelHumanTask(taskId, cancelledBy = userId);
        if err is error {
            string msg = err.message();
            if msg.includes("not found") || msg.includes("NOT_FOUND") {
                return <http:NotFound>{body: errorBody(msg)};
            }
            if msg.includes("not running") {
                return <http:Conflict>{body: errorBody(msg)};
            }
            return <http:InternalServerError>{body: errorBody(msg)};
        }
        return {success: true};
    }

    // ── Retry Tasks — List & Detail ───────────────────────────────────────────

    # Lists manual retry tasks with optional filters and pagination.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + status - Filter by task status (e.g. `PENDING`, `COMPLETED`).
    # + parentWorkflowId - Filter by parent workflow ID.
    # + taskName - Filter by task name.
    # + limit - Maximum number of results to return (capped at `maxPageSize`).
    # + pageToken - Pagination cursor from a previous response.
    # + startTimeFrom - Optional ISO-8601 lower bound on task start time.
    # + startTimeTo - Optional ISO-8601 upper bound on task start time.
    # + closeTimeFrom - Optional ISO-8601 lower bound on task close time.
    # + closeTimeTo - Optional ISO-8601 upper bound on task close time.
    # + return - Paginated retry tasks as JSON, or an internal server error.
    resource isolated function get retry\-tasks(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            string? status = (),
            string? parentWorkflowId = (),
            string? taskName = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = ())
            returns json|http:InternalServerError {
        RetryTaskSummary[]|error all = listAllRetryTasks(status,
                startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo);
        if all is error {
            return <http:InternalServerError>{body: errorBody("Failed to list retry tasks: " + all.message())};
        }
        RetryTaskSummary[] filtered = all
            .filter(t => parentWorkflowId is () || t.parentWorkflowId == parentWorkflowId)
            .filter(t => taskName is () || t.taskName == taskName);
        return paginateRetryTasks(filtered, clampLimit('limit, maxPageSize), pageToken).toJson();
    }

    # Returns detailed info for a single retry task.
    # + taskId - The retry task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Retry task detail as JSON, a not-found error, or an internal server error.
    resource isolated function get retry\-tasks/[string taskId](
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        RetryTaskInfo|error info = getRetryTaskInfo(taskId);
        if info is error {
            string msg = info.message();
            return msg.includes("not found") || msg.includes("NOT_FOUND")
                ? <http:NotFound>{body: errorBody("Retry task not found: " + taskId)}
                : <http:InternalServerError>{body: errorBody(msg)};
        }
        return info.toJson();
    }

    // ── Retry Tasks — Decisions ───────────────────────────────────────────────

    # Retries the failed activity with the original input.
    # + taskId - The retry task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Retry decision info as JSON, or a not-found, forbidden, conflict, or internal server error.
    resource isolated function post retry\-tasks/[string taskId]/'retry(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        error? err = completeRetryTask(taskId, {action: "retry"}, callerRoles, userId);
        if err is error { return retryTaskErrorResponse(err); }
        return buildRetryDecisionResponse("retry", userId).toJson();
    }

    # Retries the failed activity with modified input.
    # + taskId - The retry task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the replacement `input` object.
    # + return - Retry decision info as JSON, or a bad request, not-found, forbidden, conflict, or internal server error.
    resource isolated function post retry\-tasks/[string taskId]/retry\-with\-input(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        if body["input"] !is map<json> {
            return <http:BadRequest>{body: errorBody("input must be a JSON object")};
        }
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        error? err = completeRetryTask(taskId,
                {action: "retry-with-input", input: <map<anydata>>body["input"]},
                callerRoles, userId);
        if err is error { return retryTaskErrorResponse(err); }
        return buildRetryDecisionResponse("retry-with-input", userId).toJson();
    }

    # Permanently fails the activity (cancels further retries).
    # + taskId - The retry task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Retry decision info as JSON, or a not-found, forbidden, conflict, or internal server error.
    resource isolated function post retry\-tasks/[string taskId]/'fail(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        [string, string...]? callerRoles = parseRolesHeader(userRoles);
        error? err = completeRetryTask(taskId, {action: "fail"}, callerRoles, userId);
        if err is error { return retryTaskErrorResponse(err); }
        return buildRetryDecisionResponse("fail", userId).toJson();
    }
}

# Builds a `CompletionInfo` record stamped with the current UTC time and the
# caller's user ID (falls back to `"unknown"` when the header is absent).
# + userId - Optional caller identity; used as the `completedBy` field.
# + return - A `CompletionInfo` record with `success`, `completedBy`, and `completedAt` fields.
isolated function buildCompletionResponse(string? userId) returns CompletionInfo {
    time:Utc now = time:utcNow();
    return {success: true, completedBy: userId ?: "unknown", completedAt: time:utcToString(now)};
}

# Builds a `RetryDecisionInfo` record stamped with the current UTC time and the
# caller's user ID (falls back to `"unknown"` when the header is absent).
# + decision - The retry decision taken: `"retry"`, `"retry-with-input"`, or `"fail"`.
# + userId - Optional caller identity; used as the `decidedBy` field.
# + return - A `RetryDecisionInfo` record with `success`, `decision`, `decidedBy`, and `decidedAt` fields.
isolated function buildRetryDecisionResponse(string decision, string? userId) returns RetryDecisionInfo {
    time:Utc now = time:utcNow();
    return {success: true, decision: decision, decidedBy: userId ?: "unknown", decidedAt: time:utcToString(now)};
}

isolated function errorBody(string message) returns map<json> {
    return {"error": {"message": message}};
}

isolated function clampLimit(int requested, int maxAllowed) returns int {
    if requested < 1 { return 20; }
    return requested > maxAllowed ? maxAllowed : requested;
}

isolated function parseRolesHeader(string? rolesHeader) returns [string, string...]? {
    if rolesHeader is () || rolesHeader.trim().length() == 0 { return (); }
    string[] parts = re`,`.split(rolesHeader).map(r => r.trim()).filter(r => r.length() > 0);
    if parts.length() == 0 { return (); }
    return [parts[0], ...parts.slice(1)];
}

isolated function paginateHumanTasks(HumanTaskSummary[] items, int 'limit, string? pageToken)
        returns HumanTaskPage {
    int startIdx = decodePageToken(pageToken);
    int endIdx = int:min(startIdx + 'limit, items.length());
    HumanTaskSummary[] pageItems = startIdx < items.length() ? items.slice(startIdx, endIdx) : [];
    boolean hasMore = endIdx < items.length();
    return {items: pageItems, nextPageToken: hasMore ? encodePageToken(endIdx) : (), hasMore: hasMore};
}

isolated function paginateRetryTasks(RetryTaskSummary[] items, int 'limit, string? pageToken)
        returns RetryTaskPage {
    int startIdx = decodePageToken(pageToken);
    int endIdx = int:min(startIdx + 'limit, items.length());
    RetryTaskSummary[] pageItems = startIdx < items.length() ? items.slice(startIdx, endIdx) : [];
    boolean hasMore = endIdx < items.length();
    return {items: pageItems, nextPageToken: hasMore ? encodePageToken(endIdx) : (), hasMore: hasMore};
}

isolated function encodePageToken(int offset) returns string => offset.toString();

isolated function decodePageToken(string? token) returns int {
    if token is () { return 0; }
    int|error parsed = int:fromString(token);
    return parsed is int && parsed >= 0 ? parsed : 0;
}

isolated function humanTaskErrorResponse(error err)
        returns http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return <http:NotFound>{body: errorBody(msg)};
    }
    if msg.includes("Unauthorized") || msg.includes("not authorized") {
        return <http:Forbidden>{body: errorBody(msg)};
    }
    if msg.includes("not running") || msg.includes("already completed") {
        return <http:Conflict>{body: errorBody(msg)};
    }
    return <http:InternalServerError>{body: errorBody(msg)};
}

isolated function retryTaskErrorResponse(error err)
        returns http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
    string msg = err.message();
    if msg.includes("not found") || msg.includes("NOT_FOUND") {
        return <http:NotFound>{body: errorBody(msg)};
    }
    if msg.includes("Unauthorized") || msg.includes("not authorized") {
        return <http:Forbidden>{body: errorBody(msg)};
    }
    if msg.includes("not running") || msg.includes("already completed") {
        return <http:Conflict>{body: errorBody(msg)};
    }
    return <http:InternalServerError>{body: errorBody(msg)};
}
