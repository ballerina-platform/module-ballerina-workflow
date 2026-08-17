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
import ballerina/test;

// Auth-scheme coverage for the gateway's identity resolution into the request
// context: JWT bearer tokens (claims replace forwarded identity), OAuth2 opaque
// tokens (forwarded identity discarded unless forwarding is trusted), basic auth
// (audit-user defaulting), trusted-forwarding precedence, and per-operation-class
// scope enforcement. The declarative listener auth (signature validation,
// introspection, the 401 challenge) is Ballerina HTTP's own, configured at module
// init — these tests exercise everything this module adds on top, via
// `resolveCallerIdentity` with explicit configurations, since the configurables
// are fixed for a test run.

// ── Test fixtures ────────────────────────────────────────────────────────────────

final CallerIdentityConfig & readonly tokenMode = {
    basicAuthEnabled: false,
    tokenAuthEnabled: true,
    trustForwardedIdentity: false,
    enforceScopes: false,
    userIdClaim: "sub",
    rolesClaim: "roles"
};

// Builds a decodable JWS-format token (header.payload.signature) from claims.
// `jwt:decode` parses without verifying the signature — verification is the
// listener auth layer's job, which is exactly the boundary these tests respect.
isolated function jwtOf(map<json> claims) returns string {
    string header = base64UrlOf(string `{"alg":"RS256","typ":"JWT"}`);
    string payload = base64UrlOf(claims.toJsonString());
    return string `${header}.${payload}.${base64UrlOf("test-signature")}`;
}

isolated function base64UrlOf(string data) returns string {
    string b64 = data.toBytes().toBase64();
    string urlSafe = re`\+`.replaceAll(b64, "-");
    urlSafe = re`/`.replaceAll(urlSafe, "_");
    return re`=`.replaceAll(urlSafe, "");
}

isolated function bearerRequest(string token) returns http:Request {
    http:Request req = new;
    req.setHeader("Authorization", "Bearer " + token);
    return req;
}

// ── JWT bearer tokens ────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testJwtClaimsReplaceSpoofedHeaders() {
    http:Request req = bearerRequest(jwtOf({"sub": "alice", "roles": ["approver", "admin"]}));
    req.setHeader("x-user-id", "spoofed-user");
    req.setHeader("x-user-roles", "spoofed-role");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: "alice", roles: ["approver", "admin"]});
}

@test:Config {groups: ["unit", "auth"]}
function testJwtKeycloakClaimPaths() {
    // Keycloak: identity in preferred_username, roles nested under realm_access.
    CallerIdentityConfig keycloak = {
        basicAuthEnabled: false,
        tokenAuthEnabled: true,
        trustForwardedIdentity: false,
        enforceScopes: false,
        userIdClaim: "preferred_username",
        rolesClaim: "realm_access.roles"
    };
    http:Request req = bearerRequest(jwtOf({
        "sub": "f3b0-1c2d",
        "preferred_username": "alice",
        "realm_access": {"roles": ["approver", "offline_access"]}
    }));
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", keycloak);
    test:assertEquals(identity,
        <CallerIdentity>{userId: "alice", roles: ["approver", "offline_access"]});
}

@test:Config {groups: ["unit", "auth"]}
function testJwtRolesFromCommaSeparatedClaim() {
    http:Request req = bearerRequest(jwtOf({"sub": "bob", "roles": "approver, admin"}));
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: "bob", roles: ["approver", "admin"]});
}

@test:Config {groups: ["unit", "auth"]}
function testJwtWithoutIdentityClaimsYieldsAnonymous() {
    // A valid token with no usable claims must not let spoofed headers through.
    http:Request req = bearerRequest(jwtOf({"iss": "https://idp.example"}));
    req.setHeader("x-user-id", "spoofed-user");
    req.setHeader("x-user-roles", "spoofed-role");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: (), roles: []});
}

