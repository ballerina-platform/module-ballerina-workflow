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

// The JWT configurables: `jwksUrl` is required, `jwtIssuer` and `jwtAudience` are
// optional and an unset one leaves that claim unchecked.

const string JWKS_URL = "https://idp.example.com/jwks";

// The production validator configuration with the JWKS lookup swapped for the test
// secret, so a real handler can check the claims of an HS256 test token.
isolated function claimHandler(string? issuer, string? audience) returns http:ListenerJwtAuthHandler {
    http:JwtValidatorConfig config = jwtValidatorConfigOf(JWKS_URL, issuer, audience);
    config.signatureConfig = {secret: TEST_SECRET};
    return new (config);
}

isolated function otherPartyJwt() returns string => BEARER_PREFIX + checkpanic jwt:issue({
    issuer: "other-issuer",
    audience: "other-audience",
    username: "alice",
    signatureConfig: {algorithm: jwt:HS256, config: TEST_SECRET}
});

@test:Config {}
function testJwtConfigRequiresOnlyTheJwksUrl() {
    test:assertEquals(jwtConfigError(JWKS_URL, AUTHORIZATION_HEADER), ());

    error? missingUrl = jwtConfigError("", AUTHORIZATION_HEADER);
    if missingUrl !is error {
        test:assertFail("an empty jwksUrl must be refused");
    }
    test:assertTrue(missingUrl.message().includes("'jwksUrl'"));

    error? blankHeader = jwtConfigError(JWKS_URL, "  ");
    if blankHeader !is error {
        test:assertFail("a blank jwtAuthHeader must be refused");
    }
    test:assertTrue(blankHeader.message().includes("'jwtAuthHeader'"));
}

@test:Config {}
function testJwtValidatorConfigLeavesUnsetClaimsOut() {
    http:JwtValidatorConfig unset = jwtValidatorConfigOf(JWKS_URL, (), ());
    test:assertFalse(unset.hasKey("issuer"));
    test:assertFalse(unset.hasKey("audience"));
    test:assertEquals(unset.signatureConfig?.jwksConfig?.url, JWKS_URL);

    // A blank value, e.g. from a template that rendered nothing, counts as unset.
    http:JwtValidatorConfig blank = jwtValidatorConfigOf(JWKS_URL, "", "  ");
    test:assertFalse(blank.hasKey("issuer"));
    test:assertFalse(blank.hasKey("audience"));

    http:JwtValidatorConfig set = jwtValidatorConfigOf(JWKS_URL, "test-issuer", "workflow");
    test:assertEquals(set.issuer, "test-issuer");
    test:assertEquals(set.audience, "workflow");
}

@test:Config {}
function testUnsetClaimsAreNotChecked() {
    jwt:Payload|http:Unauthorized result = claimHandler((), ()).authenticate(otherPartyJwt());
    test:assertTrue(result is jwt:Payload, "an unset issuer and audience must accept any iss and aud");
    test:assertTrue(claimHandler("", "").authenticate(otherPartyJwt()) is jwt:Payload,
        "a blank issuer and audience must accept any iss and aud");
}

@test:Config {}
function testSetClaimsAreChecked() {
    http:ListenerJwtAuthHandler handler = claimHandler("test-issuer", "workflow");
    test:assertTrue(handler.authenticate(BEARER_PREFIX + signedJwt("alice", [])) is jwt:Payload);
    test:assertTrue(handler.authenticate(otherPartyJwt()) is http:Unauthorized);

    test:assertTrue(claimHandler("test-issuer", ()).authenticate(otherPartyJwt()) is http:Unauthorized,
        "a set issuer must be checked on its own");
    test:assertTrue(claimHandler((), "workflow").authenticate(otherPartyJwt()) is http:Unauthorized,
        "a set audience must be checked on its own");
}
