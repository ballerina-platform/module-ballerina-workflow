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

import ballerina/test;

final DecisionAudit retryEntry = {
    kind: REVIEW_ACTIVITY,
    taskId: "review-retry",
    taskName: (),
    parentWorkflowId: (),
    action: "proceed",
    outcome: DECISION_ACCEPTED,
    userId: "user-1",
    userRoles: ["citizen"],
    identitySource: "asserted",
    assignedRoles: [],
    decidedAt: "2026-11-19T00:00:00Z",
    taskInput: (),
    content: ()
};

// The writes to refuse and the entries each call received.
isolated record {|int panics; DecisionAudit[] entries;|} controlledWrites = {panics: 0, entries: []};

// Records each call's entry and panics on the first `panics` calls, as a refused log line does.
isolated function controlledWrite(DecisionAudit entry, error? err) {
    boolean refuse;
    lock {
        controlledWrites.entries.push(entry);
        refuse = controlledWrites.entries.length() <= controlledWrites.panics;
    }
    if refuse {
        panic error("modification not allowed on readonly value");
    }
}

isolated function resetControlledWrite(int panics) {
    lock {
        controlledWrites = {panics, entries: []};
    }
}

isolated function writtenEntriesSnapshot() returns DecisionAudit[] {
    lock {
        return controlledWrites.entries.clone();
    }
}

@test:Config {
    groups: ["observe"]
}
function testWriteOrRetryWritesOnceOnSuccess() {
    resetControlledWrite(0);
    writeOrRetry(controlledWrite, retryEntry, ());
    test:assertEquals(writtenEntriesSnapshot(), [retryEntry]);
}

@test:Config {
    groups: ["observe"]
}
function testWriteOrRetryWritesAgainAfterAPanic() {
    resetControlledWrite(1);
    writeOrRetry(controlledWrite, retryEntry, ());
    test:assertEquals(writtenEntriesSnapshot(), [retryEntry, retryEntry]);
}

@test:Config {
    groups: ["observe"]
}
function testWriteOrRetryContainsASecondPanic() {
    resetControlledWrite(2);
    writeOrRetry(controlledWrite, retryEntry, error("denied"));
    test:assertEquals(writtenEntriesSnapshot(), [retryEntry, retryEntry]);
}
