// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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
import ballerina/test;

// The gateway interceptor's admission decision (`gateRequest`) with a JWT in a
// custom `jwtAuthHeader`: the token is validated BEFORE any identity is derived
// from it, a presented-but-invalid token is refused whatever else the request
// carries, and `Authorization` is only read for identity when a declarative
// scheme validates it. Tokens are HS256-signed with a test secret so the real
// `http:ListenerJwtAuthHandler` accepts or rejects them.

const string JWT_HEADER = "X-JWT-Assertion";
const string TEST_SECRET = "gateway-test-secret-0123456789";
const string FORGED_SECRET = "somebody-elses-secret-0123456789";
const string API_KEY = "test-api-key";

final http:ListenerJwtAuthHandler testJwtHandler = new ({
    issuer: "test-issuer",
    audience: "workflow",
    signatureConfig: {secret: TEST_SECRET}
});

isolated function signedJwt(string user, json roles, string secret = TEST_SECRET,
        map<json> extraClaims = {}) returns string {
    map<json> claims = extraClaims.clone();
    claims["roles"] = roles;
    return checkpanic jwt:issue({
        issuer: "test-issuer",
        audience: "workflow",
        username: user,
        customClaims: claims,
        signatureConfig: {algorithm: jwt:HS256, config: secret}
    });
}

isolated function gatewayConfig(boolean basicAuthEnabled = false, boolean oauthEnabled = false,
        boolean apiKeyEnabled = false, boolean enforceScopes = false) returns GatewayConfig => {
    identity: {
        basicAuthEnabled,
        tokenAuthEnabled: oauthEnabled,
        trustForwardedIdentity: false,
        enforceScopes,
        userIdClaim: "sub",
        rolesClaim: "roles"
    },
    declarativeAuthEnabled: basicAuthEnabled || oauthEnabled,
    jwtHeader: JWT_HEADER,
    customHeaderJwtHandler: testJwtHandler,
    apiKeyEnabled,
    apiKeyHeader: "x-api-key",
    apiKeyValue: API_KEY
};

isolated function requestWith(map<string> headers) returns http:Request {
    http:Request req = new;
    foreach [string, string] [name, value] in headers.entries() {
        req.setHeader(name, value);
    }
    return req;
}

