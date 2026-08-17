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
import ballerina/jwt;
import ballerina/lang.array;

// ================================================================================
// CALLER IDENTITY
// ================================================================================
// The gateway interceptor resolves the caller's identity ONCE per request and sets
// it into the `http:RequestContext` (spec §8.1.11) under CALLER_IDENTITY_CTX_KEY;
// resources read the typed value from the context — the request itself is never
// mutated. Where the identity comes from depends on the auth scheme:
//
//   API key / basic auth  → trusted-gateway mode: the x-user-id / x-user-roles
//                           headers are read as forwarded identity (basic auth
//                           additionally defaults the user ID to the
//                           authenticated username).
//   JWT / OAuth2 (JWT     → token mode: identity is extracted from the token's
//   access tokens)          claims and REPLACES any forwarded headers, so a
//                           client cannot spoof another identity alongside a
//                           valid token. `trustForwardedIdentity = true` restores
//                           header precedence for gateway-behind-OAuth topologies.
//
// Extraction happens in the request interceptor BEFORE the listener's declarative
// auth validates the token signature — an invalid token still gets its 401 from
// the auth layer and never reaches a resource, so nothing is trusted early.
// Scopes stay orthogonal to roles: scopes authorize operation classes (view vs
// manage, workflow vs human task), while per-task eligibility remains the
// userRoles-vs-caller-roles intersection.

# Claim holding the caller's user ID in token mode. Dotted paths address nested
# claims. Common values: `sub` (default), `preferred_username`, `email`.
configurable string userIdClaim = "sub";

# Claim holding the caller's roles in token mode. Dotted paths address nested
# claims — e.g. `realm_access.roles` for Keycloak, `roles` or `groups` for
# Entra ID. The claim value may be an array of strings or a comma-separated string.
configurable string rolesClaim = "roles";

# When `true`, `x-user-id` / `x-user-roles` headers sent by the client take
# precedence over token claims — for topologies where a trusted gateway
# terminates OAuth and forwards identity headers. Leave `false` (the default)
# so a valid token's claims always win and headers cannot spoof identity.
configurable boolean trustForwardedIdentity = false;

# Enforces OAuth scopes per operation class in token mode. Requires JWT or
# OAuth2 auth to be enabled. Scopes are read from the `scope` claim
# (space-delimited, RFC 6749) or the `scp` claim (array or string).
configurable boolean enforceScopes = false;

# Scope permitting read operations on workflows (list, get, history, graphs).
configurable string scopeWorkflowView = "workflow:view";

# Scope permitting workflow mutations (start, suspend, resume, terminate, cancel).
# Implies `scopeWorkflowView` for the workflow routes.
configurable string scopeWorkflowManage = "workflow:manage";

# Scope permitting read operations on human tasks.
configurable string scopeHumanTaskView = "humantask:view";

# Scope permitting human-task mutations (complete, fail).
# Implies `scopeHumanTaskView` for the human-task routes.
configurable string scopeHumanTaskManage = "humantask:manage";

# The caller's resolved identity. The gateway interceptor sets it into the request
# context; resources read it from there (see `callerIdentityOf`).
#
# + userId - The caller's user ID, or `()` when no scheme established one
# + roles - The caller's role names; empty when none were established
type CallerIdentity record {|
    string? userId = ();
    string[] roles = [];
|};

# The request-context key the resolved `CallerIdentity` is stored under.
const string CALLER_IDENTITY_CTX_KEY = "workflowCallerIdentity";

# The configuration snapshot driving caller-identity resolution. The gateway
# interceptor fills it from the module's configurables (`defaultIdentityConfig`);
# tests construct it directly so every auth mode (basic, JWT, OAuth2-opaque,
# trusted-forwarding, scope enforcement) is exercisable in one test run even though
# the configurables are fixed at module init.
#
# + basicAuthEnabled - Whether basic auth is enabled (drives the audit-user default)
# + tokenAuthEnabled - Whether a token scheme (JWT or OAuth2) is enabled (token mode)
# + trustForwardedIdentity - Whether forwarded x-user-* headers beat token claims
# + enforceScopes - Whether OAuth scopes gate operation classes
# + userIdClaim - Claim (dotted path) holding the user ID
# + rolesClaim - Claim (dotted path) holding the roles
type CallerIdentityConfig record {|
    boolean basicAuthEnabled;
    boolean tokenAuthEnabled;
    boolean trustForwardedIdentity;
    boolean enforceScopes;
    string userIdClaim;
    string rolesClaim;
|};

