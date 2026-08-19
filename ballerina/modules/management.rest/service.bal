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
import ballerina/log;
import ballerina/workflow.management;

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
// Every resource below is a thin HTTP adapter over `management:executeCommand`:
// it maps the route to the operation name, packs the query/path/body parameters, carries
// the caller identity from the x-user-* headers, and relays the {httpStatus, body} result.
// One execution path serves HTTP and command callers alike, so the two can never drift.

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

# Names the authentication in force, so the startup log makes an unprotected endpoint
# obvious rather than leaving it to be discovered.
#
# + return - A short description of the enabled schemes
isolated function describeManagementAuth() returns string {
    string[] schemes = [];
    if enableJwtAuth {
        schemes.push("JWT");
    }
    if enableOAuth {
        schemes.push("OAuth2");
    }
    if enableBasicAuth {
        schemes.push("basic");
    }
    if enableApiKey {
        schemes.push("API key");
    }
    if schemes.length() == 0 {
        return "none — the API is open to anyone who can reach the port";
    }
    return string:'join(", ", ...schemes);
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
        log:printDebug("Workflow management REST API is disabled; no port is reserved");
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
    // Say so plainly: this is an inbound surface on a port, and it is what keeps a
    // workflow integration running once `main` returns.
    log:printInfo(string `Workflow management REST API enabled on ` +
            string `${enableTls ? "https" : "http"}://localhost:${port}/workflow` +
            string ` (auth: ${describeManagementAuth()})`);
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

        CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req,
                path.length() > 0 ? path[0] : "", defaultIdentityConfig());
        if identity is http:Forbidden {
            return identity;
        }
        ctx.set(CALLER_IDENTITY_CTX_KEY, identity);

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

# Executes one management operation on behalf of the request's caller and maps the
# result to the HTTP response: the operation's status code and body, verbatim.
#
# + operation - The management operation to run
# + params - Operation parameters, keyed like the route's query/path/body parameters
# + ctx - The request context carrying the caller identity the gateway interceptor resolved
# + return - The operation's REST response
isolated function executeToResponse(management:Operation operation, map<json> params,
        http:RequestContext ctx) returns http:Response {
    CallerIdentity identity = callerIdentityOf(ctx);
    json|management:Error result = management:executeCommand({
        operation: operation,
        params: params,
        identity: {userId: identity.userId, roles: identity.roles}
    });
    http:Response response = new;
    if result is management:Error {
        response.statusCode = statusCodeOf(result);
        response.setJsonPayload(management:toErrorJson(result));
        return response;
    }
    // Starting an instance creates a resource; every other operation reads or
    // mutates an existing one.
    response.statusCode = operation == management:START_INSTANCE
        ? http:STATUS_CREATED : http:STATUS_OK;
    response.setJsonPayload(result);
    return response;
}

# Maps a management error to the HTTP status code that represents it. This module
# owns the HTTP vocabulary; the management module reports *why* an operation
# failed as a protocol-independent `ErrorCode`, and only adapters like this one
# turn that reason into a wire-specific code.
#
# + err - The error a management operation returned
# + return - The corresponding HTTP status code
isolated function statusCodeOf(management:Error err) returns int {
    match management:errorCodeOf(err) {
        management:NOT_FOUND => {
            return http:STATUS_NOT_FOUND;
        }
        management:ACCESS_DENIED => {
            return http:STATUS_FORBIDDEN;
        }
        management:INVALID_REQUEST => {
            return http:STATUS_BAD_REQUEST;
        }
        management:CONFLICT => {
            return http:STATUS_CONFLICT;
        }
        management:INVALID_PAYLOAD => {
            return http:STATUS_UNPROCESSABLE_ENTITY;
        }
    }
    return http:STATUS_INTERNAL_SERVER_ERROR;
}

# Reads the caller identity the gateway interceptor stored in the request context.
#
# + ctx - The request context
# + return - The resolved identity; anonymous (no user, no roles) when absent —
#            the interceptor runs on every request, so absence is defensive only
isolated function callerIdentityOf(http:RequestContext ctx) returns CallerIdentity {
    if !ctx.hasKey(CALLER_IDENTITY_CTX_KEY) {
        return {};
    }
    CallerIdentity|error identity = ctx.getWithType(CALLER_IDENTITY_CTX_KEY);
    return identity is CallerIdentity ? identity : {};
}

isolated function errorBody(string message) returns map<json> {
    return {"error": {"message": message}};
}

