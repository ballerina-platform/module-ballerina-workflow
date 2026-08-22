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

// ================================================================================
// Durable agent run input (`inputType`) — the contract shared by `DurableAgent.run`
// and the management-API start, verified against the real Temporal server. The
// model echoes the user turn back, so each test reads exactly what the framework
// put in front of the model rather than trusting that the payload travelled.
// ================================================================================

import ballerina/test;
import ballerina/workflow;
import ballerina/workflow.management;

// The runner appends the structured payload to the query under this marker.
const string INPUT_MARKER = "Input:";

// The compiler plugin resolves `inputType` from the module-level declaration, so a
// mismatched payload written against the agent variable is rejected at compile time —
// which is the point of WORKFLOW_154, and which no test can execute. Reaching the agent
// through a parameter is how these tests exercise the runtime check that stands behind
// it, the one that guards the values the compiler never sees.
function runAgentDynamically(workflow:DurableAgent agent, string query, json input)
        returns string|error {
    return agent.run(query, input);
}

// Splits an echoed user turn into the query text and the payload the runner appended.
function splitEchoedTurn(string turn) returns [string, json]|error {
    int? marker = turn.indexOf(INPUT_MARKER);
    if marker is () {
        return [turn.trim(), ()];
    }
    string query = turn.substring(0, marker).trim();
    string payloadText = turn.substring(marker + INPUT_MARKER.length()).trim();
    return [query, check payloadText.fromJsonString()];
}

@test:Config {
    groups: ["integration"]
}
function testAgentTypedInputReachesTheModelTurn() returns error? {
    // A payload matching the declared inputType travels to the run, and the field the
    // payload omits arrives carrying the record's declared default.
    string agentId = check orderInputAgent.run("Place this order", {id: "ORD-1", qty: 2});

    string turn = check orderInputAgent.waitForResult(agentId);
    [string, json] [query, payload] = check splitEchoedTurn(turn);
    test:assertEquals(query, "Place this order",
            "The query must reach the model as the user turn");

    map<json> orderFields = check payload.ensureType();
    test:assertEquals(orderFields["id"], "ORD-1", "The payload's id must reach the model turn");
    test:assertEquals(orderFields["qty"], 2, "The payload's qty must reach the model turn");
    test:assertEquals(orderFields["note"], "standard",
            "An omitted field must arrive with the inputType's declared default");
}

@test:Config {
    groups: ["integration"]
}
function testAgentTypedInputRejectsAMismatchedPayload() returns error? {
    // The compiler plugin rejects what it can decide statically; a value computed at
    // run time is checked against the declared inputType before the instance starts.
    json notAnOrder = {reference: "ORD-2"};
    string|error mismatch = runAgentDynamically(orderInputAgent, "Place this order", notAnOrder);

    test:assertTrue(mismatch is error, "A payload that violates inputType must be rejected");
    if mismatch is error {
        test:assertTrue(mismatch.message().includes("declared inputType"),
                "The rejection must name the declared inputType: " + mismatch.message());
    }
}

@test:Config {
    groups: ["integration"]
}
function testAgentQueryOnlyRunTakesNoPayload() returns error? {
    // inputType: () — the query alone starts the run...
    string agentId = check queryOnlyAgent.run("Just the query");
    string turn = check queryOnlyAgent.waitForResult(agentId);
    test:assertEquals(turn.trim(), "Just the query",
            "A query-only agent's user turn is the query, with nothing appended");

    // ...and a payload has nowhere to go, so it is refused with an explanation.
    string|error unexpected = runAgentDynamically(queryOnlyAgent, "Just the query", {id: "ORD-3"});
    test:assertTrue(unexpected is error, "A query-only agent must reject a payload");
    if unexpected is error {
        test:assertTrue(unexpected.message().includes("takes no input payload"),
                "The rejection must explain that no payload is accepted: " + unexpected.message());
    }
}

@test:Config {
    groups: ["integration"]
}
function testAgentOpenJsonInputAcceptsAnyShape() returns error? {
    // The `json` default is deliberately unvalidated: an object, a list, and a scalar
    // all reach the model turn unchanged.
    string objectId = check openInputAgent.run("Anything", {note: "urgent", tags: ["a", "b"]});
    [string, json] [_, objectPayload] = check splitEchoedTurn(
            check openInputAgent.waitForResult(objectId));
    map<json> objectFields = check objectPayload.ensureType();
    test:assertEquals(objectFields["note"], "urgent", "An object payload must arrive intact");

    string listId = check openInputAgent.run("Anything", [1, 2, 3]);
    [string, json] [_, listPayload] = check splitEchoedTurn(
            check openInputAgent.waitForResult(listId));
    test:assertEquals(listPayload, <json>[1, 2, 3], "A list payload must arrive intact");

    string scalarId = check openInputAgent.run("Anything", "a bare string payload");
    [string, json] [_, scalarPayload] = check splitEchoedTurn(
            check openInputAgent.waitForResult(scalarId));
    test:assertEquals(scalarPayload, "a bare string payload",
            "A scalar payload must arrive intact");
}

