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

// The gateway interceptor's admission decision (`gateRequest`), the only place the
// management API enforces authentication: every presented credential is validated
// BEFORE any identity is derived from it, a presented-but-invalid credential is
// refused whatever else the request carries, and a request with no valid credential
// is refused whenever a scheme is enabled. JWTs are HS256-signed with a test secret
// so the real `http:ListenerJwtAuthHandler` accepts or rejects them; OAuth2 tokens are
// introspected by a mock endpoint.

const string JWT_HEADER = "X-JWT-Assertion";
const int INTROSPECTION_PORT = 9798;

final http:ListenerJwtAuthHandler testJwtHandler = new ({
    issuer: "test-issuer",
    audience: "workflow",
    signatureConfig: {secret: testJwtSecret}
});

final http:ListenerFileUserStoreBasicAuthHandler testBasicHandler = new ({});

final http:ListenerOAuth2Handler testOAuth2Handler = new ({
    url: string `http://localhost:${INTROSPECTION_PORT}/oauth2/introspect`
});

service /oauth2 on new http:Listener(INTROSPECTION_PORT) {
    resource function post introspect(http:Request req) returns json|error {
        string form = check req.getTextPayload();
        return {active: !form.includes(revokedAccessToken)};
    }
}

isolated function signedJwt(string user, json roles, string secret = testJwtSecret,
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

isolated function enables(Scheme[] schemes, string scheme) returns boolean => schemes.some(s => s == scheme);

// The gateway configuration built the way `defaultGatewayConfig` builds it, with test handlers.
isolated function gatewayConfig(Scheme[] schemes = [WITH_JWT], string jwtHeader = JWT_HEADER,
        boolean enforceScopes = false) returns GatewayConfig {
    boolean jwt = enables(schemes, WITH_JWT);
    boolean basic = enables(schemes, WITH_BASIC);
    boolean oauth2 = enables(schemes, WITH_OAUTH2);
    return {
        identity: {
            basicAuthEnabled: basic,
            tokenAuthEnabled: authorizationBearerValidated(jwt, jwtHeader, oauth2),
            trustForwardedIdentity: false,
            enforceScopes,
            userIdClaim: "sub",
            rolesClaim: "roles"
        },
        jwtHeader,
        jwtHandler: jwt ? testJwtHandler : (),
        basicAuthHandler: basic ? testBasicHandler : (),
        oauth2Handler: oauth2 ? testOAuth2Handler : (),
        apiKeyEnabled: enables(schemes, WITH_API_KEY),
        apiKeyHeader,
        apiKeyValue: testApiKey
    };
}

isolated function requestWith(map<string> headers) returns http:Request {
    http:Request req = new;
    foreach [string, string] [name, value] in headers.entries() {
        req.setHeader(name, value);
    }
    return req;
}

@test:Config {groups: ["unit", "auth"]}
function testAuthorizationBearerIsValidatedOnlyByJwtOnTheStandardHeaderOrOAuth2() {
    test:assertFalse(authorizationBearerValidated(false, AUTHORIZATION_HEADER, false));
    test:assertTrue(authorizationBearerValidated(true, "authorization", false));
    test:assertFalse(authorizationBearerValidated(true, JWT_HEADER, false));
    test:assertTrue(authorizationBearerValidated(true, JWT_HEADER, true));
    test:assertTrue(authorizationBearerValidated(false, AUTHORIZATION_HEADER, true));
}

// ── A request with no credential ─────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testRequestWithoutCredentialsIsRefusedByEveryScheme() {
    map<GatewayConfig> configs = {
        "jwt on Authorization": gatewayConfig(jwtHeader = AUTHORIZATION_HEADER),
        "jwt on a custom header": gatewayConfig(),
        "basic": gatewayConfig([WITH_BASIC]),
        "oauth2": gatewayConfig([WITH_OAUTH2]),
        "api key": gatewayConfig([WITH_API_KEY]),
        "jwt + basic + oauth2 + api key": gatewayConfig([WITH_JWT, WITH_BASIC, WITH_OAUTH2, WITH_API_KEY], jwtHeader = AUTHORIZATION_HEADER)
    };
    foreach [string, GatewayConfig] [name, cfg] in configs.entries() {
        http:Request req = requestWith({"x-user-id": "spoofed-user", "x-user-roles": "admin"});
        test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized,
                name + ": a request without credentials must be refused");
    }
}

