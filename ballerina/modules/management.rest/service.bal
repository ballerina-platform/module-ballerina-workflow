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
import ballerina/lang.runtime;

// All configurable variables are scoped to [ballerina.workflow.management.rest] in Config.toml.
//
// K8s-internal (no auth, no TLS):
//   [ballerina.workflow.management.rest]
//   enableManagementApi = true
//   enableBasicAuth     = false
//
// Externally-exposed (Basic Auth + TLS):
//   [ballerina.workflow.management.rest]
//   enableManagementApi  = true
//   enableTls            = true
//   certFile             = "/etc/certs/tls.crt"
//   keyFile              = "/etc/certs/tls.key"
//   basicAuthUsername    = "ops"
//   basicAuthPassword    = "s3cret!"
//
// Every resource below is a thin HTTP adapter over the shared operation functions in
// operations.bal — the same functions `executeManagementCommand` (dispatcher.bal)
// executes, which is what keeps a command result byte-identical to the corresponding
// REST response.

// Validates the management API configuration so any misconfiguration causes a
// descriptive error at startup rather than a silent runtime failure, and then starts
// the management HTTP service programmatically when enableManagementApi = true.
// When the API is disabled, no listener is created and no port is reserved —
// importing `ballerina/workflow.management` alone never opens a port, and importing
// this module with the API disabled doesn't either.
//
// Module init order guarantees `workflow.management` (this module's dependency)
// initializes first, so every resource below can delegate to it safely.
#
# + return - An error if the management service cannot be started
function init() returns error? {
    validateManagementApiConfig();
    check startManagementService();
}

# Master switch for the management HTTP API.
# When `false` (the default), no listener is created and the port is not
# reserved — importing this module purely for its programmatic helpers opens
# no port. Workflow execution runs independently of this flag.
# Set to `true` in Config.toml to activate the API.
public configurable boolean enableManagementApi = false;

# TCP port the management service listens on.
# Default is 8234.
public configurable int port = 8234;

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

# Optional role required to view or decide review activities that declare no roles of
# their own (failure reviews are created without role restrictions today). By default
# (`()`), such review activities are visible to any caller; set a role name to restrict
# them to callers holding that role in `x-user-roles`. Review activities that do declare
# roles always require a matching caller role, regardless of this setting.
configurable string? reviewActivityAccessRole = ();