# The identity-resolution configuration built from the module's configurables.
# + return - The configuration the gateway interceptor resolves identities with
isolated function defaultIdentityConfig() returns CallerIdentityConfig => {
    basicAuthEnabled: enableBasicAuth,
    tokenAuthEnabled: enableJwtAuth || enableOAuth,
    trustForwardedIdentity,
    enforceScopes,
    userIdClaim,
    rolesClaim
};

# Resolves the caller's identity from the request and enforces scopes. Called from
# the gateway interceptor, which stores the result in the request context; the
# request itself is never mutated.
#
# + req - The request
# + firstSegment - The first path segment under the service base path, used to
#                  classify the operation for scope enforcement
# + cfg - The identity-resolution configuration
# + return - The resolved identity, or a `403` when scope enforcement is on and
#            the token lacks the required scope
isolated function resolveCallerIdentity(http:Request req, string firstSegment,
        CallerIdentityConfig cfg) returns CallerIdentity|http:Forbidden {
    boolean hasForwardedUser = req.hasHeader(USER_ID_HEADER);
    boolean hasForwardedRoles = req.hasHeader(USER_ROLES_HEADER);
    CallerIdentity identity = forwardedIdentity(req);

    if cfg.basicAuthEnabled && identity.userId is () {
        string? basicUser = basicAuthUsername(req);
        if basicUser is string {
            identity.userId = basicUser;
        }
    }

    if !cfg.tokenAuthEnabled {
        if cfg.enforceScopes {
            // Scope enforcement is configured but no token-based scheme can carry
            // scopes: deny rather than let the request through unchecked.
            return <http:Forbidden>{body: errorBody(
                    "Scope enforcement requires a token-based authentication scheme")};
        }
        return identity;
    }
    string? token = bearerToken(req);
    if token is () {
        // A request authenticated by another enabled scheme (e.g. Basic) carries no
        // scopes. Under scope enforcement it cannot satisfy the policy, and honoring
        // its forwarded identity headers would let it choose its own roles.
        if cfg.enforceScopes {
            return <http:Forbidden>{body: errorBody(
                    "Scope enforcement requires a bearer token; this request carries none")};
        }
        return identity;
    }
    [jwt:Header, jwt:Payload]|jwt:Error decoded = jwt:decode(token);
    if decoded is jwt:Error {
        // An opaque (non-JWT) access token: no claims to extract. When scope enforcement
        // is on, an opaque token cannot prove its scopes here, so it is rejected rather
        // than silently skipping the check the deployment asked for. (Scope-checking
        // opaque tokens requires introspection — not decode — and is not supported yet.)
        if cfg.enforceScopes {
            return <http:Forbidden>{body: errorBody(
                    "Scope enforcement requires a JWT access token; opaque tokens are not supported")};
        }
        // Forwarded identity is honored only in trusted-forwarding mode; otherwise it is
        // discarded so a caller cannot pair a valid token with spoofed identity headers.
        return cfg.trustForwardedIdentity ? identity : <CallerIdentity>{};
    }
    map<json> claims = claimsOf(decoded[1]);

    if !(cfg.trustForwardedIdentity && hasForwardedUser) {
        json? userId = claimAt(claims, cfg.userIdClaim);
        identity.userId = userId is string && userId.trim().length() > 0 ? userId : ();
    }
    if !(cfg.trustForwardedIdentity && hasForwardedRoles) {
        string[]? roles = stringArrayFromClaim(claimAt(claims, cfg.rolesClaim));
        identity.roles = roles is string[] ? roles : [];
    }

    if cfg.enforceScopes && !scopeAllowed(req.method, firstSegment, scopesOf(claims)) {
        return <http:Forbidden>{body: errorBody(
                "Insufficient scope: the token does not permit this operation")};
    }
    return identity;
}

const string USER_ID_HEADER = "x-user-id";
const string USER_ROLES_HEADER = "x-user-roles";

# Reads the identity a trusted gateway forwarded via the x-user-* headers.
#
# + req - The request
# + return - The forwarded identity; fields are absent/empty when not forwarded
isolated function forwardedIdentity(http:Request req) returns CallerIdentity {
    string|http:HeaderNotFoundError userId = req.getHeader(USER_ID_HEADER);
    string|http:HeaderNotFoundError roles = req.getHeader(USER_ROLES_HEADER);
    return {
        userId: userId is string && userId.trim().length() > 0 ? userId : (),
        roles: roles is string ? rolesFromHeader(roles) : []
    };
}

