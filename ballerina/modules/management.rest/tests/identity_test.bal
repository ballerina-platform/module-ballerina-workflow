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

// Unit tests for token-identity extraction: claim-path resolution, role and scope
// normalization across IdP shapes (Keycloak, Entra ID, generic), the operation-class
// scope rule, and the header-rewrite behavior of applyCallerIdentity for basic auth.

// A Keycloak-shaped access token payload.
final map<json> & readonly keycloakClaims = {
    "sub": "f3b0-1c2d",
    "preferred_username": "alice",
    "realm_access": {"roles": ["approver", "offline_access"]},
    "scope": "openid workflow:view humantask:manage"
};

// An Entra-ID-shaped access token payload.
final map<json> & readonly entraClaims = {
    "sub": "AAAbbbCCC",
    "roles": ["Task.Approver"],
    "scp": ["workflow:manage"]
};

@test:Config {groups: ["unit"]}
function testClaimAtResolvesNestedPaths() {
    test:assertEquals(claimAt(keycloakClaims, "sub"), <json>"f3b0-1c2d");
    test:assertEquals(claimAt(keycloakClaims, "realm_access.roles"),
        <json>["approver", "offline_access"]);
    test:assertEquals(claimAt(keycloakClaims, "realm_access.missing"), ());
    test:assertEquals(claimAt(keycloakClaims, "missing.roles"), ());
    // A dotted path into a non-map value is absent, not an error.
    test:assertEquals(claimAt(keycloakClaims, "sub.deeper"), ());
}

@test:Config {groups: ["unit"]}
function testRolesClaimShapes() {
    test:assertEquals(stringArrayFromClaim(claimAt(keycloakClaims, "realm_access.roles")),
        <string[]>["approver", "offline_access"]);
    test:assertEquals(stringArrayFromClaim(claimAt(entraClaims, "roles")), <string[]>["Task.Approver"]);
    // Comma-separated string form.
    test:assertEquals(stringArrayFromClaim("approver, admin ,"), <string[]>["approver", "admin"]);
    // Unusable shapes yield ().
    test:assertEquals(stringArrayFromClaim(42), ());
    test:assertEquals(stringArrayFromClaim(()), ());
}

@test:Config {groups: ["unit"]}
function testScopesAcrossIdpShapes() {
    test:assertEquals(scopesOf(keycloakClaims), <string[]>["openid", "workflow:view", "humantask:manage"]);
    test:assertEquals(scopesOf(entraClaims), <string[]>["workflow:manage"]);
    test:assertEquals(scopesOf({"scp": "workflow:view humantask:view"}),
        <string[]>["workflow:view", "humantask:view"]);
    test:assertEquals(scopesOf({}), <string[]>[]);
}

@test:Config {groups: ["unit"]}
function testScopeRulePerOperationClass() {
    // Reads accept view or manage of the route's class; mutations need manage.
    test:assertTrue(scopeAllowed("GET", "workflows", ["workflow:view"]));
    test:assertTrue(scopeAllowed("GET", "workflows", ["workflow:manage"]));
    test:assertFalse(scopeAllowed("POST", "workflows", ["workflow:view"]));
    test:assertTrue(scopeAllowed("POST", "workflows", ["workflow:manage"]));
    // The human-tasks class uses its own pair; workflow scopes don't leak into it.
    test:assertTrue(scopeAllowed("GET", "human-tasks", ["humantask:view"]));
    test:assertFalse(scopeAllowed("GET", "human-tasks", ["workflow:view"]));
    test:assertTrue(scopeAllowed("POST", "human-tasks", ["humantask:manage"]));
    test:assertFalse(scopeAllowed("POST", "human-tasks", ["humantask:view"]));
    // Definitions and review-activities fall into the workflow class.
    test:assertTrue(scopeAllowed("GET", "definitions", ["workflow:view"]));
    test:assertTrue(scopeAllowed("POST", "review-activities", ["workflow:manage"]));
    test:assertFalse(scopeAllowed("POST", "review-activities", []));
}

@test:Config {groups: ["unit"]}
function testBasicAuthUserDefaulting() returns error? {
    // "ops:s3cret!" base64-encoded; basic auth is enabled in this module's defaults.
    http:Request req = new;
    req.setHeader("Authorization", "Basic b3BzOnMzY3JldCE=");
    http:Forbidden? result = applyCallerIdentity(req, "workflows");
    test:assertTrue(result is ());
    test:assertEquals(check req.getHeader("x-user-id"), "ops");

    // An explicit x-user-id is never overwritten by the basic-auth default.
    http:Request explicit = new;
    explicit.setHeader("Authorization", "Basic b3BzOnMzY3JldCE=");
    explicit.setHeader("x-user-id", "audit-user");
    _ = applyCallerIdentity(explicit, "workflows");
    test:assertEquals(check explicit.getHeader("x-user-id"), "audit-user");
}

@test:Config {groups: ["unit"]}
function testNoTokenModeLeavesHeadersUntouched() returns error? {
    // JWT/OAuth2 are disabled in the test configuration, so bearer requests keep
    // whatever identity headers the (trusted) gateway forwarded.
    http:Request req = new;
    req.setHeader("Authorization", "Bearer some-opaque-token");
    req.setHeader("x-user-id", "gateway-user");
    req.setHeader("x-user-roles", "approver");
    http:Forbidden? result = applyCallerIdentity(req, "workflows");
    test:assertTrue(result is ());
    test:assertEquals(check req.getHeader("x-user-id"), "gateway-user");
    test:assertEquals(check req.getHeader("x-user-roles"), "approver");
}
