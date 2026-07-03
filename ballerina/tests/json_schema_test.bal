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

// ============================================================================
// JSON Schema builder unit tests (TypesUtil.toJsonSchema)
// ============================================================================
//
// Covers the schema-generation branches used to build workflow input schemas:
// primitives, arrays, maps, records (sealed/open, required/optional fields),
// nilable unions, multi-member unions, nested types, and broad json/anydata.
// ============================================================================

import ballerina/jballerina.java;
import ballerina/test;

isolated function buildJsonSchema(typedesc<anydata> t) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "buildJsonSchema"
} external;

// Complex types must be referenced through named aliases to be passed as typedesc values.
type IntArray int[];
type StringMap map<string>;
type OptString string?;
type IntOrString int|string;
type NilType ();
type ReadonlyPerson readonly & SchemaPerson;

@test:Config {groups: ["unit"]}
function testSchemaPrimitives() {
    test:assertTrue(buildJsonSchema(int).includes("\"integer\""), "int → integer");
    test:assertTrue(buildJsonSchema(byte).includes("\"integer\""), "byte → integer");
    test:assertTrue(buildJsonSchema(float).includes("\"number\""), "float → number");
    test:assertTrue(buildJsonSchema(decimal).includes("\"number\""), "decimal → number");
    test:assertTrue(buildJsonSchema(boolean).includes("\"boolean\""), "boolean → boolean");
    test:assertTrue(buildJsonSchema(string).includes("\"string\""), "string → string");
}

@test:Config {groups: ["unit"]}
function testSchemaArray() {
    string schema = buildJsonSchema(IntArray);
    test:assertTrue(schema.includes("\"array\""), "array type → array");
    test:assertTrue(schema.includes("\"items\""), "array → items");
    test:assertTrue(schema.includes("\"integer\""), "array element type → integer");
}

@test:Config {groups: ["unit"]}
function testSchemaMap() {
    string schema = buildJsonSchema(StringMap);
    test:assertTrue(schema.includes("\"object\""), "map → object");
    test:assertTrue(schema.includes("\"additionalProperties\""), "map → additionalProperties");
}

type SchemaPerson record {|
    string name;
    int age?;
|};

@test:Config {groups: ["unit"]}
function testSchemaSealedRecordRequiredAndOptional() {
    string schema = buildJsonSchema(SchemaPerson);
    test:assertTrue(schema.includes("\"object\""), "record → object");
    test:assertTrue(schema.includes("\"name\""), "record property name");
    test:assertTrue(schema.includes("\"age\""), "record property age");
    test:assertTrue(schema.includes("\"required\""), "record has required list");
    // sealed record → no open additionalProperties
    test:assertFalse(schema.includes("\"additionalProperties\""),
            "sealed record must not emit additionalProperties");
}

type SchemaOpenRecord record {
    string id;
};

@test:Config {groups: ["unit"]}
function testSchemaOpenRecordAdditionalProperties() {
    string schema = buildJsonSchema(SchemaOpenRecord);
    test:assertTrue(schema.includes("\"additionalProperties\""),
            "open record must emit additionalProperties from the rest type");
}

@test:Config {groups: ["unit"]}
function testSchemaNilableUnion() {
    // string? → single non-null member + null → type ["string","null"]
    string schema = buildJsonSchema(OptString);
    test:assertTrue(schema.includes("\"string\""), "nilable union keeps base type");
    test:assertTrue(schema.includes("\"null\""), "nilable union adds null");
}

@test:Config {groups: ["unit"]}
function testSchemaMultiMemberUnion() {
    // int|string → anyOf
    string schema = buildJsonSchema(IntOrString);
    test:assertTrue(schema.includes("\"anyOf\""), "multi-member union → anyOf");
    test:assertTrue(schema.includes("\"integer\"") && schema.includes("\"string\""),
            "anyOf should include both member schemas");
}

@test:Config {groups: ["unit"]}
function testSchemaNilType() {
    string schema = buildJsonSchema(NilType);
    test:assertTrue(schema.includes("\"null\""), "nil → null");
}

type SchemaOrder record {|
    string orderId;
    SchemaPerson customer;
    string[] items;
|};

@test:Config {groups: ["unit"]}
function testSchemaNestedRecord() {
    string schema = buildJsonSchema(SchemaOrder);
    test:assertTrue(schema.includes("\"orderId\""), "nested record top field");
    test:assertTrue(schema.includes("\"customer\""), "nested record object field");
    test:assertTrue(schema.includes("\"items\"") && schema.includes("\"array\""),
            "nested record array field");
}

@test:Config {groups: ["unit"]}
function testSchemaReadonlyIntersection() {
    // readonly & record → intersection dereferences to the record type
    string schema = buildJsonSchema(ReadonlyPerson);
    test:assertTrue(schema.includes("\"object\"") && schema.includes("\"name\""),
            "readonly intersection should resolve to the underlying record schema");
}

@test:Config {groups: ["unit"]}
function testSchemaBroadJson() {
    // json / anydata fall back to a generic object schema
    test:assertTrue(buildJsonSchema(json).includes("\"object\""), "json → generic object");
    test:assertTrue(buildJsonSchema(anydata).includes("\"object\""), "anydata → generic object");
}
