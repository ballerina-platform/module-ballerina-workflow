// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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
import ballerina/lang.runtime as langRuntime;
import ballerina/test;

// Negative cases for every authentication scheme: each credential that must NOT admit a
// request is listed with the reason, so a regression names the case that slipped through.

const int BROKEN_INTROSPECTION_PORT = 9799;
const int UNREACHABLE_INTROSPECTION_PORT = 9800;

service /oauth2 on new http:Listener(BROKEN_INTROSPECTION_PORT) {
    resource function post introspect() returns http:InternalServerError => {body: "introspection failed"};
}

isolated function jwtWith(*jwt:IssuerConfig config) returns string => checkpanic jwt:issue(config);

final readonly & map<json> adminClaims = {"iss": "test-issuer", "aud": "workflow", "sub": "admin", "roles": ["admin"]};

// A validly signed token whose payload is swapped for one claiming another identity.
isolated function tamperedJwt() returns string {
    string[] parts = re `\.`.split(signedJwt("alice", ["viewer"]));
    return string `${parts[0]}.${base64UrlOf(adminClaims.toJsonString())}.${parts[2]}`;
}

isolated function unsignedJwt() returns string {
    string header = base64UrlOf("{\"alg\":\"none\",\"typ\":\"JWT\"}");
    return string `${header}.${base64UrlOf(adminClaims.toJsonString())}.`;
}

// Tokens the JWT handler must refuse, as the raw token (the scheme prefix is added per header).
isolated function invalidJwts() returns map<string> => {
    "signed with another key": signedJwt("admin", ["admin"], forgedJwtSecret),
    "tampered payload": tamperedJwt(),
    "unsigned (alg none)": unsignedJwt(),
    "bogus signature": jwtOf(adminClaims),
    "expired": jwtWith(issuer = "test-issuer", audience = "workflow", username = "alice", expTime = -60,
            signatureConfig = {algorithm: jwt:HS256, config: testJwtSecret}),
    "wrong issuer": jwtWith(issuer = "other-issuer", audience = "workflow", username = "alice",
            signatureConfig = {algorithm: jwt:HS256, config: testJwtSecret}),
    "wrong audience": jwtWith(issuer = "test-issuer", audience = "other-audience", username = "alice",
            signatureConfig = {algorithm: jwt:HS256, config: testJwtSecret}),
    "not a JWT": "not.a.jwt",
    "random text": "garbage"
};

// ── JWT ──────────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testInvalidJwtIsRefusedOnEveryHeader() {
    foreach string header in [AUTHORIZATION_HEADER, JWT_HEADER] {
        GatewayConfig cfg = gatewayConfig(jwtHeader = header);
        foreach [string, string] [name, token] in invalidJwts().entries() {
            http:Request req = requestWith({[header]: BEARER_PREFIX + token, "x-user-id": "admin"});
            test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized,
                    string `JWT on '${header}', ${name}: must be refused`);
        }
        foreach [string, string] [name, value] in malformedBearerValues.entries() {
            http:Request req = requestWith({[header]: value});
            test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized,
                    string `JWT on '${header}', ${name}: must be refused`);
        }
    }
}

@test:Config {groups: ["unit", "auth"]}
function testInvalidJwtIsRefusedEvenWhenAnotherSchemeAdmits() {
    GatewayConfig cfg = gatewayConfig([WITH_JWT, WITH_BASIC, WITH_API_KEY]);
    foreach [string, string] [name, token] in invalidJwts().entries() {
        http:Request req = requestWith({[JWT_HEADER]: token, "Authorization": opsBasic, "x-api-key": testApiKey});
        test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized,
                name + ": an invalid JWT must be refused beside valid basic and API key credentials");
    }
}

// ── OAuth2 ───────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testOAuth2RefusesInactiveMalformedAndUnverifiableTokens() {
    GatewayConfig cfg = gatewayConfig([WITH_OAUTH2]);
    http:Request inactive = requestWith({"Authorization": BEARER_PREFIX + revokedAccessToken});
    test:assertTrue(gateRequest(inactive, "workflows", cfg) is http:Unauthorized, "an inactive token must be refused");
    foreach [string, string] [name, value] in malformedBearerValues.entries() {
        http:Request req = requestWith({"Authorization": value});
        test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized, name + ": must be refused");
    }

    // An introspection endpoint that fails or cannot be reached never admits a request.
    map<int> endpoints = {"failing endpoint": BROKEN_INTROSPECTION_PORT, "unreachable endpoint": UNREACHABLE_INTROSPECTION_PORT};
    foreach [string, int] [name, endpointPort] in endpoints.entries() {
        GatewayConfig unverifiable = gatewayConfig([WITH_OAUTH2]);
        unverifiable.oauth2Handler = new ({url: string `http://localhost:${endpointPort}/oauth2/introspect`});
        http:Request req = requestWith({"Authorization": BEARER_PREFIX + opaqueAccessToken});
        test:assertTrue(gateRequest(req, "workflows", unverifiable) is http:Unauthorized,
                name + ": a token that cannot be introspected must be refused");
    }
}