@test:Config {groups: ["unit", "auth"]}
function testNoSchemeLeavesTheApiOpen() {
    http:Request req = requestWith({"x-user-id": "gateway-user"});
    test:assertEquals(gateRequest(req, "workflows", gatewayConfig([])),
            <CallerIdentity>{userId: "gateway-user", roles: []});
}

// ── JWT on the standard Authorization header ─────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testJwtOnAuthorizationIsValidated() {
    GatewayConfig cfg = gatewayConfig(jwtHeader = AUTHORIZATION_HEADER);
    http:Request valid = requestWith({"Authorization": BEARER_PREFIX + signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(valid, "workflows", cfg),
            <CallerIdentity>{userId: "alice", roles: ["approver"], identitySource: "verified"});

    http:Request forged = requestWith({"Authorization": BEARER_PREFIX + signedJwt("admin", ["admin"], forgedJwtSecret)});
    test:assertTrue(gateRequest(forged, "workflows", cfg) is http:Unauthorized);

    // On Authorization the scheme is required: a bare token is not accepted.
    http:Request bare = requestWith({"Authorization": signedJwt("alice", ["approver"])});
    test:assertTrue(gateRequest(bare, "workflows", cfg) is http:Unauthorized);

    // Basic credentials are no JWT, and basic auth is off.
    http:Request basic = requestWith({"Authorization": opsBasic});
    test:assertTrue(gateRequest(basic, "workflows", cfg) is http:Unauthorized);
}

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthIsAnAlternativeToJwtOnAuthorization() {
    GatewayConfig cfg = gatewayConfig([WITH_JWT, WITH_BASIC], jwtHeader = AUTHORIZATION_HEADER);
    http:Request basic = requestWith({"Authorization": opsBasic});
    test:assertEquals(gateRequest(basic, "workflows", cfg),
            <CallerIdentity>{userId: "ops", roles: [], identitySource: "verified"});
    http:Request badBasic = requestWith({"Authorization": opsBadBasic});
    test:assertTrue(gateRequest(badBasic, "workflows", cfg) is http:Unauthorized);
}

@test:Config {groups: ["unit", "auth"]}
function testOpaqueTokenFallsBackToIntrospectionWhenJwtAndOAuth2ShareAuthorization() {
    GatewayConfig cfg = gatewayConfig([WITH_JWT, WITH_OAUTH2], jwtHeader = AUTHORIZATION_HEADER);
    // Not a JWT, but the introspection endpoint vouches for it; no claims, so no identity.
    http:Request opaque = requestWith({"Authorization": BEARER_PREFIX + opaqueAccessToken, "x-user-id": "spoofed"});
    test:assertEquals(gateRequest(opaque, "workflows", cfg), <CallerIdentity>{});

    http:Request revoked = requestWith({"Authorization": BEARER_PREFIX + revokedAccessToken});
    test:assertTrue(gateRequest(revoked, "workflows", cfg) is http:Unauthorized);
}

// ── OAuth2 ───────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testOAuth2TokenIsIntrospected() {
    GatewayConfig cfg = gatewayConfig([WITH_OAUTH2]);
    // An active JWT access token: its claims are the identity.
    http:Request active = requestWith({"Authorization": BEARER_PREFIX + signedJwt("bob", ["viewer"])});
    test:assertEquals(gateRequest(active, "workflows", cfg),
            <CallerIdentity>{userId: "bob", roles: ["viewer"], identitySource: "verified"});

    http:Request revoked = requestWith({"Authorization": BEARER_PREFIX + revokedAccessToken});
    test:assertTrue(gateRequest(revoked, "workflows", cfg) is http:Unauthorized);

    foreach string value in malformedBearerValues {
        test:assertTrue(gateRequest(requestWith({"Authorization": value}), "workflows", cfg) is http:Unauthorized);
    }
}

