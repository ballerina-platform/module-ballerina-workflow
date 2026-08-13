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

import ballerina/workflow.management;

// ================================================================================
// HTTP-ONLY TYPES
// ================================================================================
// These records exist purely for the REST API's response and pagination envelopes.
// The domain records they wrap live in `ballerina/workflow.management`, whose
// native code creates them by name — they must stay there.

# Audit record returned by human task completion operations.
#
# + success - Always true on the success path
# + completedBy - User ID extracted from the `x-user-id` request header
# + completedAt - ISO-8601 timestamp of when the completion was processed
public type CompletionInfo record {|
    boolean success;
    string completedBy;
    string completedAt;
|};

# Audit record returned by review activity decision operations.
#
# + success - Always true on the success path
# + decision - The decision taken: `"proceed"`, `"proceed-with-input"`, or `"reject"`
# + decidedBy - User ID extracted from the `x-user-id` request header
# + decidedAt - ISO-8601 timestamp of when the decision was processed
public type ReviewDecisionInfo record {|
    boolean success;
    string decision;
    string decidedBy;
    string decidedAt;
|};

# Paginated list of human task summaries.
#
# + items - Human task summaries for this page
# + nextPageToken - Opaque continuation token, or `()` on the last page
# + hasMore - True when more pages follow
public type HumanTaskPage record {|
    management:HumanTaskSummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};

# Paginated list of review activity summaries.
#
# + items - Review activity summaries for this page
# + nextPageToken - Opaque continuation token, or `()` on the last page
# + hasMore - True when more pages follow
public type ReviewActivityPage record {|
    management:ReviewActivitySummary[] items;
    string? nextPageToken;
    boolean hasMore;
|};
