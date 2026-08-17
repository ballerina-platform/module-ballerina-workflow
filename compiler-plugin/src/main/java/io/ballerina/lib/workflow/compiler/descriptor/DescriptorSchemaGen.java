/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.compiler.descriptor;

import io.ballerina.compiler.api.symbols.ArrayTypeSymbol;
import io.ballerina.compiler.api.symbols.IntersectionTypeSymbol;
import io.ballerina.compiler.api.symbols.MapTypeSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.BAL_ANYDATA;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.BAL_NIL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_ARRAY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_BOOLEAN;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_INTEGER;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_NULL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_NUMBER;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_OBJECT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_STRING;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.LOSSY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA_ADDITIONAL_PROPERTIES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA_ANY_OF;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA_ITEMS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA_PROPERTIES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA_REQUIRED;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TYPE;

/**
 * Compile-time JSON Schema generation from semantic-model type symbols for the Workflow
 * Definition Descriptor. The emitted dialect is pinned to exactly what the runtime's
 * {@code TypesUtil.toJsonSchemaObject} produces for the same type — the descriptor replaces
 * runtime schema derivation, so the two must stay byte-equivalent for representable types
 * (golden tests enforce parity).
 *
 * <p>Every schema-bearing position in the descriptor is a <em>typed slot</em>
 * ({@code {type, schema?, lossy?}}): the resolved Ballerina type descriptor is always recorded;
 * the JSON Schema is a derived rendering emitted per representability tier:
 * <ul>
 *   <li>{@link Tier#EXACT} — closed anydata shapes: precise schema.</li>
 *   <li>{@link Tier#APPROXIMATE} — open anydata ({@code json}, {@code anydata}): the permissive
 *       schema, flagged {@code lossy: true}.</li>
 *   <li>{@link Tier#NONE} — {@code xml}, {@code error}, behavioral types, and composites mixing
 *       them into JSON data: the schema is omitted; only the type string is recorded.</li>
 * </ul>
 *
 * @since 0.9.0
 */
public final class DescriptorSchemaGen {

    private static final int MAX_DEPTH = 12;

    /** Representability of a Ballerina type as a JSON Schema. Order matters: lowest wins. */
    public enum Tier {
        NONE,
        APPROXIMATE,
        EXACT
    }

    private DescriptorSchemaGen() {
    }

    // ------------------------------------------------------------------
    // Typed slots
    // ------------------------------------------------------------------

    /**
     * Builds a typed slot ({@code {type, schema?, lossy?}}) for a single type.
     *
     * @param type the slot's type; may be {@code null} for an unknown type (treated as anydata)
     * @return the slot map
     */
    public static Map<String, Object> slot(TypeSymbol type) {
        Map<String, Object> slot = new LinkedHashMap<>();
        if (type == null) {
            slot.put(TYPE, BAL_ANYDATA);
            slot.put(SCHEMA, mapOf(TYPE, JSON_OBJECT));
            slot.put(LOSSY, Boolean.TRUE);
            return slot;
        }
        slot.put(TYPE, typeString(type));
        Tier tier = tierOf(type, 0);
        if (tier == Tier.EXACT) {
            slot.put(SCHEMA, schemaObject(type, 0));
        } else if (tier == Tier.APPROXIMATE) {
            slot.put(SCHEMA, schemaObject(type, 0));
            slot.put(LOSSY, Boolean.TRUE);
        }
        return slot;
    }

    /**
     * Builds a typed slot for a function's <em>output</em>: the full (possibly error-bearing)
     * type is recorded in {@code type}, while the schema describes only the success members —
     * errors don't cross a form boundary; they surface through execution history.
     *
     * @param returnType the declared return type; {@code null} means no return (nil)
     * @return the slot map
     */
    public static Map<String, Object> outputSlot(TypeSymbol returnType) {
        Map<String, Object> slot = new LinkedHashMap<>();
        if (returnType == null) {
            slot.put(TYPE, "()");
            slot.put(SCHEMA, mapOf(TYPE, JSON_NULL));
            return slot;
        }
        slot.put(TYPE, typeString(returnType));
        TypeSymbol successType = stripErrorMembers(returnType);
        if (successType == null) {
            // The whole type is error — legal, simply schema-less.
            return slot;
        }
        Tier tier = tierOf(successType, 0);
        if (tier == Tier.EXACT) {
            slot.put(SCHEMA, schemaObject(successType, 0));
        } else if (tier == Tier.APPROXIMATE) {
            slot.put(SCHEMA, schemaObject(successType, 0));
            slot.put(LOSSY, Boolean.TRUE);
        }
        return slot;
    }