// ── JWT in a custom header: valid tokens ─────────────────────────────────────────

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

// ── Invalid credentials are refused whatever else the request carries ────────────

@test:Config {groups: ["unit", "auth"]}
function testForgedCustomHeaderJwtIsRefusedDespiteValidBasicAuth() {
    // Basic auth is on (the default), the caller presents valid Basic credentials plus
    // a JWT signed with another key claiming to be admin.
    http:Request req = requestWith({
        "Authorization": opsBasic,
        [JWT_HEADER]: signedJwt("admin", ["admin"], forgedJwtSecret)
    });
    CallerIdentity|http:Unauthorized|http:Forbidden gate = gateRequest(req, "workflows",
            gatewayConfig([WITH_JWT, WITH_BASIC]));
    test:assertTrue(gate is http:Unauthorized, "a forged custom-header JWT must be refused, not fall through");
}

@test:Config {groups: ["unit", "auth"]}
function testForgedCustomHeaderJwtIsRefusedDespiteValidApiKey() {
    http:Request req = requestWith({
        "x-api-key": testApiKey,
        [JWT_HEADER]: signedJwt("admin", ["admin"], forgedJwtSecret)
    });
    CallerIdentity|http:Unauthorized|http:Forbidden gate = gateRequest(req, "workflows",
            gatewayConfig([WITH_JWT, WITH_API_KEY]));
    test:assertTrue(gate is http:Unauthorized, "a forged custom-header JWT must be refused, not fall through");
}

@test:Config {groups: ["unit", "auth"]}
function testUnsignedOrMalformedCustomHeaderJwtIsRefused() {
    // Decodable but unsigned (what `jwt:decode` alone would have accepted).
    http:Request unsigned = requestWith({[JWT_HEADER]: jwtOf({"sub": "admin", "roles": ["admin"]})});
    test:assertTrue(gateRequest(unsigned, "workflows", gatewayConfig()) is http:Unauthorized);

    // Not a token at all.
    http:Request garbage = requestWith({[JWT_HEADER]: opsBasic});
    test:assertTrue(gateRequest(garbage, "workflows", gatewayConfig([WITH_JWT, WITH_OAUTH2])) is http:Unauthorized);

    // Wrong audience, signed with the right key.
    string wrongAudience = checkpanic jwt:issue({
        issuer: "test-issuer",
        audience: "someone-else",
        username: "alice",
        signatureConfig: {algorithm: jwt:HS256, config: testJwtSecret}
    });
    http:Request other = requestWith({[JWT_HEADER]: wrongAudience});
    test:assertTrue(gateRequest(other, "workflows", gatewayConfig()) is http:Unauthorized);
}

// ── Basic auth alongside a custom-header JWT ─────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthIsValidatedAlongsideACustomHeaderJwt() {
    GatewayConfig withBasic = gatewayConfig([WITH_JWT, WITH_BASIC]);

    // The motivating topology with the default enableBasicAuth = true: a JWT-only request passes.
    http:Request jwtOnly = requestWith({[JWT_HEADER]: signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(jwtOnly, "workflows", withBasic),
            <CallerIdentity>{userId: "alice", roles: ["approver"], identitySource: "verified"});

    // Basic alone passes too, validated against [[ballerina.auth.users]].
    http:Request basicOnly = requestWith({"Authorization": opsBasic});
    test:assertEquals(gateRequest(basicOnly, "workflows", withBasic),
            <CallerIdentity>{userId: "ops", roles: [], identitySource: "verified"});

    // Wrong password: refused, even with a valid JWT beside it.
    http:Request badBasic = requestWith({"Authorization": opsBadBasic});
    test:assertTrue(gateRequest(badBasic, "workflows", withBasic) is http:Unauthorized);
    http:Request badBasicWithJwt = requestWith({
        "Authorization": opsBadBasic,
        [JWT_HEADER]: signedJwt("alice", ["approver"])
    });
    test:assertTrue(gateRequest(badBasicWithJwt, "workflows", withBasic) is http:Unauthorized);

    // Both valid: the JWT's claims are the identity.
    http:Request both = requestWith({"Authorization": opsBasic, [JWT_HEADER]: signedJwt("alice", ["approver"])});
    test:assertEquals(gateRequest(both, "workflows", withBasic),
            <CallerIdentity>{userId: "alice", roles: ["approver"], identitySource: "verified"});
}