// Extracts the basic-auth username for the audit identity (completedBy/decidedBy/
// startedBy that would otherwise read "unknown"). The declarative auth layer still
// validates the credentials; callers gate this on basic auth being enabled and on
// no identity having been forwarded.
isolated function basicAuthUsername(http:Request req) returns string? {
    string|http:HeaderNotFoundError authHeader = req.getHeader("Authorization");
    if authHeader !is string || !authHeader.startsWith("Basic ") {
        return ();
    }
    byte[]|error rawCredentials = array:fromBase64(authHeader.substring(6).trim());
    if rawCredentials is error {
        return ();
    }
    string|error credentials = string:fromBytes(rawCredentials);
    if credentials is error {
        return ();
    }
    int? separator = credentials.indexOf(":");
    if separator is int && separator > 0 {
        return credentials.substring(0, separator);
    }
    return ();
}

isolated function bearerToken(http:Request req) returns string? {
    string|http:HeaderNotFoundError authHeader = req.getHeader("Authorization");
    if authHeader is string && authHeader.startsWith("Bearer ") {
        string token = authHeader.substring(7).trim();
        return token.length() > 0 ? token : ();
    }
    return ();
}

isolated function claimsOf(jwt:Payload payload) returns map<json> {
    json raw = payload.toJson();
    return raw is map<json> ? raw : {};
}

# Resolves a (possibly nested) claim by a dotted path — `realm_access.roles`
# addresses `{"realm_access": {"roles": [...]}}`.
#
# + claims - The token's claims
# + path - The dotted claim path
# + return - The claim value, or `()` when any path segment is missing
isolated function claimAt(map<json> claims, string path) returns json? {
    json current = claims;
    foreach string segment in re`\.`.split(path) {
        if current !is map<json> || !current.hasKey(segment) {
            return ();
        }
        current = current.get(segment);
    }
    return current;
}

# Normalizes a roles-claim value: an array of strings is taken as-is, a string is
# split on commas; anything else yields `()`.
#
# + value - The claim value
# + return - The role names, or `()` when the value has no usable shape
isolated function stringArrayFromClaim(json? value) returns string[]? {
    if value is json[] {
        string[] roles = [];
        foreach json entry in value {
            if entry is string && entry.trim().length() > 0 {
                roles.push(entry.trim());
            }
        }
        return roles;
    }
    if value is string {
        string[] roles = re`,`.split(value).map(r => r.trim()).filter(r => r.length() > 0);
        return roles;
    }
    return ();
}

# Extracts OAuth scopes from the `scope` claim (space-delimited string, RFC 6749)
# or the `scp` claim (array of strings, or a space-delimited string).
#
# + claims - The token's claims
# + return - The scope names; empty when the token carries none
isolated function scopesOf(map<json> claims) returns string[] {
    // Providers differ on both the claim name and its shape: `scope` is usually a
    // space-separated string and `scp` usually an array, but either may take either
    // form, so both are accepted in both shapes.
    string[] scopes = claimScopes(claims["scope"]);
    if scopes.length() > 0 {
        return scopes;
    }
    return claimScopes(claims["scp"]);
}

isolated function claimScopes(json? claim) returns string[] {
    if claim is string {
        return re`\s+`.split(claim.trim()).filter(s => s.length() > 0);
    }
    if claim is json[] {
        string[] scopes = [];
        foreach json entry in claim {
            if entry is string && entry.trim().length() > 0 {
                scopes.push(entry.trim());
            }
        }
        return scopes;
    }
    return [];
}

# Decides whether the token's scopes permit an operation. Routes under
# `human-tasks` form the human-task class; everything else is the workflow class.
# Reads (GET/HEAD/OPTIONS) accept the class's view OR manage scope; mutations
# require the manage scope.
#
# + method - The HTTP method
# + firstSegment - The first path segment under the service base path
# + scopes - The token's scopes
# + return - `true` when the operation is permitted
isolated function scopeAllowed(string method, string firstSegment, string[] scopes) returns boolean {
    boolean isRead = method == "GET" || method == "HEAD" || method == "OPTIONS";
    string viewScope = firstSegment == "human-tasks" ? scopeHumanTaskView : scopeWorkflowView;
    string manageScope = firstSegment == "human-tasks" ? scopeHumanTaskManage : scopeWorkflowManage;
    foreach string scope in scopes {
        if scope == manageScope || (isRead && scope == viewScope) {
            return true;
        }
    }
    return false;
}