@test:Config {groups: ["unit", "auth"]}
function testRevokedOAuth2TokenIsRefusedEvenWhenAnotherSchemeAdmits() {
    GatewayConfig cfg = gatewayConfig([WITH_JWT, WITH_OAUTH2, WITH_API_KEY], jwtHeader = JWT_HEADER);
    http:Request req = requestWith({
        "Authorization": BEARER_PREFIX + revokedAccessToken,
        [JWT_HEADER]: signedJwt("alice", ["approver"]),
        "x-api-key": testApiKey
    });
    // The JWT travels in its own header, so the token in Authorization is still introspected.
    test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized,
            "a revoked OAuth2 token must be refused beside a valid JWT and API key");
}

// ── Basic auth ───────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testBasicAuthRefusesBadCredentials() {
    GatewayConfig cfg = gatewayConfig([WITH_BASIC]);
    map<string> invalid = {
        "wrong password": opsBadBasic,
        "unknown user": basicCredential(unknownUsername, opsPassword),
        "no separator": "Basic " + opsUsername.toBytes().toBase64(),
        "empty password": basicCredential(opsUsername, "")
    };
    foreach [string, string] [name, value] in malformedBasicValues.entries() {
        invalid[name] = value;
    }
    foreach [string, string] [name, value] in invalid.entries() {
        http:Request req = requestWith({"Authorization": value, "x-user-id": "admin"});
        test:assertTrue(gateRequest(req, "workflows", cfg) is http:Unauthorized, name + ": must be refused");
    }
}

// ── API key ──────────────────────────────────────────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testApiKeyRefusesAnythingButTheExactKey() {
    GatewayConfig cfg = gatewayConfig([WITH_API_KEY]);
    map<map<string>> invalid = {
        "key in another header": {"x-api-token": testApiKey},
        "key as a bearer token": {"Authorization": BEARER_PREFIX + testApiKey}
    };
    foreach [string, string] [name, value] in invalidApiKeyValues.entries() {
        invalid[name] = {"x-api-key": value};
    }
    foreach [string, map<string>] [name, headers] in invalid.entries() {
        test:assertTrue(gateRequest(requestWith(headers), "workflows", cfg) is http:Unauthorized,
                name + ": must be refused");
    }
}

// ── A credential for a scheme that is not enabled ────────────────────────────────

@test:Config {groups: ["unit", "auth"]}
function testCredentialForADisabledSchemeIsNotAccepted() {
    string validJwt = signedJwt("alice", ["approver"]);
    map<[GatewayConfig, map<string>]> cases = {
        "basic credentials, JWT only": [gatewayConfig(jwtHeader = AUTHORIZATION_HEADER), {"Authorization": opsBasic}],
        "API key, JWT only": [gatewayConfig(), {"x-api-key": testApiKey}],
        "JWT, basic only": [gatewayConfig([WITH_BASIC]),
            {"Authorization": BEARER_PREFIX + validJwt, [JWT_HEADER]: validJwt}],
        "API key, basic only": [gatewayConfig([WITH_BASIC]), {"x-api-key": testApiKey}],
        "basic credentials, OAuth2 only": [gatewayConfig([WITH_OAUTH2]),
            {"Authorization": opsBasic}],
        "JWT in the custom header, OAuth2 only": [gatewayConfig([WITH_OAUTH2]),
            {[JWT_HEADER]: validJwt}],
        "Authorization bearer, JWT on a custom header": [gatewayConfig(), {"Authorization": BEARER_PREFIX + validJwt}],
        "forwarded identity only": [gatewayConfig([WITH_API_KEY]),
            {"x-user-id": "admin", "x-user-roles": "admin"}]
    };
    foreach [string, [GatewayConfig, map<string>]] [name, [cfg, headers]] in cases.entries() {
        test:assertTrue(gateRequest(requestWith(headers), "workflows", cfg) is http:Unauthorized,
                name + ": must be refused");
    }
}

// ── Every route of the running listener ──────────────────────────────────────────

