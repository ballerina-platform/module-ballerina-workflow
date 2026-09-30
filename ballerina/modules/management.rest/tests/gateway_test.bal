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

// Reads the [[ballerina.auth.users]] entry in tests/Config.toml (ops / s3cret!).
final http:ListenerFileUserStoreBasicAuthHandler testBasicHandler = new ({});
const string OPS_BASIC = "Basic b3BzOnMzY3JldCE="; // ops:s3cret!
const string OPS_BAD_BASIC = "Basic b3BzOndyb25n"; // ops:wrong

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

// The custom-header-mode gateway configuration, derived the way production derives
// it (`authModeOf`) so the fixture cannot drift from `defaultGatewayConfig`.
isolated function gatewayConfig(boolean basicAuthEnabled = false, boolean oauthEnabled = false,
        boolean apiKeyEnabled = false, boolean enforceScopes = false) returns GatewayConfig {
    AuthMode mode = authModeOf(basicAuthEnabled, true, JWT_HEADER, oauthEnabled);
    return {
        identity: {
            basicAuthEnabled,
            tokenAuthEnabled: mode.authorizationBearerValidated,
            trustForwardedIdentity: false,
            enforceScopes,
            userIdClaim: "sub",
            rolesClaim: "roles"
        },
        declarativeAuthEnabled: mode.declarativeAuthEnabled,
        jwtHeader: JWT_HEADER,
        customHeaderJwtHandler: mode.customHeaderJwt ? testJwtHandler : (),
        basicAuthHandler: mode.interceptorBasic ? testBasicHandler : (),
        apiKeyEnabled,
        apiKeyHeader: "x-api-key",
        apiKeyValue: API_KEY
    };
}

isolated function requestWith(map<string> headers) returns http:Request {
    http:Request req = new;
    foreach [string, string] [name, value] in headers.entries() {
        req.setHeader(name, value);
    }
    return req;
}

// ── Where each scheme is enforced (the production derivation) ────────────────────

@test:Config {groups: ["unit", "auth"]}
function testAuthModeOfPlacesEachScheme() {
    // Defaults: basic only, declarative.
    test:assertEquals(authModeOf(true, false, "Authorization", false), <AuthMode>{
        customHeaderJwt: false, declarativeJwt: false, interceptorBasic: false, declarativeBasic: true,
        declarativeAuthEnabled: true, authorizationBearerValidated: false
    });
    // JWT on the standard header (any case): declarative, and Authorization bearers are validated.
    test:assertEquals(authModeOf(false, true, "authorization", false), <AuthMode>{
        customHeaderJwt: false, declarativeJwt: true, interceptorBasic: false, declarativeBasic: false,
        declarativeAuthEnabled: true, authorizationBearerValidated: true
    });
    // JWT in a custom header with basic left at its default: both move to the interceptor,
    // nothing declarative remains, and an Authorization bearer is NOT trusted.
    test:assertEquals(authModeOf(true, true, JWT_HEADER, false), <AuthMode>{
        customHeaderJwt: true, declarativeJwt: false, interceptorBasic: true, declarativeBasic: false,
        declarativeAuthEnabled: false, authorizationBearerValidated: false
    });
    // Custom header plus OAuth2: introspection validates Authorization declaratively.
    test:assertEquals(authModeOf(false, true, JWT_HEADER, true), <AuthMode>{
        customHeaderJwt: true, declarativeJwt: false, interceptorBasic: false, declarativeBasic: false,
        declarativeAuthEnabled: true, authorizationBearerValidated: true
    });
    // A custom header name with JWT auth OFF is inert: nothing reads that header.
    test:assertEquals(authModeOf(true, false, JWT_HEADER, true), <AuthMode>{
        customHeaderJwt: false, declarativeJwt: false, interceptorBasic: false, declarativeBasic: true,
        declarativeAuthEnabled: true, authorizationBearerValidated: true
    });
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
        "Authorization": OPS_BASIC,
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
    test:assertTrue(gateRequest(garbage, "workflows", gatewayConfig(oauthEnabled = true)) is http:Unauthorized);

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

// ── Basic auth alongside a custom-header JWT ─────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthIsValidatedInTheInterceptorAlongsideACustomHeaderJwt() {
    GatewayConfig withBasic = gatewayConfig(basicAuthEnabled = true);

    // The motivating topology with the default enableBasicAuth = true: a JWT-only request passes.
    http:Request jwtOnly = requestWith({[JWT_HEADER]: signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(jwtOnly, "workflows", withBasic),
            <CallerIdentity>{userId: "alice", roles: ["approver"], identitySource: "verified"});

    // Basic alone passes too, validated here against [[ballerina.auth.users]].
    http:Request basicOnly = requestWith({"Authorization": OPS_BASIC});
    test:assertEquals(gateRequest(basicOnly, "workflows", withBasic),
            <CallerIdentity>{userId: "ops", roles: [], identitySource: "verified"});

    // Wrong password: refused, even with a valid JWT beside it.
    http:Request badBasic = requestWith({"Authorization": OPS_BAD_BASIC});
    test:assertTrue(gateRequest(badBasic, "workflows", withBasic) is http:Unauthorized);
    http:Request badBasicWithJwt = requestWith({
        "Authorization": OPS_BAD_BASIC,
        [JWT_HEADER]: signedJwt("alice", ["approver"])
    });
    test:assertTrue(gateRequest(badBasicWithJwt, "workflows", withBasic) is http:Unauthorized);

    // Both valid: the JWT's claims are the identity.
    http:Request both = requestWith({"Authorization": OPS_BASIC, [JWT_HEADER]: signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(both, "workflows", withBasic),
            <CallerIdentity>{userId: "alice", roles: ["approver"], identitySource: "verified"});

    // No credential at all: nothing declarative remains to admit it.
    http:Request none = requestWith({"x-user-id": "gateway-user"});
    test:assertTrue(gateRequest(none, "workflows", withBasic) is http:Unauthorized);
}

// ── Custom header absent ─────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testMissingCustomHeaderJwtIsRefusedOnlyWhenNothingElseCouldAdmit() {
    http:Request req = requestWith({"x-user-id": "gateway-user"});

    // JWT is the sole scheme: 401.
    test:assertTrue(gateRequest(req, "workflows", gatewayConfig()) is http:Unauthorized);

    // OAuth2 is also on: the request falls through to the declarative layer.
    http:Request oauth = requestWith({"x-user-id": "gateway-user"});
    test:assertEquals(gateRequest(oauth, "workflows", gatewayConfig(oauthEnabled = true)),
            <CallerIdentity>{userId: "gateway-user", roles: []});

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
