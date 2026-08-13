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
// Where the caller's identity (user ID + roles) comes from depends on the auth
// scheme:
//
//   API key / basic auth  → trusted-gateway mode: the x-user-id / x-user-roles
//                           headers, as today (basic auth additionally defaults
//                           the user ID to the authenticated username).
//   JWT / OAuth2 (JWT     → token mode: identity is extracted from the token's
//   access tokens)          claims and OVERWRITES the x-user-* headers, so a
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

# Resolves the caller's identity onto the x-user-* headers and enforces scopes.
# Called from the gateway interceptor for every request.
#
# + req - The request; its identity headers are rewritten in token mode
# + firstSegment - The first path segment under the service base path, used to
#                  classify the operation for scope enforcement
# + return - A `403` when scope enforcement is on and the token lacks the
#            required scope; otherwise `()`
isolated function applyCallerIdentity(http:Request req, string firstSegment) returns http:Forbidden? {
    applyBasicAuthUserDefault(req);

    if !(enableJwtAuth || enableOAuth) {
        return ();
    }
    string? token = bearerToken(req);
    if token is () {
        return ();
    }
    [jwt:Header, jwt:Payload]|jwt:Error decoded = jwt:decode(token);
    if decoded is jwt:Error {
        // An opaque (non-JWT) access token: no claims to extract. Headers are kept
        // only in trusted-forwarding mode; otherwise they are dropped so a caller
        // cannot pair an opaque token with spoofed identity headers.
        if !trustForwardedIdentity {
            removeIdentityHeaders(req);
        }
        return ();
    }
    map<json> claims = claimsOf(decoded[1]);

    if !(trustForwardedIdentity && req.hasHeader(USER_ID_HEADER)) {
        json? userId = claimAt(claims, userIdClaim);
        if userId is string && userId.trim().length() > 0 {
            req.setHeader(USER_ID_HEADER, userId);
        } else {
            req.removeHeader(USER_ID_HEADER);
        }
    }
    if !(trustForwardedIdentity && req.hasHeader(USER_ROLES_HEADER)) {
        string[]? roles = stringArrayFromClaim(claimAt(claims, rolesClaim));
        if roles is string[] && roles.length() > 0 {
            req.setHeader(USER_ROLES_HEADER, string:'join(",", ...roles));
        } else {
            req.removeHeader(USER_ROLES_HEADER);
        }
    }

    if enforceScopes && !scopeAllowed(req.method, firstSegment, scopesOf(claims)) {
        return <http:Forbidden>{body: errorBody(
                "Insufficient scope: the token does not permit this operation")};
    }
    return ();
}

const string USER_ID_HEADER = "x-user-id";
const string USER_ROLES_HEADER = "x-user-roles";

// Defaults x-user-id to the basic-auth username when basic auth is enabled and the
// header is absent. The declarative auth layer still validates the credentials; this
// only fills the audit identity (completedBy/decidedBy/startedBy) that would
// otherwise read "unknown".
isolated function applyBasicAuthUserDefault(http:Request req) {
    if !enableBasicAuth || req.hasHeader(USER_ID_HEADER) {
        return;
    }
    string|http:HeaderNotFoundError authHeader = req.getHeader("Authorization");
    if authHeader !is string || !authHeader.startsWith("Basic ") {
        return;
    }
    byte[]|error rawCredentials = array:fromBase64(authHeader.substring(6).trim());
    if rawCredentials is error {
        return;
    }
    string|error credentials = string:fromBytes(rawCredentials);
    if credentials is error {
        return;
    }
    int? separator = credentials.indexOf(":");
    if separator is int && separator > 0 {
        req.setHeader(USER_ID_HEADER, credentials.substring(0, separator));
    }
}

isolated function bearerToken(http:Request req) returns string? {
    string|http:HeaderNotFoundError authHeader = req.getHeader("Authorization");
    if authHeader is string && authHeader.startsWith("Bearer ") {
        string token = authHeader.substring(7).trim();
        return token.length() > 0 ? token : ();
    }
    return ();
}

isolated function removeIdentityHeaders(http:Request req) {
    if req.hasHeader(USER_ID_HEADER) {
        error? result = req.removeHeader(USER_ID_HEADER);
        if result is error {
            // Unreachable: hasHeader was checked.
        }
    }
    if req.hasHeader(USER_ROLES_HEADER) {
        error? result = req.removeHeader(USER_ROLES_HEADER);
        if result is error {
            // Unreachable: hasHeader was checked.
        }
    }
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
    json? scopeClaim = claims["scope"];
    if scopeClaim is string {
        return re`\s+`.split(scopeClaim.trim()).filter(s => s.length() > 0);
    }
    json? scpClaim = claims["scp"];
    if scpClaim is json[] {
        string[] scopes = [];
        foreach json entry in scpClaim {
            if entry is string && entry.trim().length() > 0 {
                scopes.push(entry.trim());
            }
        }
        return scopes;
    }
    if scpClaim is string {
        return re`\s+`.split(scpClaim.trim()).filter(s => s.length() > 0);
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