// ── API key ──────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testApiKeyAdmitsOnlyTheConfiguredKey() {
    http:Request keyed = requestWith({"x-api-key": testApiKey, "x-user-id": "gateway-user"});
    test:assertEquals(gateRequest(keyed, "workflows", gatewayConfig([WITH_JWT, WITH_API_KEY])),
            <CallerIdentity>{userId: "gateway-user", roles: []});
    http:Request badKey = requestWith({"x-api-key": wrongApiKey});
    test:assertTrue(gateRequest(badKey, "workflows", gatewayConfig([WITH_JWT, WITH_API_KEY])) is http:Unauthorized);
    // A wrong key is refused even when another scheme could have admitted the request.
    http:Request badKeyWithBasic = requestWith({"x-api-key": wrongApiKey, "Authorization": opsBasic});
    test:assertTrue(gateRequest(badKeyWithBasic, "workflows",
            gatewayConfig([WITH_BASIC, WITH_API_KEY])) is http:Unauthorized);
}

@test:Config {groups: ["unit", "auth"]}
function testUnvalidatedAuthorizationBearerIsNotReadForIdentity() {
    // Nothing validates an Authorization bearer token with a custom JWT header and OAuth2
    // off, so its claims are not read even though the API key admits the request.
    string forged = signedJwt("admin", ["admin"], forgedJwtSecret);
    http:Request keyed = requestWith({"x-api-key": testApiKey, "Authorization": "Bearer " + forged});
    test:assertEquals(gateRequest(keyed, "workflows", gatewayConfig([WITH_JWT, WITH_API_KEY])),
            <CallerIdentity>{userId: (), roles: []});
}

// ── Basic auth with no users configured ──────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testRefusalExplainsAnEmptyBasicAuthUserStore() {
    // tests/Config.toml configures a user, so the probe sees a populated store.
    test:assertFalse(isFileUserStoreEmpty());

    GatewayConfig empty = gatewayConfig([WITH_BASIC]);
    empty.basicAuthUsersMissing = true;
    CallerIdentity|http:Unauthorized|http:Forbidden refused = gateRequest(requestWith({}), "workflows", empty);
    if refused !is http:Unauthorized {
        test:assertFail("a request without credentials must be refused");
    }
    string message = (refused?.body).toString();
    test:assertTrue(message.includes("[[ballerina.auth.users]]") && message.includes("enableBasicAuth = false"),
            "the refusal must say how to configure users or turn basic auth off: " + message);

    CallerIdentity|http:Unauthorized|http:Forbidden configured = gateRequest(requestWith({}), "workflows",
            gatewayConfig([WITH_BASIC]));
    if configured !is http:Unauthorized {
        test:assertFail("a request without credentials must be refused");
    }
    test:assertFalse((configured?.body).toString().includes("[[ballerina.auth.users]]"),
            "with users configured, the refusal does not describe the server's configuration");
}

@test:Config {groups: ["unit", "auth"]}
function testEmptyUserStoreIsReportedOnlyWhenBasicAuthIsTheOnlyScheme() {
    test:assertTrue(isBasicAuthTheOnlyScheme(true, false, false, false));
    // Another scheme can still admit requests, so the empty store is not a misconfiguration.
    test:assertFalse(isBasicAuthTheOnlyScheme(true, true, false, false));
    test:assertFalse(isBasicAuthTheOnlyScheme(true, false, true, false));
    test:assertFalse(isBasicAuthTheOnlyScheme(true, false, false, true));
    test:assertFalse(isBasicAuthTheOnlyScheme(false, false, false, false));
}