// One request per resource of the management service; path parameters are placeholders.
final readonly & [string, string][] managementRoutes = [
    ["GET", "/runtime"],
    ["GET", "/definitions"],
    ["GET", "/metadata"],
    ["GET", "/workflows"],
    ["POST", "/workflows"],
    ["GET", "/workflows/wf-1"],
    ["POST", "/workflows/wf-1/suspend"],
    ["POST", "/workflows/wf-1/wake"],
    ["POST", "/workflows/wf-1/resume"],
    ["POST", "/workflows/wf-1/data/approval"],
    ["POST", "/workflows/wf-1/terminate"],
    ["POST", "/workflows/wf-1/cancel"],
    ["GET", "/workflows/wf-1/history"],
    ["GET", "/workflows/wf-1/activity-tree"],
    ["GET", "/workflows/wf-1/reset-points"],
    ["POST", "/workflows/wf-1/reset"],
    ["GET", "/workflows/wf-1/execution-graph"],
    ["GET", "/workflows/wf-1/run-1"],
    ["POST", "/workflows/wf-1/run-1/suspend"],
    ["POST", "/workflows/wf-1/run-1/resume"],
    ["POST", "/workflows/wf-1/run-1/terminate"],
    ["POST", "/workflows/wf-1/run-1/cancel"],
    ["GET", "/workflows/wf-1/run-1/history"],
    ["GET", "/workflows/wf-1/run-1/activity-tree"],
    ["GET", "/workflows/wf-1/run-1/reset-points"],
    ["POST", "/workflows/wf-1/run-1/reset"],
    ["GET", "/workflows/wf-1/run-1/execution-graph"],
    ["GET", "/human-tasks"],
    ["GET", "/human-tasks/pending-count"],
    ["POST", "/human-tasks/task-1/reassign"],
    ["POST", "/human-tasks/task-1/deadline"],
    ["GET", "/human-tasks/task-1"],
    ["POST", "/human-tasks/task-1/complete"],
    ["POST", "/human-tasks/task-1/fail"],
    ["GET", "/review-activities"],
    ["POST", "/review-activities/task-1/reassign"],
    ["POST", "/review-activities/task-1/deadline"],
    ["GET", "/review-activities/task-1"],
    ["POST", "/review-activities/task-1/proceed"],
    ["POST", "/review-activities/task-1/proceed-with-input"],
    ["POST", "/review-activities/task-1/reject"],
    ["POST", "/review-activities/bulk-retry"]
];

// The test configuration leaves basic auth at its default (on), so the listener must
// refuse every route to a caller without valid basic credentials.
@test:Config {}
function testEveryRouteRefusesUnauthenticatedCallers() returns error? {
    check startManagementListener();
    string url = string `http://localhost:${port}`;
    map<http:Client> callers = {
        "no credentials": check new (url, timeout = 5),
        "wrong password": check new (url, timeout = 5, auth = {username: opsUsername, password: wrongPassword}),
        "unknown user": check new (url, timeout = 5, auth = {username: unknownUsername, password: opsPassword}),
        "forged bearer token": check new (url, timeout = 5, auth = {token: signedJwt("admin", ["admin"], forgedJwtSecret)})
    };
    foreach [string, http:Client] [name, caller] in callers.entries() {
        foreach [string, string] [method, path] in managementRoutes {
            map<string> spoofed = {"x-user-id": "admin", "x-user-roles": "admin"};
            http:Response response;
            if method == "GET" {
                response = check caller->get("/workflow" + path, spoofed);
            } else {
                response = check caller->post("/workflow" + path, {}, spoofed);
            }
            test:assertEquals(response.statusCode, 401, string `${name}: ${method} ${path} must be refused`);
        }
    }
    check stopManagementService();
    langRuntime:sleep(0.1);
}

// A browser sends a CORS preflight without credentials, so it is answered from the CORS configuration,
// while any other OPTIONS request still needs a credential.
@test:Config {}
function testCorsPreflightIsAnsweredWithoutCredentials() returns error? {
    check startManagementListener();
    http:Client anonymous = check new (string `http://localhost:${port}`, timeout = 5);
    http:Response preflight = check anonymous->options("/workflow/definitions", {
        "Origin": "https://portal.example.com",
        [CORS_REQUEST_METHOD_HEADER]: "GET",
        "Access-Control-Request-Headers": "authorization"
    });
    test:assertEquals(preflight.statusCode, 204, "a CORS preflight must not need credentials");
    test:assertTrue(preflight.hasHeader("Access-Control-Allow-Origin"),
        "the preflight must carry the CORS headers");

    http:Response plainOptions = check anonymous->options("/workflow/definitions");
    test:assertEquals(plainOptions.statusCode, 401, "an OPTIONS request that is not a preflight needs credentials");

    http:Response getWithPreflightHeader = check anonymous->get("/workflow/definitions",
        {[CORS_REQUEST_METHOD_HEADER]: "GET"});
    test:assertEquals(getWithPreflightHeader.statusCode, 401, "the preflight header does not exempt other methods");

    check stopManagementService();
    langRuntime:sleep(0.1);
}
