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

// Test credentials and invalid credential values, read from tests/Config.toml so none is written into the sources.

configurable string testJwtSecret = ?;
configurable string forgedJwtSecret = ?;
configurable string testApiKey = ?;
configurable string wrongApiKey = ?;
configurable string opaqueAccessToken = ?;
configurable string revokedAccessToken = ?;
// The [[ballerina.auth.users]] entry in tests/Config.toml.
configurable string opsUsername = ?;
configurable string opsPassword = ?;
configurable string wrongPassword = ?;
configurable string unknownUsername = ?;
// Authorization header values that carry no usable bearer token.
configurable map<string> & readonly malformedBearerValues = ?;
// Authorization header values that carry no usable basic credential.
configurable map<string> & readonly malformedBasicValues = ?;
// API key header values that are not the configured key.
configurable map<string> & readonly invalidApiKeyValues = ?;

final http:CredentialsConfig & readonly opsCredentials = {username: opsUsername, password: opsPassword};
final string opsBasic = basicCredential(opsUsername, opsPassword);
final string opsBadBasic = basicCredential(opsUsername, wrongPassword);

isolated function basicCredential(string username, string password) returns string =>
    "Basic " + string `${username}:${password}`.toBytes().toBase64();

// The authentication schemes a test gateway configuration enables.
enum Scheme {
    WITH_JWT,
    WITH_BASIC,
    WITH_OAUTH2,
    WITH_API_KEY
}