    /**
     * Builds a typed slot for a parameter list rendered as a single input form — one property
     * per data parameter, defaultable and nilable parameters not required (mirrors the runtime's
     * {@code toParameterSchemaMap(..., honorParameterDefaults = true)}). The slot's {@code type}
     * is the parenthesized parameter list, which is the input's structural identity.
     *
     * @param params the data parameters (context/typedesc/client-object params already excluded)
     * @return the slot map
     */
    public static Map<String, Object> parameterSlot(List<ParameterSymbol> params) {
        Map<String, Object> slot = new LinkedHashMap<>();
        StringBuilder typeSig = new StringBuilder("(");
        Tier tier = Tier.EXACT;
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            ParameterSymbol p = params.get(i);
            String name = p.getName().filter(n -> !n.isBlank()).orElse("arg" + i);
            if (i > 0) {
                typeSig.append(", ");
            }
            typeSig.append(typeString(p.typeDescriptor())).append(' ').append(name);
            properties.put(name, schemaObject(p.typeDescriptor(), 0));
            boolean defaultable = p.paramKind() == ParameterKind.DEFAULTABLE;
            if (!defaultable && !isNilable(p.typeDescriptor(), 0)) {
                required.add(name);
            }
            tier = min(tier, tierOf(p.typeDescriptor(), 0));
        }
        typeSig.append(')');
        slot.put(TYPE, typeSig.toString());
        if (tier != Tier.NONE) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put(TYPE, JSON_OBJECT);
            schema.put(SCHEMA_PROPERTIES, properties);
            if (!required.isEmpty()) {
                schema.put(SCHEMA_REQUIRED, required);
            }
            slot.put(SCHEMA, schema);
            if (tier == Tier.APPROXIMATE) {
                slot.put(LOSSY, Boolean.TRUE);
            }
        }
        return slot;
    }

    // ------------------------------------------------------------------
    // Schema generation (mirror of TypesUtil.toJsonSchemaObject)
    // ------------------------------------------------------------------

    /**
     * Builds the JSON Schema object for a type — the pinned dialect: the exact subset the
     * runtime's {@code TypesUtil} emits for the same type.
     */
    public static Object schemaObject(TypeSymbol rawType, int depth) {
        if (rawType == null || depth > MAX_DEPTH) {
            return mapOf(TYPE, JSON_OBJECT);
        }
        TypeSymbol type = dereference(rawType, depth);
        if (type == null) {
            return mapOf(TYPE, JSON_OBJECT);
        }
        TypeDescKind kind = type.typeKind();
        switch (kind) {
            case INT, BYTE, INT_SIGNED8, INT_SIGNED16, INT_SIGNED32,
                 INT_UNSIGNED8, INT_UNSIGNED16, INT_UNSIGNED32 -> {
                return mapOf(TYPE, JSON_INTEGER);
            }
            case FLOAT, DECIMAL -> {
                return mapOf(TYPE, JSON_NUMBER);
            }
            case BOOLEAN -> {
                return mapOf(TYPE, JSON_BOOLEAN);
            }
            case STRING, STRING_CHAR -> {
                return mapOf(TYPE, JSON_STRING);
            }
            case NIL -> {
                return mapOf(TYPE, JSON_NULL);
            }
            case ARRAY -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put(TYPE, JSON_ARRAY);
                schema.put(SCHEMA_ITEMS, schemaObject(((ArrayTypeSymbol) type).memberTypeDescriptor(), depth + 1));
                return schema;
            }
            case MAP -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put(TYPE, JSON_OBJECT);
                schema.put(SCHEMA_ADDITIONAL_PROPERTIES, schemaObject(((MapTypeSymbol) type).typeParam(), depth + 1));
                return schema;
            }
            case RECORD -> {
                return recordSchema((RecordTypeSymbol) type, depth);
            }
            case UNION -> {
                return unionSchema((UnionTypeSymbol) type, depth);
            }
            default -> {
                // json/anydata and every unsupported kind: the permissive object schema
                // (mirrors the runtime fallback).
                return mapOf(TYPE, JSON_OBJECT);
            }
        }
    }

    private static Map<String, Object> recordSchema(RecordTypeSymbol recordType, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, JSON_OBJECT);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (Map.Entry<String, RecordFieldSymbol> entry : recordType.fieldDescriptors().entrySet()) {
            String fieldName = entry.getKey();
            RecordFieldSymbol field = entry.getValue();
            properties.put(fieldName, schemaObject(field.typeDescriptor(), depth + 1));
            // A field is required only when it must be present (not declared optional with `?`)
            // and cannot be nil — the same rule the runtime applies (SymbolFlags.OPTIONAL).
            if (!field.isOptional() && !isNilable(field.typeDescriptor(), depth + 1)) {
                required.add(fieldName);
            }
        }
        schema.put(SCHEMA_PROPERTIES, properties);
        if (!required.isEmpty()) {
            schema.put(SCHEMA_REQUIRED, required);
        }
        Optional<TypeSymbol> restType = recordType.restTypeDescriptor();
        if (restType.isPresent()) {
            schema.put(SCHEMA_ADDITIONAL_PROPERTIES, schemaObject(restType.get(), depth + 1));
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Object unionSchema(UnionTypeSymbol unionType, int depth) {
        List<TypeSymbol> nonNullMembers = new ArrayList<>();
        boolean hasNull = false;
        for (TypeSymbol member : unionType.memberTypeDescriptors()) {
            TypeSymbol m = dereference(member, depth + 1);
            if (m != null && m.typeKind() == TypeDescKind.NIL) {
                hasNull = true;
            } else {
                nonNullMembers.add(m);
            }
        }
        if (nonNullMembers.isEmpty()) {
            return mapOf(TYPE, JSON_NULL);
        }
        if (nonNullMembers.size() == 1) {
            Object base = schemaObject(nonNullMembers.get(0), depth + 1);
            if (hasNull && base instanceof Map<?, ?> baseMapRaw) {
                Map<String, Object> baseMap = (Map<String, Object>) baseMapRaw;
                Object typeVal = baseMap.get(TYPE);
                if (typeVal instanceof String typeStr) {
                    List<Object> unionTypes = new ArrayList<>();
                    unionTypes.add(typeStr);
                    unionTypes.add(JSON_NULL);
                    baseMap.put(TYPE, unionTypes);
                } else if (typeVal instanceof List<?> typeList) {
                    List<Object> unionTypes = new ArrayList<>(typeList);
                    if (!unionTypes.contains(JSON_NULL)) {
                        unionTypes.add(JSON_NULL);
                    }
                    baseMap.put(TYPE, unionTypes);
                }
            }
            return base;
        }
        List<Object> schemas = new ArrayList<>();
        for (TypeSymbol member : nonNullMembers) {
            schemas.add(schemaObject(member, depth + 1));
        }
        if (hasNull) {
            schemas.add(mapOf(TYPE, JSON_NULL));
        }
        Map<String, Object> anyOf = new LinkedHashMap<>();
        anyOf.put(SCHEMA_ANY_OF, schemas);
        return anyOf;
    }

    // ------------------------------------------------------------------
    // Tier classification
    // ------------------------------------------------------------------

    /** Classifies a type's representability as JSON Schema. */
    public static Tier tierOf(TypeSymbol rawType, int depth) {
        if (rawType == null || depth > MAX_DEPTH) {
            return Tier.APPROXIMATE;
        }
        TypeSymbol type = dereference(rawType, depth);
        if (type == null) {
            return Tier.APPROXIMATE;
        }
        switch (type.typeKind()) {
            case INT, BYTE, INT_SIGNED8, INT_SIGNED16, INT_SIGNED32, INT_UNSIGNED8, INT_UNSIGNED16,
                 INT_UNSIGNED32, FLOAT, DECIMAL, BOOLEAN, STRING, STRING_CHAR, NIL -> {
                return Tier.EXACT;
            }
            case SINGLETON -> {
                // The pinned dialect renders a singleton as the permissive object schema
                // (matching TypesUtil), which does not capture the value the type admits.
                // That is an approximation, so the slot must say so rather than claim to be
                // exact — a form generator would otherwise trust it for validation.
                return Tier.APPROXIMATE;
            }
            case ARRAY -> {
                return tierOf(((ArrayTypeSymbol) type).memberTypeDescriptor(), depth + 1);
            }
            case MAP -> {
                return tierOf(((MapTypeSymbol) type).typeParam(), depth + 1);
            }
            case RECORD -> {
                RecordTypeSymbol record = (RecordTypeSymbol) type;
                Tier tier = Tier.EXACT;
                for (RecordFieldSymbol field : record.fieldDescriptors().values()) {
                    tier = min(tier, tierOf(field.typeDescriptor(), depth + 1));
                }
                Optional<TypeSymbol> rest = record.restTypeDescriptor();
                if (rest.isPresent()) {
                    tier = min(tier, tierOf(rest.get(), depth + 1));
                }
                return tier;
            }
            case UNION -> {
                Tier tier = Tier.EXACT;
                for (TypeSymbol member : ((UnionTypeSymbol) type).memberTypeDescriptors()) {
                    tier = min(tier, tierOf(member, depth + 1));
                }
                return tier;
            }
            case JSON, ANYDATA -> {
                return Tier.APPROXIMATE;
            }
            case TABLE, TUPLE -> {
                // anydata-compatible but rendered by the runtime as the permissive schema.
                return Tier.APPROXIMATE;
            }
            default -> {
                // xml (all flavors), error, streams, objects, functions, futures, typedescs,
                // handles, any, never, readonly, regexp — not representable as JSON Schema.
                return Tier.NONE;
            }
        }
    }

    /** The lower of two tiers. */
    public static Tier min(Tier a, Tier b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    // ------------------------------------------------------------------
    // Type strings and helpers
    // ------------------------------------------------------------------

    /** The resolved Ballerina type descriptor string for a slot's {@code type} field. */
    public static String typeString(TypeSymbol type) {
        if (type == null) {
            return BAL_ANYDATA;
        }
        if (type.typeKind() == TypeDescKind.NIL) {
            return BAL_NIL;
        }
        if (type instanceof TypeReferenceTypeSymbol ref) {
            return ref.getName().orElseGet(type::signature);
        }
        if (type.typeKind() == TypeDescKind.UNION) {
            // Render member-wise so type references stay unqualified, matching how a single
            // reference renders (the raw union signature fully qualifies every member).
            StringBuilder sb = new StringBuilder();
            for (TypeSymbol member : ((UnionTypeSymbol) type).memberTypeDescriptors()) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(typeString(member));
            }
            return sb.length() > 0 ? sb.toString() : type.signature();
        }
        return type.signature();
    }

    /**
     * Strips {@code error} members from a (possibly union) return type, returning the success
     * type — a single member, a reduced union, or {@code null} when nothing remains.
     */
    public static TypeSymbol stripErrorMembers(TypeSymbol rawType) {
        TypeSymbol type = dereference(rawType, 0);
        if (type == null) {
            return null;
        }
        if (type.typeKind() == TypeDescKind.ERROR) {
            return null;
        }
        if (type.typeKind() == TypeDescKind.UNION) {
            List<TypeSymbol> nonError = new ArrayList<>();
            for (TypeSymbol member : ((UnionTypeSymbol) type).memberTypeDescriptors()) {
                TypeSymbol m = dereference(member, 0);
                if (m == null || m.typeKind() != TypeDescKind.ERROR) {
                    nonError.add(member);
                }
            }
            if (nonError.isEmpty()) {
                return null;
            }
            if (nonError.size() == 1) {
                return nonError.get(0);
            }
            // More than one non-error member: schema the original union minus errors is not
            // constructible as a symbol; keep the full union — error members degrade the schema
            // through the tier rules instead.
            return type;
        }
        return rawType;
    }

    /** Resolves type references and readonly intersections to the effective type. */
    public static TypeSymbol dereference(TypeSymbol type, int depth) {
        if (type == null || depth > MAX_DEPTH) {
            return type;
        }
        if (type instanceof TypeReferenceTypeSymbol ref) {
            TypeSymbol referred = ref.typeDescriptor();
            if (referred != null && referred != type) {
                return dereference(referred, depth + 1);
            }
        }
        if (type instanceof IntersectionTypeSymbol intersection) {
            for (TypeSymbol constituent : intersection.memberTypeDescriptors()) {
                if (constituent.typeKind() != TypeDescKind.READONLY) {
                    return dereference(constituent, depth + 1);
                }
            }
        }
        return type;
    }

    /** Mirrors the runtime's nilable check used for {@code required} membership. */
    public static boolean isNilable(TypeSymbol rawType, int depth) {
        if (rawType == null || depth > MAX_DEPTH) {
            return false;
        }
        TypeSymbol type = dereference(rawType, depth + 1);
        if (type == null) {
            return false;
        }
        if (type.typeKind() == TypeDescKind.NIL) {
            return true;
        }
        if (type.typeKind() == TypeDescKind.UNION) {
            for (TypeSymbol member : ((UnionTypeSymbol) type).memberTypeDescriptors()) {
                if (isNilable(member, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k, v);
        return map;
    }
}