@test:Config {groups: ["unit", "auth"]}
function testJwtTrustedForwardingHeaderPrecedence() {
    // Gateway-behind-OAuth topology: a forwarded header wins over the claim,
    // but a claim still fills an identity part the gateway did not forward.
    CallerIdentityConfig trusting = {
        basicAuthEnabled: false,
        tokenAuthEnabled: true,
        trustForwardedIdentity: true,
        enforceScopes: false,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    http:Request req = bearerRequest(jwtOf({"sub": "token-user", "roles": ["approver"]}));
    req.setHeader("x-user-id", "gateway-user");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", trusting);
    test:assertEquals(identity, <CallerIdentity>{userId: "gateway-user", roles: ["approver"]});
}

// ── OAuth2 opaque access tokens ──────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testOpaqueOAuthTokenDiscardsForwardedIdentity() {
    // An opaque (non-JWT) access token carries no claims: forwarded identity is
    // discarded so a caller cannot pair a valid token with a spoofed identity.
    http:Request req = bearerRequest("opaque-access-token-xyz");
    req.setHeader("x-user-id", "spoofed-user");
    req.setHeader("x-user-roles", "spoofed-role");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: (), roles: []});
}

@test:Config {groups: ["unit", "auth"]}
function testOpaqueOAuthTokenTrustedForwardingKeepsIdentity() {
    CallerIdentityConfig trusting = {
        basicAuthEnabled: false,
        tokenAuthEnabled: true,
        trustForwardedIdentity: true,
        enforceScopes: false,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    http:Request req = bearerRequest("opaque-access-token-xyz");
    req.setHeader("x-user-id", "gateway-user");
    req.setHeader("x-user-roles", "approver, admin");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", trusting);
    test:assertEquals(identity,
        <CallerIdentity>{userId: "gateway-user", roles: ["approver", "admin"]});
}

// ── Scope enforcement (token mode) ───────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testScopeEnforcementPerOperationClass() {
    CallerIdentityConfig enforcing = {
        basicAuthEnabled: false,
        tokenAuthEnabled: true,
        trustForwardedIdentity: false,
        enforceScopes: true,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    string viewToken = jwtOf({"sub": "alice", "scope": "openid workflow:view"});

    // A read within the granted class passes and resolves the identity.
    http:Request read = bearerRequest(viewToken);
    read.method = "GET";
    CallerIdentity|http:Forbidden allowed = resolveCallerIdentity(read, "workflows", enforcing);
    test:assertEquals(allowed, <CallerIdentity>{userId: "alice", roles: []});

    // A mutation with only the view scope is forbidden.
    http:Request mutation = bearerRequest(viewToken);
    mutation.method = "POST";
    test:assertTrue(resolveCallerIdentity(mutation, "workflows", enforcing) is http:Forbidden);

    // The manage scope permits the mutation; scp-array tokens (Entra ID) work too.
    http:Request managed = bearerRequest(jwtOf({"sub": "alice", "scp": ["humantask:manage"]}));
    managed.method = "POST";
    test:assertTrue(resolveCallerIdentity(managed, "human-tasks", enforcing) is CallerIdentity);

    // Workflow scopes do not leak into the human-task class.
    http:Request crossClass = bearerRequest(jwtOf({"sub": "alice", "scope": "workflow:manage"}));
    crossClass.method = "POST";
    test:assertTrue(resolveCallerIdentity(crossClass, "human-tasks", enforcing) is http:Forbidden);
}

// A request that carries no bearer token cannot satisfy a scope policy, whichever
// scheme authenticated it. Letting it through would also honor its forwarded
// identity headers, so the caller could choose its own roles.
@test:Config {groups: ["unit", "auth"]}
function testScopeEnforcementDeniesRequestsWithoutABearerToken() {
    CallerIdentityConfig enforcing = {
        basicAuthEnabled: true,
        tokenAuthEnabled: true,
        trustForwardedIdentity: false,
        enforceScopes: true,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    // Basic-authenticated, with identity headers of its own choosing.
    http:Request basic = new;
    basic.method = "GET";
    basic.setHeader("Authorization", "Basic YWxpY2U6c2VjcmV0");
    basic.setHeader(USER_ID_HEADER, "root");
    basic.setHeader(USER_ROLES_HEADER, "admin");
    test:assertTrue(resolveCallerIdentity(basic, "workflows", enforcing) is http:Forbidden,
        "A request with no bearer token must not pass scope enforcement");

    // The same policy with no token-based scheme at all denies rather than skips.
    CallerIdentityConfig noTokenScheme = {
        basicAuthEnabled: true,
        tokenAuthEnabled: false,
        trustForwardedIdentity: false,
        enforceScopes: true,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    test:assertTrue(resolveCallerIdentity(basic, "workflows", noTokenScheme) is http:Forbidden,
        "Scope enforcement without a token scheme must deny, not skip");
}

// Providers disagree on the shape of each scope claim, so both names are read in
// both shapes.
@test:Config {groups: ["unit", "auth"]}
function testScopeClaimIsReadInBothShapes() {
    test:assertEquals(scopesOf({"scope": "workflow:view humantask:manage"}),
        ["workflow:view", "humantask:manage"], "space-separated `scope`");
    test:assertEquals(scopesOf({"scope": ["workflow:view", "humantask:manage"]}),
        ["workflow:view", "humantask:manage"], "array-valued `scope`");
    test:assertEquals(scopesOf({"scp": "workflow:view"}), ["workflow:view"], "string-valued `scp`");
    test:assertEquals(scopesOf({"scp": ["workflow:view"]}), ["workflow:view"], "array-valued `scp`");
    test:assertEquals(scopesOf({}), [], "no scope claim");
}

// ── Basic auth ───────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthDisabledNeverDefaultsAuditUser() {
    CallerIdentityConfig noBasic = {
        basicAuthEnabled: false,
        tokenAuthEnabled: false,
        trustForwardedIdentity: false,
        enforceScopes: false,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    http:Request req = new;
    req.setHeader("Authorization", "Basic b3BzOnMzY3JldCE="); // ops:s3cret!
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(req, "workflows", noBasic);
    test:assertEquals(identity, <CallerIdentity>{userId: (), roles: []});
}

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthMalformedCredentialsIgnored() {
    CallerIdentityConfig basicOnly = {
        basicAuthEnabled: true,
        tokenAuthEnabled: false,
        trustForwardedIdentity: false,
        enforceScopes: false,
        userIdClaim: "sub",
        rolesClaim: "roles"
    };
    // Not base64 at all.
    http:Request malformed = new;
    malformed.setHeader("Authorization", "Basic !!!not-base64!!!");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(malformed, "workflows", basicOnly);
    test:assertEquals(identity, <CallerIdentity>{userId: (), roles: []});

    // Decodable, but no user:password separator ("nocolon").
    http:Request noSeparator = new;
    noSeparator.setHeader("Authorization", "Basic bm9jb2xvbg==");
    identity = resolveCallerIdentity(noSeparator, "workflows", basicOnly);
    test:assertEquals(identity, <CallerIdentity>{userId: (), roles: []});
}

// ── Bearer header edge cases ─────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testMissingOrEmptyBearerTokenUsesForwardedIdentity() {
    // No token to extract from: the forwarded identity stands (the listener's
    // declarative auth is what rejects an unauthenticated request).
    http:Request bare = new;
    bare.setHeader("x-user-id", "gateway-user");
    CallerIdentity|http:Forbidden identity = resolveCallerIdentity(bare, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: "gateway-user", roles: []});

    // "Bearer" with an empty token behaves the same.
    http:Request empty = bearerRequest("");
    empty.setHeader("x-user-id", "gateway-user");
    identity = resolveCallerIdentity(empty, "workflows", tokenMode);
    test:assertEquals(identity, <CallerIdentity>{userId: "gateway-user", roles: []});
}

// ── Request-context hand-off ─────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testCallerIdentityContextRoundTrip() {
    // The interceptor stores the identity in the request context; resources read
    // it back with callerIdentityOf. An untouched context resolves to anonymous.
    http:RequestContext ctx = new;
    ctx.set(CALLER_IDENTITY_CTX_KEY, <CallerIdentity>{userId: "alice", roles: ["approver"]});
    test:assertEquals(callerIdentityOf(ctx), <CallerIdentity>{userId: "alice", roles: ["approver"]});

    http:RequestContext bare = new;
    test:assertEquals(callerIdentityOf(bare), <CallerIdentity>{userId: (), roles: []});
}