@test:Config {
    groups: ["integration"]
}
function testAgentTypedInputWithDeclaredResultType() returns error? {
    // Input validation and result typing are independent: the payload is checked on the
    // way in, and the loop's outcome is converted to the declared result type on the way out.
    string agentId = check orderSummaryAgent.run("Summarize this order", {id: "ORD-4", qty: 9});

    AgentOrderSummary summary = check orderSummaryAgent.waitForResult(agentId);
    test:assertEquals(summary.summary, "generated summary",
            "The declared result type is produced by the loop-exit generate call");
    test:assertEquals(summary.score, 7, "The typed result's fields must convert");
}

@test:Config {
    groups: ["integration"]
}
function testAgentManagementStartUsesTheQueryInputEnvelope() returns error? {
    // Every agent is started through the same `{query, input}` envelope, whatever it declares.
    management:WorkflowHandle typedStart = check management:startWorkflowByType(
            "orderInputAgent", {query: "Place this order", input: {id: "ORD-5", qty: 3}});
    [string, json] [typedQuery, typedPayload] = check splitEchoedTurn(
            check orderInputAgent.waitForResult(typedStart.workflowId));
    test:assertEquals(typedQuery, "Place this order",
            "The envelope's query becomes the agent's user turn");
    map<json> orderFields = check typedPayload.ensureType();
    test:assertEquals(orderFields["id"], "ORD-5", "The envelope's input becomes the run payload");

    management:WorkflowHandle queryStart = check management:startWorkflowByType(
            "queryOnlyAgent", {query: "Just the query"});
    string queryTurn = check queryOnlyAgent.waitForResult(queryStart.workflowId);
    test:assertEquals(queryTurn.trim(), "Just the query",
            "A query-only agent starts from the envelope's query alone");
}

@test:Config {
    groups: ["integration"]
}
function testAgentManagementStartRejectsBadEnvelopes() returns error? {
    // A payload that violates the declared inputType is refused before the instance starts.
    management:WorkflowHandle|error mismatch = management:startWorkflowByType(
            "orderInputAgent", {query: "Place this order", input: {reference: "ORD-6"}});
    test:assertTrue(mismatch is error, "A mismatched payload must be rejected at start");
    if mismatch is error {
        test:assertTrue(mismatch.message().includes("input"),
                "The rejection must name the offending envelope field: " + mismatch.message());
    }

    // The envelope is the only accepted shape: a bare value carries no query.
    management:WorkflowHandle|error bare = management:startWorkflowByType(
            "orderInputAgent", "Place this order");
    test:assertTrue(bare is error, "A non-envelope start input must be rejected");
    if bare is error {
        test:assertTrue(bare.message().includes("query"),
                "The rejection must point at the envelope's query field: " + bare.message());
    }

    // A query-only agent has nowhere to put a payload.
    management:WorkflowHandle|error unexpected = management:startWorkflowByType(
            "queryOnlyAgent", {query: "Just the query", input: {id: "ORD-7"}});
    test:assertTrue(unexpected is error, "A query-only agent must reject a posted payload");
}

@test:Config {
    groups: ["integration"]
}
function testAgentStartSchemaDescribesTheEnvelope() returns error? {
    // The definition list is what a caller builds a start form from, so the schema must
    // describe the envelope: a required query for every agent, and an input carrying the
    // declared inputType's own schema — required when it cannot be nil, absent entirely
    // for a query-only agent.
    management:WorkflowDefinition[] defs = check management:listWorkflowDefinitions();
    map<string> schemas = {};
    foreach management:WorkflowDefinition def in defs {
        if def.kind == "AGENT" {
            schemas[def.workflowType] = def.inputSchema ?: "";
        }
    }

    json typedSchema = check (schemas.get("orderInputAgent")).fromJsonString();
    map<json> typed = check typedSchema.ensureType();
    map<json> typedProps = check typed["properties"].ensureType();
    test:assertTrue(typedProps.hasKey("query") && typedProps.hasKey("input"),
            "A typed agent's schema must describe both envelope fields: " + typedSchema.toString());
    // Only the query is required: omitting the payload runs the agent on the query alone,
    // exactly as run(query) does, so the schema must not advertise a stricter contract.
    test:assertEquals(typed["required"], <json>["query"],
            "The payload stays optional even for a typed inputType: " + typedSchema.toString());
    map<json> inputSchema = check typedProps["input"].ensureType();
    map<json> orderProps = check inputSchema["properties"].ensureType();
    test:assertTrue(orderProps.hasKey("id") && orderProps.hasKey("qty"),
            "The input schema must be the declared record's own schema: " + typedSchema.toString());
    // The payload schema marks a field required exactly when the start needs it: `note` carries a
    // default, and testAgentTypedInputReachesTheModelTurn starts without it, so demanding it here
    // would send a launcher UI after a field the API does not want.
    test:assertEquals(inputSchema["required"], <json>["id", "qty"],
            "Only the fields without a default are required: " + typedSchema.toString());

    json queryOnlySchema = check (schemas.get("queryOnlyAgent")).fromJsonString();
    map<json> queryOnly = check queryOnlySchema.ensureType();
    map<json> queryOnlyProps = check queryOnly["properties"].ensureType();
    test:assertTrue(queryOnlyProps.hasKey("query"),
            "Every agent's schema declares a query: " + queryOnlySchema.toString());
    test:assertFalse(queryOnlyProps.hasKey("input"),
            "A query-only agent's schema must not offer a payload: " + queryOnlySchema.toString());

    json openSchema = check (schemas.get("openInputAgent")).fromJsonString();
    map<json> open = check openSchema.ensureType();
    test:assertEquals(open["required"], <json>["query"],
            "The json default's payload is optional too: " + openSchema.toString());
    // The open json default accepts an object, a list, or a bare scalar — as
    // testAgentOpenJsonInputAcceptsAnyShape shows — so its payload schema constrains nothing.
    map<json> openProps = check open["properties"].ensureType();
    test:assertEquals(openProps["input"], true,
            "The json default's payload schema must not constrain the value: " + openSchema.toString());
}

