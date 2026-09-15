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

// ================================================================================
// COMMON UTILITIES FOR WORKFLOW INTEGRATION TESTS
// ================================================================================

import ballerina/time;
import ballerina/lang.regexp;

# Generates a unique workflow ID with a given prefix.
# Uses nanosecond timestamp to ensure uniqueness.
#
# + prefix - The prefix for the workflow ID
# + return - A unique workflow ID string
public isolated function uniqueId(string prefix) returns string {
    time:Utc now = time:utcNow();
    int nanoTime = now[0] * 1000000000 + <int>now[1];
    return prefix + "-" + nanoTime.toString();
}

// UUID v7 regex pattern:
// 8 hex - 4 hex - 7[0-9a-f]{3} - [89ab][0-9a-f]{3} - 12 hex
final regexp:RegExp uuidV7Pattern = re `^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`;

# Validates that the given string is a valid UUID v7.
#
# A UUID v7 has the standard 8-4-4-4-12 hex format with:
# - Version nibble `7` at position 14
# - Variant bits `8`, `9`, `a`, or `b` at position 19
#
# + id - The string to validate
# + return - true if the string is a valid UUID v7
public isolated function isValidUuidV7(string id) returns boolean {
    return uuidV7Pattern.isFullMatch(id.toLowerAscii());
}