# Splits the comma-separated `x-user-roles` header into role names.
#
# + rolesHeader - The header value, or `()` when absent
# + return - The role names; empty when the header is absent or blank
isolated function rolesFromHeader(string? rolesHeader) returns string[] {
    if rolesHeader is () || rolesHeader.trim().length() == 0 {
        return [];
    }
    return re `,`.split(rolesHeader).map(r => r.trim()).filter(r => r.length() > 0);
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
    # + return - The `ManagementGatewayInterceptor` that enforces caller identity and API key auth.
    public function createInterceptors() returns ManagementGatewayInterceptor {
        return new ManagementGatewayInterceptor();
    }

    // ── Definitions ──────────────────────────────────────────────────────────

    resource isolated function get definitions(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:LIST_DEFINITIONS, {}, ctx);
    }

    // ── Workflow Instances — List & Start ─────────────────────────────────────

    resource isolated function get workflows(
            http:RequestContext ctx,
            string? status = (),
            string? workflowType = (),
            string? workflowId = (),
            string? startedBy = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = (),
            string? taskQueue = ()) returns http:Response {
        return executeToResponse(management:LIST_INSTANCES, {
            status: status,
            workflowType: workflowType,
            workflowId: workflowId,
            startedBy: startedBy,
            'limit: 'limit,
            pageToken: pageToken,
            startTimeFrom: startTimeFrom,
            startTimeTo: startTimeTo,
            closeTimeFrom: closeTimeFrom,
            closeTimeTo: closeTimeTo,
            taskQueue: taskQueue
        }, ctx);
    }

    resource isolated function post workflows(
            http:RequestContext ctx,
            @http:Payload map<json> body) returns http:Response {
        return executeToResponse(management:START_INSTANCE, body, ctx);
    }

    // ── Workflow Instance — Latest Run ───────────────────────────────────────
    // These routes omit {runId} and always target the latest (or currently-active) run.
    // The {runId} variants below pin the request to an exact run.

    resource isolated function get workflows/[string workflowId](
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE, {workflowId: workflowId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/suspend(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:SUSPEND_INSTANCE, {workflowId: workflowId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/wake(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:WAKE_INSTANCE, {workflowId: workflowId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/resume(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:RESUME_INSTANCE, {workflowId: workflowId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/terminate(
            http:RequestContext ctx,
            @http:Payload map<json>? body = ()) returns http:Response {
        map<json> params = {workflowId: workflowId};
        if body is map<json> && body["reason"] is string {
            params["reason"] = body["reason"];
        }
        return executeToResponse(management:TERMINATE_INSTANCE, params, ctx);
    }

    resource isolated function post workflows/[string workflowId]/cancel(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:CANCEL_INSTANCE, {workflowId: workflowId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/history(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_HISTORY, {workflowId: workflowId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/activity\-tree(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_ACTIVITY_TREE, {workflowId: workflowId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/execution\-graph(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_EXECUTION_GRAPH, {workflowId: workflowId}, ctx);
    }

    // ── Workflow Instance — Run-pinned ───────────────────────────────────────

    resource isolated function get workflows/[string workflowId]/[string runId](
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/[string runId]/suspend(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:SUSPEND_INSTANCE, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/[string runId]/resume(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:RESUME_INSTANCE, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function post workflows/[string workflowId]/[string runId]/terminate(
            http:RequestContext ctx,
            @http:Payload map<json>? body = ()) returns http:Response {
        map<json> params = {workflowId: workflowId, runId: runId};
        if body is map<json> && body["reason"] is string {
            params["reason"] = body["reason"];
        }
        return executeToResponse(management:TERMINATE_INSTANCE, params, ctx);
    }

    resource isolated function post workflows/[string workflowId]/[string runId]/cancel(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:CANCEL_INSTANCE, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/[string runId]/history(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_HISTORY, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/[string runId]/activity\-tree(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_ACTIVITY_TREE, {workflowId: workflowId, runId: runId}, ctx);
    }

    resource isolated function get workflows/[string workflowId]/[string runId]/execution\-graph(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_INSTANCE_EXECUTION_GRAPH, {workflowId: workflowId, runId: runId}, ctx);
    }

    // ── Human Tasks ──────────────────────────────────────────────────────────

    resource isolated function get human\-tasks(
            http:RequestContext ctx,
            string? status = (),
            string? parentWorkflowId = (),
            string? parentWorkflowType = (),
            string? taskName = (),
            string? userRole = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = (),
            string? taskQueue = ()) returns http:Response {
        return executeToResponse(management:LIST_HUMAN_TASKS, {
            status: status,
            parentWorkflowId: parentWorkflowId,
            parentWorkflowType: parentWorkflowType,
            taskName: taskName,
            userRole: userRole,
            'limit: 'limit,
            pageToken: pageToken,
            startTimeFrom: startTimeFrom,
            startTimeTo: startTimeTo,
            closeTimeFrom: closeTimeFrom,
            closeTimeTo: closeTimeTo,
            taskQueue: taskQueue
        }, ctx);
    }

    resource isolated function get human\-tasks/pending\-count(
            http:RequestContext ctx,
            string? taskQueue = ()) returns http:Response {
        return executeToResponse(management:COUNT_PENDING_HUMAN_TASKS, {taskQueue: taskQueue}, ctx);
    }

    resource isolated function get human\-tasks/[string taskId](
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_HUMAN_TASK, {taskId: taskId}, ctx);
    }

    resource isolated function post human\-tasks/[string taskId]/complete(
            http:RequestContext ctx,
            @http:Payload map<json> body) returns http:Response {
        return executeToResponse(management:COMPLETE_HUMAN_TASK, {taskId: taskId, result: body["result"]}, ctx);
    }

    resource isolated function post human\-tasks/[string taskId]/'fail(
            http:RequestContext ctx,
            @http:Payload map<json> body) returns http:Response {
        map<json> params = {taskId: taskId, reason: body["reason"]};
        if body["details"] is map<json> {
            params["details"] = body["details"];
        }
        return executeToResponse(management:FAIL_HUMAN_TASK, params, ctx);
    }

    // Note: there is deliberately no cancel endpoint for human tasks. A task becomes
    // CANCELED only internally, when its parent workflow closes; admins can terminate
    // the task workflow (TERMINATED) via the workflow terminate endpoint instead.

    // ── Review Activities ────────────────────────────────────────────────────

    resource isolated function get review\-activities(
            http:RequestContext ctx,
            string? status = (),
            string? parentWorkflowId = (),
            string? taskName = (),
            int 'limit = 20,
            string? pageToken = (),
            string? startTimeFrom = (),
            string? startTimeTo = (),
            string? closeTimeFrom = (),
            string? closeTimeTo = (),
            string? taskQueue = ()) returns http:Response {
        return executeToResponse(management:LIST_REVIEW_ACTIVITIES, {
            status: status,
            parentWorkflowId: parentWorkflowId,
            taskName: taskName,
            'limit: 'limit,
            pageToken: pageToken,
            startTimeFrom: startTimeFrom,
            startTimeTo: startTimeTo,
            closeTimeFrom: closeTimeFrom,
            closeTimeTo: closeTimeTo,
            taskQueue: taskQueue
        }, ctx);
    }

    resource isolated function get review\-activities/[string taskId](
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:GET_REVIEW_ACTIVITY, {taskId: taskId}, ctx);
    }

    resource isolated function post review\-activities/[string taskId]/'proceed(
            http:RequestContext ctx) returns http:Response {
        return executeToResponse(management:DECIDE_REVIEW_ACTIVITY, {taskId: taskId, action: "proceed"}, ctx);
    }

    resource isolated function post review\-activities/[string taskId]/proceed\-with\-input(
            http:RequestContext ctx,
            @http:Payload map<json> body) returns http:Response {
        map<json> params = {taskId: taskId, action: "proceed-with-input"};
        if body["input"] is map<json> {
            params["input"] = body["input"];
        }
        return executeToResponse(management:DECIDE_REVIEW_ACTIVITY, params, ctx);
    }

    resource isolated function post review\-activities/[string taskId]/'reject(
            http:RequestContext ctx,
            @http:Payload map<json> body = {}) returns http:Response {
        map<json> params = {taskId: taskId, action: "reject"};
        if body["feedback"] is string {
            params["feedback"] = body["feedback"];
        }
        return executeToResponse(management:DECIDE_REVIEW_ACTIVITY, params, ctx);
    }

    // Retries or fails many failed-activity reviews in one call. The body names the
    // decision and the tasks: `taskIds` for an explicit selection, or
    // `parentWorkflowId` for every pending failure review of one workflow, optionally
    // narrowed by `activityName`. The decision is limited to retry or fail — there is
    // no body field for replacement arguments, so a bulk decision cannot change the
    // payload an activity is retried with.
    //
    // Responds 200 with a per-task report whenever the batch was accepted, including
    // when some tasks were skipped or failed; only a malformed selection is 400.
    resource isolated function post review\-activities/bulk\-retry(
            http:RequestContext ctx,
            @http:Payload map<json> body) returns http:Response {
        map<json> params = {};
        foreach string name in ["action", "taskIds", "parentWorkflowId", "activityName", "feedback"] {
            json value = body[name];
            if value !is () {
                params[name] = value;
            }
        }
        return executeToResponse(management:BULK_RETRY_REVIEW_ACTIVITIES, params, ctx);
    }

};