@test:Config {
    groups: ["integration"]
}
function testAgentStartEnforcesTheSchemasRequiredQuery() returns error? {
    // What the schema marks required is what the start actually enforces. `query` is
    // required, so omitting it is a malformed request rather than a silent empty turn...
    management:WorkflowHandle|error noQuery = management:startWorkflowByType(
            "orderInputAgent", {input: {id: "ORD-8", qty: 1}});
    test:assertTrue(noQuery is error, "An envelope without a query must be rejected");
    if noQuery is error {
        test:assertTrue(noQuery.message().includes("'query' field is required"),
                "The rejection must name the missing field: " + noQuery.message());
    }

    management:WorkflowHandle|error empty = management:startWorkflowByType("orderInputAgent", ());
    test:assertTrue(empty is error, "A start with no envelope at all must be rejected");

    // ...while an explicitly empty query is a legitimate start for an agent whose run is
    // driven by something other than the opening turn.
    management:WorkflowHandle emptyQuery = check management:startWorkflowByType(
            "queryOnlyAgent", {query: ""});
    string turn = check queryOnlyAgent.waitForResult(emptyQuery.workflowId);
    test:assertEquals(turn.trim(), "", "An explicitly empty query is a valid start");

    // `input` is NOT required, so a typed agent starts on the query alone — the same
    // contract `run(query)` applies.
    management:WorkflowHandle noPayload = check management:startWorkflowByType(
            "orderInputAgent", {query: "Place this order"});
    string payloadless = check orderInputAgent.waitForResult(noPayload.workflowId);
    test:assertEquals(payloadless.trim(), "Place this order",
            "An omitted payload starts the agent on the query alone");

    // An explicit nil is how JSON spells "no payload", so it reads the same as omitting the
    // field rather than as a violation of the declared inputType.
    management:WorkflowHandle nilPayload = check management:startWorkflowByType(
            "orderInputAgent", {query: "Place this order", input: ()});
    string nilResult = check orderInputAgent.waitForResult(nilPayload.workflowId);
    test:assertEquals(nilResult.trim(), "Place this order",
            "An explicit nil payload reads the same as omitting the field");
}

@test:Config {
    groups: ["integration"]
}
function testAgentStartEnvelopeIsClosed() returns error? {
    // The envelope carries exactly the fields the schema defines. A misspelled key used to be
    // dropped in silence, which started the agent without the payload it was meant to carry —
    // the same silent-wrong-run this PR exists to remove.
    management:WorkflowHandle|error typo = management:startWorkflowByType(
            "orderInputAgent", {query: "Place this order", inpt: {id: "ORD-9", qty: 1}});
    test:assertTrue(typo is error, "A misspelled envelope field must be rejected");
    if typo is error {
        test:assertTrue(typo.message().includes("'inpt'"),
                "The rejection must name the offending field: " + typo.message());
    }

    // A query-only agent defines no payload field, so a posted one is reported as the payload
    // it is — including an explicit nil, which used to be accepted and ignored.
    management:WorkflowHandle|error nilOnQueryOnly = management:startWorkflowByType(
            "queryOnlyAgent", {query: "Just the query", input: ()});
    test:assertTrue(nilOnQueryOnly is error,
            "A query-only agent must reject an input field even when it is nil");
    if nilOnQueryOnly is error {
        test:assertTrue(nilOnQueryOnly.message().includes("takes no input payload"),
                "The rejection must explain that no payload is accepted: " + nilOnQueryOnly.message());
    }

    // ...and the published schema says the envelope is closed, so a caller reading it knows
    // that before sending anything.
    management:WorkflowDefinition[] defs = check management:listWorkflowDefinitions();
    foreach management:WorkflowDefinition def in defs {
        if def.workflowType == "orderInputAgent" || def.workflowType == "queryOnlyAgent" {
            map<json> schema = check (check (def.inputSchema ?: "").fromJsonString()).ensureType();
            test:assertEquals(schema["additionalProperties"], false,
                    def.workflowType + "'s schema must declare the envelope closed: "
                            + schema.toString());
        }
    }
}
