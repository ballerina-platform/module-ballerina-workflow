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
//
// Assertions parse the generated schema and inspect its structure directly
// (object keys, array members, anyOf contents, required/additionalProperties)
// rather than substring-matching the raw string, so a test cannot pass because
// an unrelated part of the schema happens to contain a keyword.
// ============================================================================

import ballerina/jballerina.java;
import ballerina/test;

isolated function buildJsonSchema(typedesc<anydata> t) returns string = @java:Method {
    'class: "io.ballerina.lib.workflow.test.TestNatives",
    name: "buildJsonSchema"
} external;

// Parses the schema built for `t` into an object for structural assertions.
isolated function schemaOf(typedesc<anydata> t) returns map<json>|error {
    return buildJsonSchema(t).fromJsonString().ensureType();
}

// Complex types must be referenced through named aliases to be passed as typedesc values.
type IntArray int[];
type StringMap map<string>;
type OptString string?;
type IntOrString int|string;
type NilType ();
type ReadonlyPerson readonly & SchemaPerson;

@test:Config {groups: ["unit"]}
function testSchemaPrimitives() returns error? {
    test:assertEquals((check schemaOf(int))["type"], "integer", "int → integer");
    test:assertEquals((check schemaOf(byte))["type"], "integer", "byte → integer");
    test:assertEquals((check schemaOf(float))["type"], "number", "float → number");
    test:assertEquals((check schemaOf(decimal))["type"], "number", "decimal → number");
    test:assertEquals((check schemaOf(boolean))["type"], "boolean", "boolean → boolean");
    test:assertEquals((check schemaOf(string))["type"], "string", "string → string");
}

@test:Config {groups: ["unit"]}
function testSchemaArray() returns error? {
    map<json> schema = check schemaOf(IntArray);
    test:assertEquals(schema["type"], "array", "array type → array");
    map<json> items = check schema["items"].ensureType();
    test:assertEquals(items["type"], "integer", "array element type → integer");
}

@test:Config {groups: ["unit"]}
function testSchemaMap() returns error? {
    map<json> schema = check schemaOf(StringMap);
    test:assertEquals(schema["type"], "object", "map → object");
    map<json> additional = check schema["additionalProperties"].ensureType();
    test:assertEquals(additional["type"], "string", "map value type → string");
}

type SchemaPerson record {|
    string name;
    int age?;
|};

@test:Config {groups: ["unit"]}
function testSchemaSealedRecordRequiredAndOptional() returns error? {
    map<json> schema = check schemaOf(SchemaPerson);
    test:assertEquals(schema["type"], "object", "record → object");

    map<json> props = check schema["properties"].ensureType();
    test:assertTrue(props.hasKey("name"), "record should expose the 'name' property");
    test:assertTrue(props.hasKey("age"), "record should expose the 'age' property");
    map<json> nameProp = check props["name"].ensureType();
    test:assertEquals(nameProp["type"], "string", "'name' should be typed string");

    json[] required = check schema["required"].ensureType();
    test:assertTrue(required.indexOf("name") is int, "required field 'name' should be listed");
    test:assertTrue(required.indexOf("age") is (), "optional field 'age' should not be required");

    // A sealed record must not emit open additionalProperties.
    test:assertFalse(schema.hasKey("additionalProperties"),
            "sealed record must not emit additionalProperties");
}

type SchemaOpenRecord record {
    string id;
};

@test:Config {groups: ["unit"]}
function testSchemaOpenRecordAdditionalProperties() returns error? {
    map<json> schema = check schemaOf(SchemaOpenRecord);
    test:assertTrue(schema.hasKey("additionalProperties"),
            "open record must emit additionalProperties from the rest type");
}

@test:Config {groups: ["unit"]}
function testSchemaNilableUnion() returns error? {
    // string? → single non-null member + null → type ["string","null"]
    map<json> schema = check schemaOf(OptString);
    json[] types = check schema["type"].ensureType();
    test:assertTrue(types.indexOf("string") is int, "nilable union keeps the base type");
    test:assertTrue(types.indexOf("null") is int, "nilable union adds null");
    test:assertEquals(types.length(), 2, "nilable union should be exactly [string, null]");
}

@test:Config {groups: ["unit"]}
function testSchemaMultiMemberUnion() returns error? {
    // int|string → anyOf with an integer schema and a string schema
    map<json> schema = check schemaOf(IntOrString);
    json[] anyOf = check schema["anyOf"].ensureType();
    test:assertEquals(anyOf.length(), 2, "int|string → anyOf of two members");

    string[] memberTypes = [];
    foreach json member in anyOf {
        map<json> m = check member.ensureType();
        string memberType = check m["type"].ensureType();
        memberTypes.push(memberType);
    }
    test:assertTrue(memberTypes.indexOf("integer") is int, "anyOf should contain the integer member");
    test:assertTrue(memberTypes.indexOf("string") is int, "anyOf should contain the string member");
}

@test:Config {groups: ["unit"]}
function testSchemaNilType() returns error? {
    test:assertEquals((check schemaOf(NilType))["type"], "null", "nil → null");
}

type SchemaOrder record {|
    string orderId;
    SchemaPerson customer;
    string[] items;
|};

@test:Config {groups: ["unit"]}
function testSchemaNestedRecord() returns error? {
    map<json> schema = check schemaOf(SchemaOrder);
    map<json> props = check schema["properties"].ensureType();

    map<json> orderId = check props["orderId"].ensureType();
    test:assertEquals(orderId["type"], "string", "nested record top field type");

    map<json> customer = check props["customer"].ensureType();
    test:assertEquals(customer["type"], "object", "nested record object field → object");
    map<json> customerProps = check customer["properties"].ensureType();
    test:assertTrue(customerProps.hasKey("name"), "nested object should expose its fields");

    map<json> items = check props["items"].ensureType();
    test:assertEquals(items["type"], "array", "nested record array field → array");
    map<json> itemsItems = check items["items"].ensureType();
    test:assertEquals(itemsItems["type"], "string", "array element type → string");
}

@test:Config {groups: ["unit"]}
function testSchemaReadonlyIntersection() returns error? {
    // readonly & record → intersection dereferences to the record type
    map<json> schema = check schemaOf(ReadonlyPerson);
    test:assertEquals(schema["type"], "object",
            "readonly intersection should resolve to the underlying record schema");
    map<json> props = check schema["properties"].ensureType();
    test:assertTrue(props.hasKey("name"), "resolved record should expose its fields");
}

@test:Config {groups: ["unit"]}
function testSchemaBroadJson() returns error? {
    // json / anydata fall back to a generic object schema
    test:assertEquals((check schemaOf(json))["type"], "object", "json → generic object");
    test:assertEquals((check schemaOf(anydata))["type"], "object", "anydata → generic object");
}
