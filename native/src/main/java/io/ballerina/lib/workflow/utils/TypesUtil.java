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

package io.ballerina.lib.workflow.utils;

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.flags.SymbolFlags;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.IntersectionType;
import io.ballerina.runtime.api.types.MapType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.ReferenceType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.ValueUtils;
import io.ballerina.runtime.api.utils.XmlUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTable;
import io.ballerina.runtime.api.values.BXml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for type conversions between Ballerina and Java types.
 *
 * @since 0.1.0
 */
public final class TypesUtil {

    // Error marker key for serialized errors
    public static final String ERROR_MARKER = "__error__";
    public static final String ERROR_MESSAGE = "message";

    // Marker key used to round-trip Ballerina `xml` values across Temporal's
    // JSON payload converter, which cannot serialize the `BXml` type graph
    // directly (immutable/intersection wrappers form a cycle).
    public static final String XML_MARKER = "__xml__";
    public static final String XML_WRAPPER_MARKER = "__workflow_xml_wrapper__";
    public static final String ERROR_TYPE = "errorType";

    private TypesUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Converts a Java object to its Ballerina equivalent.
     *
     * @param javaValue the Java value to convert
     * @return the Ballerina equivalent value
     */
    @SuppressWarnings("unchecked")
    public static Object convertJavaToBallerinaType(Object javaValue) {
        if (javaValue == null) {
            return null;
        }

        // Already a Ballerina type - return as-is to avoid double-conversion.
        // BMap implements java.util.Map, so we must guard against re-processing it.
        if (javaValue instanceof BString || javaValue instanceof BArray || javaValue instanceof BMap ||
                javaValue instanceof BError || javaValue instanceof BDecimal) {
            return javaValue;
        }

        // Check if this is a serialized error
        if (javaValue instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) javaValue;
            if (Boolean.TRUE.equals(map.get(ERROR_MARKER))) {
                String message = (String) map.getOrDefault(ERROR_MESSAGE, "Unknown error");
                return ErrorCreator.createError(StringUtils.fromString(message));
            }
            // XML round-trip marker: reconstruct a BXml from its string form.
            if (map.size() == 2 && Boolean.TRUE.equals(map.get(XML_WRAPPER_MARKER)) && map.get(
                    XML_MARKER) instanceof String xmlStr) {
                try {
                    return XmlUtils.parse(xmlStr);
                } catch (RuntimeException ignored) {
                    return convertMapToBMap(map);
                }
            }
            // Convert regular map to BMap
            return convertMapToBMap(map);
        }

        if (javaValue instanceof String) {
            return StringUtils.fromString((String) javaValue);
        }

        if (javaValue instanceof List) {
            return convertListToBArray((List<?>) javaValue);
        }

        if (javaValue instanceof BigDecimal) {
            return ValueCreator.createDecimalValue((BigDecimal) javaValue);
        }

        // Convert Integer to Long - Ballerina uses Long for int type
        // JSON deserialization may return Integer for values within int32 range
        if (javaValue instanceof Integer) {
            return ((Integer) javaValue).longValue();
        }