# Validates the management API configuration at module startup.
# Called from this module's `init()`.
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
            panic error("workflow.management.rest: TLS is enabled (enableTls = true) " +
                "but 'certFile' and/or 'keyFile' are not configured. " +
                "Set both paths or disable TLS with enableTls = false.");
        }
    }

    if enableJwtAuth {
        if jwtIssuer == "" || jwtAudience == "" || jwksUrl == "" {
            panic error("workflow.management.rest: JWT auth is enabled (enableJwtAuth = true) " +
                "but one or more of 'jwtIssuer', 'jwtAudience', 'jwksUrl' are not set.");
        }
    }

    if enableOAuth {
        if oauth2IntrospectionUrl == "" {
            panic error("workflow.management.rest: OAuth2 auth is enabled (enableOAuth = true) " +
                "but 'oauth2IntrospectionUrl' is not set.");
        }
    }

    if enableApiKey {
        if apiKeyValue == "" {
            panic error("workflow.management.rest: API key auth is enabled (enableApiKey = true) " +
                "but 'apiKeyValue' is not set.");
        }
    }

    if enforceScopes && !enableJwtAuth && !enableOAuth {
        panic error("workflow.management.rest: scope enforcement is enabled (enforceScopes = true) " +
            "but no token-based auth is: enable 'enableJwtAuth' or 'enableOAuth', " +
            "or disable scope enforcement.");
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

// Deliberately a plain module-level variable, NOT a `listener` declaration.
// A `listener` declaration would put the listener under the runtime's module-listener
// lifecycle in addition to the dynamic registration performed below, effectively
// registering the service twice — and it would always reserve the port. This module
// owns the full lifecycle instead: the listener exists only while the management API
// is enabled (create + attach + start + dynamic registration in
// startManagementService(), deregistration + graceful stop in stopManagementService()).
http:Listener? mgmtListener = ();

# Creates the management listener, attaches the service, and starts it — only when
# `enableManagementApi = true`. Called from the module `init()`. When the API is
# disabled this is a no-op: no listener is created and the port stays free, so a
# program that imports this module purely for its programmatic helpers opens no
# port. The started listener is registered as a dynamic listener with the runtime,
# which keeps a `main`-function program alive after `main` returns so the
# management API stays available.
#
# + return - An error if creating, attaching, or starting the listener fails
function startManagementService() returns error? {
    if !enableManagementApi {
        return;
    }
    check startManagementListener();
}

# Creates, attaches, starts, and registers the management listener — the lifecycle that
# `startManagementService` gates behind `enableManagementApi`. Factored out so the
# module's tests can exercise the lifecycle directly while the test configuration keeps
# the API disabled (no port reserved at module init).
#
# + return - An error if creating, attaching, or starting the listener fails
function startManagementListener() returns error? {
    http:Listener httpListener = check new (port, buildMgmtListenerConfig());
    mgmtListener = httpListener;
    check httpListener.attach(mgmtService, "/workflow");
    check httpListener.'start();
    // Always stop the listener on shutdown. With a programmatically started listener the
    // runtime no longer does this automatically (it only manages `listener` declarations);
    // an un-stopped listener would hold the port past program end (e.g. across `bal test`
    // module executions).
    runtime:onGracefulStop(stopManagementService);
    runtime:registerListener(httpListener);
}

# Deregisters the management listener from the runtime and stops it gracefully so that
# shutdown is not blocked and the port is released. A no-op when the management API is
# disabled (no listener was created).
#
# + return - An error if stopping the listener fails
function stopManagementService() returns error? {
    http:Listener? httpListener = mgmtListener;
    if httpListener is () {
        return;
    }
    // Clear the reference first so a second invocation (e.g. the graceful-stop handler
    // firing after a test already stopped the listener) is a clean no-op.
    mgmtListener = ();
    runtime:deregisterListener(httpListener);
    check httpListener.gracefulStop();
}

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

# Builds the `http:CorsConfig` from the configurable CORS variables.
# Called once at module initialization via `@http:ServiceConfig`.
# + return - The CORS configuration; origins are empty when CORS is disabled.
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

# Request interceptor that resolves the caller's identity and enforces API key
# authentication (which has no built-in Ballerina HTTP handler). Registered via
# createInterceptors on the `http:InterceptableService`. The management API
# master switch needs no gate here: when `enableManagementApi = false` the
# listener is never created, so no request can reach this service.
#
# **Identity** — in token mode (JWT/OAuth2) the caller's identity is extracted
# from the bearer token's claims onto the x-user-* headers, and scopes are
# enforced when configured (see identity.bal). The token's signature is still
# validated by `@http:ServiceConfig { auth: ... }` after this interceptor, so an
# invalid token never reaches a resource.
#
# **API key** — validates the configured header when `enableApiKey = true`;
# returns `401` only when API key is the sole auth type and validation fails.
# When other auth types are also enabled, a failed key falls through so that
# `@http:ServiceConfig { auth: ... }` can still admit the request.
service class ManagementGatewayInterceptor {
    *http:RequestInterceptor;

    resource function 'default [string... path](
            http:RequestContext ctx,
            http:Request req)
            returns http:Unauthorized|http:Forbidden|http:NextService|error? {

        http:Forbidden? scopeErr = applyCallerIdentity(req, path.length() > 0 ? path[0] : "");
        if scopeErr is http:Forbidden {
            return scopeErr;
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

// Service value — attached to `mgmtListener` at base path `/workflow` by
// startManagementService(), which the module `init()` invokes. Kept as a service
// *value* (not a service declaration bound to a module listener) so this module fully
// owns the listener lifecycle.
// Implements `http:InterceptableService` to register the `ManagementGatewayInterceptor`.
// `@http:ServiceConfig` handles CORS and the built-in auth types (BasicAuth, JWT, OAuth2).
final http:InterceptableService mgmtService = @http:ServiceConfig {
    cors: buildCorsConfig(),
    auth: buildAuthConfigs()
} service object http:InterceptableService {

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
        return opListDefinitions();
    }

    // ── Workflow Instances — List & Start ─────────────────────────────────────

    # Lists workflow instances with optional status/type/id/time filters and pagination.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + status - Filter by workflow status (e.g. `RUNNING`, `COMPLETED`).
    # + workflowType - Filter by workflow type name.
    # + workflowId - Filter by workflow ID prefix.
    # + startedBy - Filter by starter user ID captured from `x-user-id` on workflow start.
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
            string? startedBy = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = ())
            returns json|http:InternalServerError {
        return opListWorkflows(status, workflowType, workflowId, startedBy, 'limit, pageToken,
                startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo);
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
            returns http:Created|http:BadRequest|http:InternalServerError {
        return opStartWorkflow(body, userId);
    }

    // ── Workflow Instance — Latest Run ───────────────────────────────────────
    // These routes omit {runId} and always target the latest (or currently-active) run.
    // The {runId} variants below pin the request to an exact run.

    # Returns execution info for the latest run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Workflow execution info as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId](
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opGetWorkflow(workflowId, (), parseRolesHeader(userRoles));
    }

    # Suspends the latest active run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/suspend(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        return opSuspendWorkflow(workflowId, ());
    }

    # Resumes the latest suspended run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/resume(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        return opResumeWorkflow(workflowId, ());
    }

    # Terminates the latest run of a workflow immediately.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Optional request body with a `reason` string.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/terminate(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json>? body = ())
            returns json|http:NotFound|http:InternalServerError {
        string? reason = body is map<json> && body["reason"] is string
                ? <string>(<map<json>>body)["reason"] : ();
        return opTerminateWorkflow(workflowId, (), reason);
    }

    # Requests graceful cancellation of the latest run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{success: true}` on success, a not-found error, or an internal server error.
    resource isolated function post workflows/[string workflowId]/cancel(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:InternalServerError {
        return opCancelWorkflow(workflowId, ());
    }

    # Returns all execution history events for the latest run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - History events as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/history(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opWorkflowHistory(workflowId, (), parseRolesHeader(userRoles));
    }

    # Returns the activity tree for the latest run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Activity tree nodes as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/activity\-tree(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opActivityTree(workflowId, (), parseRolesHeader(userRoles));
    }

    # Returns the execution graph for the latest run of a workflow.
    # + workflowId - The workflow instance ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Execution graph as JSON, a not-found error, or an internal server error.
    resource isolated function get workflows/[string workflowId]/execution\-graph(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opExecutionGraph(workflowId, (), parseRolesHeader(userRoles));
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
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opGetWorkflow(workflowId, runId, parseRolesHeader(userRoles));
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
        return opSuspendWorkflow(workflowId, runId);
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
        return opResumeWorkflow(workflowId, runId);
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
        return opTerminateWorkflow(workflowId, runId, reason);
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
        return opCancelWorkflow(workflowId, runId);
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
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opWorkflowHistory(workflowId, runId, parseRolesHeader(userRoles));
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
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opActivityTree(workflowId, runId, parseRolesHeader(userRoles));
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
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opExecutionGraph(workflowId, runId, parseRolesHeader(userRoles));
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
        return opListHumanTasks(status, parentWorkflowId, parentWorkflowType, taskName, userRole,
                onlyMyTasks, 'limit, pageToken, startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo,
                parseRolesHeader(userRoles));
    }

    # Returns count of pending human tasks (for UI badge).
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - `{count: N}` JSON object, or an internal server error.
    resource isolated function get human\-tasks/pending\-count(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:InternalServerError {
        return opPendingHumanTaskCount(parseRolesHeader(userRoles));
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
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opGetHumanTask(taskId, parseRolesHeader(userRoles));
    }

    # Completes a human task with the given result.
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the task `result`.
    # + return - Completion info as JSON, or a not-found, forbidden, conflict, unprocessable-entity (invalid
    #            payload), or internal server error.
    resource isolated function post human\-tasks/[string taskId]/complete(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:NotFound|http:Forbidden|http:Conflict|http:UnprocessableEntity|http:InternalServerError {
        return opCompleteHumanTask(taskId, body["result"], parseRolesHeader(userRoles), userId);
    }

    # Fails/rejects a human task with a reason.
    # + taskId - The human task workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the `reason` string and optional `details` object.
    # + return - Completion info as JSON, or a bad request, not-found, forbidden, conflict, unprocessable-entity
    #            (invalid payload), or internal server error.
    resource isolated function post human\-tasks/[string taskId]/'fail(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:UnprocessableEntity
                    |http:InternalServerError {
        map<json>? details = body["details"] is map<json> ? <map<json>>body["details"] : ();
        return opFailHumanTask(taskId, body["reason"], details, parseRolesHeader(userRoles), userId);
    }

    // Note: there is deliberately no cancel endpoint for human tasks. A task becomes
    // CANCELED only internally, when its parent workflow closes; admins can terminate
    // the task workflow (TERMINATED) via the workflow terminate endpoint instead.

    // ── Review Activities — List & Detail ────────────────────────────────────

    # Lists review activities with optional filters and pagination.
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
    # + return - Paginated review activities as JSON, or an internal server error.
    resource isolated function get review\-activities(
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
        return opListReviewActivities(status, parentWorkflowId, taskName, 'limit, pageToken,
                startTimeFrom, startTimeTo, closeTimeFrom, closeTimeTo, parseRolesHeader(userRoles));
    }

    # Returns detailed info for a single review activity.
    # + taskId - The review activity workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Review activity detail as JSON, a not-found error, or an internal server error.
    resource isolated function get review\-activities/[string taskId](
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:NotFound|http:Forbidden|http:InternalServerError {
        return opGetReviewActivity(taskId, parseRolesHeader(userRoles));
    }

    // ── Review Activities — Decisions ──────────────────────────────────────────

    # Proceeds: runs the gated activity, or reruns the failed one, with the original input.
    # + taskId - The review activity workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + return - Review decision info as JSON, or a not-found, forbidden, conflict, or internal server error.
    resource isolated function post review\-activities/[string taskId]/'proceed(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles)
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        return opDecideReviewActivity(taskId, "proceed", (), (), parseRolesHeader(userRoles), userId);
    }

    # Proceeds with modified input: runs the gated/failed activity with the replacement arguments.
    # + taskId - The review activity workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Request body containing the replacement `input` object.
    # + return - Review decision info as JSON, or a bad request, not-found, forbidden, conflict, or internal server error.
    resource isolated function post review\-activities/[string taskId]/proceed\-with\-input(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body)
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        map<json>? input = body["input"] is map<json> ? <map<json>>body["input"] : ();
        return opDecideReviewActivity(taskId, "proceed-with-input", input, (),
                parseRolesHeader(userRoles), userId);
    }

    # Rejects: skips the gated activity, or permanently fails the failed one. Optional `feedback`
    # in the body is relayed to the caller (e.g. surfaced in the failure message).
    # + taskId - The review activity workflow ID.
    # + userId - Optional caller identity from the `x-user-id` header.
    # + userRoles - Optional comma-separated roles from the `x-user-roles` header.
    # + body - Optional request body containing a `feedback` string.
    # + return - Review decision info as JSON, or a not-found, forbidden, conflict, or internal server error.
    resource isolated function post review\-activities/[string taskId]/'reject(
            @http:Header {name: "x-user-id"} string? userId,
            @http:Header {name: "x-user-roles"} string? userRoles,
            @http:Payload map<json> body = {})
            returns json|http:BadRequest|http:NotFound|http:Forbidden|http:Conflict|http:InternalServerError {
        string? feedback = body["feedback"] is string ? <string>body["feedback"] : ();
        return opDecideReviewActivity(taskId, "reject", (), feedback, parseRolesHeader(userRoles), userId);
    }

};
