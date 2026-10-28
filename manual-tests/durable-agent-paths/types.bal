// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/ai;

type Risk "LOW"|"MEDIUM"|"HIGH";

type Verdict record {|
    boolean approved;
    Risk risk;
    string reason;
|};

type Contact record {|
    string name;
    string? email;
    string phone?;
    string country = "LK";
|};

type LineItem record {|
    string sku;
    int quantity;
    decimal unitPrice;
|};

type Invoice record {|
    string invoiceId;
    Contact customer;
    LineItem[] items;
    decimal total;
|};

type City record {|
    string name;
    int population;
|};

@ai:JsonSchema {
    'type: "object",
    properties: {summary: {'type: "string"}, score: {'type: "integer"}},
    required: ["summary", "score"]
}
type AnnotatedSummary record {|
    string summary;
    int score;
|};

type Order record {|
    string orderId;
    string item;
    int quantity;
    decimal amount;
|};

type OrderDecision record {|
    string orderId;
    boolean accepted;
    string note;
|};

type ManagerApproval record {|
    boolean approved;
    string comment;
|};

type AuditEntry record {|
    string message;
|};

type ParallelOutcome record {|
    Verdict verdict;
    OrderDecision decision;
|};