        // Primitive types (Long, Double, Boolean) are compatible
        return javaValue;
    }

    /**
     * Converts a Ballerina object to its Java equivalent.
     *
     * @param ballerinaValue the Ballerina value to convert
     * @return the Java equivalent value
     */
    @SuppressWarnings("unchecked")
    public static Object convertBallerinaToJavaType(Object ballerinaValue) {
        if (ballerinaValue == null) {
            return null;
        }

        if (ballerinaValue instanceof BString) {
            return ((BString) ballerinaValue).getValue();
        }

        if (ballerinaValue instanceof BMap) {
            return convertBMapToMap((BMap<BString, Object>) ballerinaValue);
        }

        if (ballerinaValue instanceof BArray) {
            return convertBArrayToList((BArray) ballerinaValue);
        }

        // A `table` cannot be JSON-serialised directly by Temporal's payload converter, but it is anydata and
        // its rows are records. Serialise it as a JSON array of rows; the inverse conversion rebuilds the table
        // via cloneWithType against the event future's table constraint type.
        if (ballerinaValue instanceof BTable<?, ?> table) {
            List<Object> rows = new ArrayList<>();
            for (Object row : table.values()) {
                rows.add(convertBallerinaToJavaType(row));
            }
            return rows;
        }

        if (ballerinaValue instanceof BDecimal) {
            return ((BDecimal) ballerinaValue).decimalValue();
        }

        if (ballerinaValue instanceof BError) {
            return serializeError((BError) ballerinaValue);
        }

        // Ballerina `xml` values cannot be JSON-serialised directly by
        // Temporal's payload converter. Wrap the canonical string form in a
        // marker map so the inverse conversion can reconstruct the BXml.
        if (ballerinaValue instanceof BXml) {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put(XML_WRAPPER_MARKER, true);
            wrapper.put(XML_MARKER, ballerinaValue.toString());
            return wrapper;
        }

        // Primitive types (Long, Double, Boolean) are compatible
        return ballerinaValue;
    }

    /**
     * Converts a Ballerina BMap to a Java Map.
     *
     * @param bMap the BMap to convert
     * @return the Java Map equivalent
     */
    public static Map<String, Object> convertBMapToMap(BMap<BString, Object> bMap) {
        Map<String, Object> result = new HashMap<>();
        for (BString key : bMap.getKeys()) {
            Object value = bMap.get(key);
            result.put(key.getValue(), convertBallerinaToJavaType(value));
        }
        return result;
    }

    /**
     * Converts a Java Map to a Ballerina BMap.
     *
     * @param map the Java Map to convert
     * @return the BMap equivalent
     */
    public static BMap<BString, Object> convertMapToBMap(Map<String, Object> map) {
        // Create a map<anydata> type to ensure it can be cast to anydata
        BMap<BString, Object> bMap = ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            bMap.put(StringUtils.fromString(entry.getKey()), convertJavaToBallerinaType(entry.getValue()));
        }
        return bMap;
    }

    /**
     * Converts a Ballerina BArray to a Java List.
     *
     * @param bArray the BArray to convert
     * @return the Java List equivalent
     */
    public static List<Object> convertBArrayToList(BArray bArray) {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < bArray.size(); i++) {
            result.add(convertBallerinaToJavaType(bArray.get(i)));
        }
        return result;
    }

    /**
     * Converts a Java List to a Ballerina BArray.
     *
     * @param list the Java List to convert
     * @return the BArray equivalent
     */
    public static BArray convertListToBArray(List<?> list) {
        ArrayType anydataArrayType = TypeCreator.createArrayType(PredefinedTypes.TYPE_ANYDATA);
        BArray bArray = ValueCreator.createArrayValue(anydataArrayType, list.size());
        for (int i = 0; i < list.size(); i++) {
            bArray.add(i, convertJavaToBallerinaType(list.get(i)));
        }
        return bArray;
    }

    /**
     * Serializes a BError to a Map for transport across workflow boundaries.
     *
     * @param error the BError to serialize
     * @return a Map representation of the error
     */
    public static Map<String, Object> serializeError(BError error) {
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put(ERROR_MARKER, true);
        errorMap.put(ERROR_MESSAGE, error.getMessage());
        errorMap.put(ERROR_TYPE, error.getType().getName());
        return errorMap;
    }

    /**
     * Clones a Ballerina value with a target type.
     * <p>
     * This is used for dependent typing support - converting the activity result to the expected type specified by the
     * typedesc parameter.
     *
     * @param value      the value to clone/convert
     * @param targetType the target type to convert to
     * @return the value converted to the target type, or an error if conversion fails
     */
    public static Object cloneWithType(Object value, Type targetType) {
        if (value == null) {
            return null;
        }

        // If value is already an error, return it as-is
        if (value instanceof BError) {
            return value;
        }

        try {
            // Use ValueUtils.convert to convert the value to the target type
            // This is the proper way to do cloneWithType in native code
            return ValueUtils.convert(value, targetType);
        } catch (BError e) {
            // If conversion fails, return the error
            return e;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString("Type conversion failed: " + e.getMessage()));
        }
    }

    /**
     * Validates and converts a completion/signal payload against the expected {@code targetType}.
     * <p>
     * Unlike {@link #cloneWithType(Object, Type)}, a {@code null} (Ballerina nil) value is rejected when the target
     * type does not accept nil. This prevents a nil from crossing the Java&rarr;Ballerina boundary as a non-nilable
     * {@code T}, which otherwise panics with a {@code TypeCastError} (see ballerina-library#8866). A successful call
     * returns the value coerced to {@code targetType}; a failure returns a {@link BError} describing the mismatch.
     *
     * @param value      the payload value to validate (may be {@code null})
     * @param targetType the expected type
     * @return the coerced value, or a {@link BError} if the value is not assignable to {@code targetType}
     */
    public static Object validateAndConvert(Object value, Type targetType) {
        if (value == null) {
            if (targetType == null || acceptsNil(targetType, 0)) {
                return null;
            }
            return ErrorCreator.createError(StringUtils.fromString(
                    "expected a non-nil value of type '" + targetType + "', but found ()"));
        }
        return cloneWithType(value, targetType);
    }

    /**
     * Returns {@code true} if a Ballerina nil ({@code ()}) is a valid value of {@code rawType} — i.e. the type is
     * {@code ()}, a nilable union, or one of the broad types {@code any}/{@code anydata}/{@code json} that include nil.
     * <p>
     * This is intentionally distinct from {@link #isNilableType(Type, int)}: that helper reports only explicit nilable
     * unions (used to decide JSON-Schema {@code required} membership and so treats {@code any}/{@code anydata}/
     * {@code json} as non-nilable), whereas this checks whether a nil <em>value</em> is assignable. Both share the same
     * conservative depth guard: an unknown or too-deeply-nested type is treated as <em>not</em> accepting nil, so
     * {@link #validateAndConvert} rejects the nil rather than letting it panic at the boundary (see #8866).
     */
    private static boolean acceptsNil(Type rawType, int depth) {
        if (rawType == null || depth > 12) {
            return false;
        }
        Type type = dereferenceType(rawType, depth + 1);
        int tag = type.getTag();
        if (tag == TypeTags.NULL_TAG || tag == TypeTags.ANY_TAG || tag == TypeTags.ANYDATA_TAG
                || tag == TypeTags.JSON_TAG) {
            return true;
        }
        if (type instanceof UnionType unionType) {
            for (Type member : unionType.getMemberTypes()) {
                if (acceptsNil(member, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds a JSON Schema string for the provided Ballerina type.
     *
     * @param type Ballerina runtime type
     * @return JSON schema string
     */
    public static String toJsonSchema(Type type) {
        Object schema = toJsonSchemaObject(type, 0);
        return toJsonString(schema);
    }

    /**
     * Builds a JSON Schema object for a list of function parameters.
     *
     * @param parameters   function parameters
     * @param startIndex   first parameter index to include
     * @param endExclusive exclusive upper bound
     * @return JSON schema string for an object with parameter-named fields
     */
    public static String toJsonSchemaForParameters(Parameter[] parameters, int startIndex, int endExclusive) {
        return toJsonSchemaForParameters(parameters, startIndex, endExclusive, false);
    }

    /**
     * Builds a JSON Schema string for a list of function parameters, optionally treating defaultable
     * parameters as not required. Callers describing a call-site input form (where the runtime
     * supplies the declared default when a value is omitted) should pass
     * {@code honorParameterDefaults = true}; nilable parameters are never required.
     *
     * @param parameters             function parameters
     * @param startIndex             first parameter index to include
     * @param endExclusive           exclusive upper bound
     * @param honorParameterDefaults when {@code true}, parameters declared with a default value are
     *                               omitted from {@code required}
     * @return JSON schema string for an object with parameter-named fields
     */
    public static String toJsonSchemaForParameters(Parameter[] parameters, int startIndex, int endExclusive,
                                                   boolean honorParameterDefaults) {
        return toJsonString(toParameterSchemaMap(parameters, startIndex, endExclusive, honorParameterDefaults));
    }

    /**
     * Builds a JSON Schema object (as a map) for a list of function parameters. Same shape as
     * {@link #toJsonSchemaForParameters} but returns the underlying map so callers can embed it into larger
     * structures (e.g. an agent tool definition) without a string round-trip.
     *
     * @param parameters   function parameters
     * @param startIndex   first parameter index to include
     * @param endExclusive exclusive upper bound
     * @return a map representing the JSON schema object
     */
    public static Map<String, Object> toParameterSchemaMap(Parameter[] parameters, int startIndex, int endExclusive) {
        return toParameterSchemaMap(parameters, startIndex, endExclusive, false);
    }

    /**
     * Builds a JSON Schema object (as a map), optionally treating defaultable parameters as not required.
     *
     * @param parameters             function parameters
     * @param startIndex             first parameter index to include
     * @param endExclusive           exclusive upper bound
     * @param honorParameterDefaults when {@code true}, defaultable parameters are omitted from {@code required}
     * @return a map representing the JSON schema object
     */
    public static Map<String, Object> toParameterSchemaMap(Parameter[] parameters, int startIndex, int endExclusive,
                                                           boolean honorParameterDefaults) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();

        if (parameters != null) {
            int start = Math.max(0, startIndex);
            int end = Math.min(parameters.length, Math.max(start, endExclusive));
            for (int i = start; i < end; i++) {
                Parameter p = parameters[i];
                String name = p.name != null && !p.name.isBlank() ? p.name : "arg" + i;
                properties.put(name, toJsonSchemaObject(p.type, 0));
                boolean defaultable = honorParameterDefaults && p.isDefault;
                if (!defaultable && !isNilableType(p.type, 0)) {
                    required.add(name);
                }
            }
        }

        root.put("properties", properties);
        if (!required.isEmpty()) {
            root.put("required", required);
        }
        return root;
    }

    private static Object toJsonSchemaObject(Type rawType, int depth) {
        if (rawType == null || depth > 12) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            return fallback;
        }

        Type type = dereferenceType(rawType, depth);
        int tag = type.getTag();

        if (tag == TypeTags.INT_TAG || tag == TypeTags.BYTE_TAG) {
            return mapOf("type", "integer");
        }
        if (tag == TypeTags.FLOAT_TAG || tag == TypeTags.DECIMAL_TAG) {
            return mapOf("type", "number");
        }
        if (tag == TypeTags.BOOLEAN_TAG) {
            return mapOf("type", "boolean");
        }
        if (tag == TypeTags.STRING_TAG || tag == TypeTags.CHAR_STRING_TAG) {
            return mapOf("type", "string");
        }
        if (tag == TypeTags.NULL_TAG) {
            return mapOf("type", "null");
        }

        if (tag == TypeTags.ARRAY_TAG && type instanceof ArrayType arrayType) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", toJsonSchemaObject(arrayType.getElementType(), depth + 1));
            return schema;
        }

        if (tag == TypeTags.MAP_TAG && type instanceof MapType mapType) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", toJsonSchemaObject(mapType.getConstrainedType(), depth + 1));
            return schema;
        }

        if (tag == TypeTags.RECORD_TYPE_TAG && type instanceof RecordType recordType) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            Map<String, Object> properties = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                String fieldName = entry.getKey();
                Field field = entry.getValue();
                properties.put(fieldName, toJsonSchemaObject(field.getFieldType(), depth + 1));
                // A field is required only when it must be present (not declared optional with `?`) and cannot be nil.
                boolean optional = SymbolFlags.isFlagOn(field.getFlags(), SymbolFlags.OPTIONAL);
                if (!optional && !isNilableType(field.getFieldType(), depth + 1)) {
                    required.add(fieldName);
                }
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            if (!recordType.isSealed()) {
                Type restType = recordType.getRestFieldType();
                schema.put("additionalProperties",
                           restType != null ? toJsonSchemaObject(restType, depth + 1) : Boolean.TRUE);
            }
            return schema;
        }

        if (tag == TypeTags.UNION_TAG && type instanceof UnionType unionType) {
            List<Type> members = unionType.getMemberTypes();
            List<Type> nonNullMembers = new ArrayList<>();
            boolean hasNull = false;
            for (Type member : members) {
                Type m = dereferenceType(member, depth + 1);
                if (m.getTag() == TypeTags.NULL_TAG) {
                    hasNull = true;
                } else {
                    nonNullMembers.add(m);
                }
            }

            if (nonNullMembers.isEmpty()) {
                return mapOf("type", "null");
            }

            if (nonNullMembers.size() == 1) {
                Object base = toJsonSchemaObject(nonNullMembers.get(0), depth + 1);
                if (hasNull && base instanceof Map<?, ?> baseMapRaw) {
                    @SuppressWarnings("unchecked") Map<String, Object> baseMap = (Map<String, Object>) baseMapRaw;
                    Object typeVal = baseMap.get("type");
                    if (typeVal instanceof String typeStr) {
                        List<Object> unionTypes = new ArrayList<>();
                        unionTypes.add(typeStr);
                        unionTypes.add("null");
                        baseMap.put("type", unionTypes);
                    } else if (typeVal instanceof List<?> typeList) {
                        List<Object> unionTypes = new ArrayList<>(typeList);
                        if (!unionTypes.contains("null")) {
                            unionTypes.add("null");
                        }
                        baseMap.put("type", unionTypes);
                    }
                }
                return base;
            }

            Map<String, Object> anyOf = new LinkedHashMap<>();
            List<Object> schemas = new ArrayList<>();
            for (Type member : nonNullMembers) {
                schemas.add(toJsonSchemaObject(member, depth + 1));
            }
            if (hasNull) {
                schemas.add(mapOf("type", "null"));
            }
            anyOf.put("anyOf", schemas);
            return anyOf;
        }

        // For json/anydata and all other unsupported tags, return a generic object schema.
        return mapOf("type", "object");
    }

    private static Type dereferenceType(Type type, int depth) {
        if (type == null || depth > 12) {
            return type;
        }

        if (type instanceof ReferenceType refType) {
            Type referred = refType.getReferredType();
            if (referred != type) {
                return dereferenceType(referred, depth + 1);
            }
        }

        if (type instanceof IntersectionType intersectionType) {
            for (Type constituent : intersectionType.getConstituentTypes()) {
                if (constituent.getTag() != TypeTags.READONLY_TAG) {
                    return dereferenceType(constituent, depth + 1);
                }
            }
        }

        return type;
    }

    private static boolean isNilableType(Type rawType, int depth) {
        if (rawType == null || depth > 12) {
            return false;
        }

        Type type = dereferenceType(rawType, depth + 1);
        if (type == null) {
            return false;
        }

        if (type.getTag() == TypeTags.NULL_TAG) {
            return true;
        }

        if (type instanceof UnionType unionType) {
            for (Type member : unionType.getMemberTypes()) {
                if (isNilableType(member, depth + 1)) {
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

    /**
     * Serializes a plain Java value (maps, lists, strings, numbers, booleans, null) to a JSON string.
     *
     * @param value the value to serialize
     * @return the JSON string
     */
    public static String toJsonString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\":");
                sb.append(toJsonString(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(toJsonString(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