// ── Valid tokens ─────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testCustomHeaderJwtIdentityComesFromTheValidatedToken() {
    CallerIdentity expected = {userId: "alice", roles: ["approver"], identitySource: "verified"};

    // Bare token, as gateways forward it.
    http:Request bare = requestWith({[JWT_HEADER]: signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(bare, "workflows", gatewayConfig()), expected);

    // `Bearer <token>` and a lower-cased header name are accepted too.
    http:Request prefixed = requestWith({"x-jwt-assertion": "Bearer " + signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(prefixed, "workflows", gatewayConfig()), expected);

    // Spoofed x-user-* headers alongside a valid token are discarded.
    http:Request spoofed = requestWith({
        [JWT_HEADER]: signedJwt("alice", ["approver"]),
        "x-user-id": "spoofed-user",
        "x-user-roles": "admin"
    });
    test:assertEquals(gateRequest(spoofed, "workflows", gatewayConfig()), expected);
}

@test:Config {groups: ["unit", "auth"]}
function testCustomHeaderJwtScopesAreEnforcedFromTheValidatedToken() {
    GatewayConfig scoped = gatewayConfig(enforceScopes = true);
    http:Request allowed = requestWith({[JWT_HEADER]: signedJwt("alice", [], extraClaims = {"scope": "workflow:view"})});
    allowed.method = "GET";
    CallerIdentity|http:Unauthorized|http:Forbidden gate = gateRequest(allowed, "workflows", scoped);
    test:assertTrue(gate is CallerIdentity, "a token with the view scope must read workflows");
    test:assertEquals((<CallerIdentity>gate).scopes, ["workflow:view"]);

    http:Request denied = requestWith({[JWT_HEADER]: signedJwt("alice", [], extraClaims = {"scope": "workflow:view"})});
    denied.method = "POST";
    test:assertTrue(gateRequest(denied, "workflows", scoped) is http:Forbidden,
            "a token with only the view scope must not start workflows");
}

// ── Invalid tokens are refused whatever else the request carries ─────────────────

@test:Config {groups: ["unit", "auth"]}
function testForgedCustomHeaderJwtIsRefusedDespiteValidBasicAuth() {
    // The reviewer's case: Basic auth is on (the default), the caller presents valid
    // Basic credentials plus a JWT signed with another key claiming to be admin.
    http:Request req = requestWith({
        "Authorization": "Basic b3BzOnMzY3JldCE=", // ops:s3cret!
        [JWT_HEADER]: signedJwt("admin", ["admin"], FORGED_SECRET)
    });
    CallerIdentity|http:Unauthorized|http:Forbidden gate = gateRequest(req, "workflows",
            gatewayConfig(basicAuthEnabled = true));
    test:assertTrue(gate is http:Unauthorized, "a forged custom-header JWT must be refused, not fall through");
}

@test:Config {groups: ["unit", "auth"]}
function testForgedCustomHeaderJwtIsRefusedDespiteValidApiKey() {
    http:Request req = requestWith({
        "x-api-key": API_KEY,
        [JWT_HEADER]: signedJwt("admin", ["admin"], FORGED_SECRET)
    });
    CallerIdentity|http:Unauthorized|http:Forbidden gate = gateRequest(req, "workflows",
            gatewayConfig(apiKeyEnabled = true));
    test:assertTrue(gate is http:Unauthorized, "a forged custom-header JWT must be refused, not fall through");
}

@test:Config {groups: ["unit", "auth"]}
function testUnsignedOrMalformedCustomHeaderJwtIsRefused() {
    // Decodable but unsigned (what `jwt:decode` alone would have accepted).
    http:Request unsigned = requestWith({[JWT_HEADER]: jwtOf({"sub": "admin", "roles": ["admin"]})});
    test:assertTrue(gateRequest(unsigned, "workflows", gatewayConfig()) is http:Unauthorized);

    // Not a token at all.
    http:Request garbage = requestWith({[JWT_HEADER]: "Basic b3BzOnMzY3JldCE="});
    test:assertTrue(gateRequest(garbage, "workflows", gatewayConfig(basicAuthEnabled = true)) is http:Unauthorized);

    // Wrong audience, signed with the right key.
    string wrongAudience = checkpanic jwt:issue({
        issuer: "test-issuer",
        audience: "someone-else",
        username: "alice",
        signatureConfig: {algorithm: jwt:HS256, config: TEST_SECRET}
    });
    http:Request other = requestWith({[JWT_HEADER]: wrongAudience});
    test:assertTrue(gateRequest(other, "workflows", gatewayConfig()) is http:Unauthorized);
}

// ── Custom header absent ─────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testMissingCustomHeaderJwtIsRefusedOnlyWhenNothingElseCouldAdmit() {
    http:Request req = requestWith({"x-user-id": "gateway-user"});

    // JWT is the sole scheme: 401.
    test:assertTrue(gateRequest(req, "workflows", gatewayConfig()) is http:Unauthorized);

    // Basic auth is also on: the request falls through to the declarative layer.
    http:Request basic = requestWith({"Authorization": "Basic b3BzOnMzY3JldCE="});
    test:assertEquals(gateRequest(basic, "workflows", gatewayConfig(basicAuthEnabled = true)),
            <CallerIdentity>{userId: "ops", roles: [], identitySource: "verified"});

    // API key is also on: a valid key admits, an invalid one is refused.
    http:Request keyed = requestWith({"x-api-key": API_KEY, "x-user-id": "gateway-user"});
    test:assertEquals(gateRequest(keyed, "workflows", gatewayConfig(apiKeyEnabled = true)),
            <CallerIdentity>{userId: "gateway-user", roles: []});
    http:Request badKey = requestWith({"x-api-key": "nope"});
    test:assertTrue(gateRequest(badKey, "workflows", gatewayConfig(apiKeyEnabled = true)) is http:Unauthorized);
}

@test:Config {groups: ["unit", "auth"]}
function testAuthorizationBearerIsOnlyTrustedWhenValidatedDownstream() {
    string forged = signedJwt("admin", ["admin"], FORGED_SECRET);

    // OAuth off: nothing validates an Authorization bearer token in custom-header
    // mode, so its claims are not read even though the API key admits the request.
    http:Request keyed = requestWith({"x-api-key": API_KEY, "Authorization": "Bearer " + forged});
    test:assertEquals(gateRequest(keyed, "workflows", gatewayConfig(apiKeyEnabled = true)),
            <CallerIdentity>{userId: (), roles: []});

    // OAuth on: introspection validates that token after the interceptor, so its
    // claims are read as before.
    http:Request oauth = requestWith({"Authorization": "Bearer " + signedJwt("bob", ["viewer"])});
    test:assertEquals(gateRequest(oauth, "workflows", gatewayConfig(oauthEnabled = true)),
            <CallerIdentity>{userId: "bob", roles: ["viewer"], identitySource: "verified"});
}
