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

package io.ballerina.stdlib.workflow.utils;

import io.ballerina.runtime.api.flags.SymbolFlags;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.ReferenceType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Extracts correlation keys from workflow input and signal data.
 * <p>
 * Correlation keys are readonly fields in record types that are used to:
 * <ul>
 *   <li>Identify workflow instances when sending signals</li>
 *   <li>Register Temporal Search Attributes for visibility queries</li>
 *   <li>Enable lookup of workflows by business identifiers</li>
 * </ul>
 * <p>
 * If no readonly fields are defined, falls back to the 'id' field for
 * backward compatibility.
 *
 * @since 0.1.0
 */
public final class CorrelationExtractor {

    private CorrelationExtractor() {
        // Utility class, prevent instantiation
    }

    /**
     * Extracts correlation key values from input data based on the record type.
     * <p>
     * Only readonly fields in the record type are considered correlation keys.
     * The 'id' field must be explicitly marked as readonly to be used for correlation.
     *
     * @param inputData  the input data map
     * @param recordType the record type definition (can be null)
     * @return map of correlation key names to values (never null, may be empty)
     */
    public static Map<String, Object> extractCorrelationKeys(
            BMap<BString, Object> inputData, RecordType recordType) {

        Map<String, Object> correlationKeys = new LinkedHashMap<>();

        if (recordType != null) {
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                Field field = entry.getValue();
                String fieldName = entry.getKey();

                // Only readonly fields are correlation keys
                if (isReadonlyField(field)) {
                    Object value = inputData.get(StringUtils.fromString(fieldName));
                    if (value != null) {
                        correlationKeys.put(fieldName, value);
                    }
                }
            }
        }

        return correlationKeys;
    }

    /**
     * Extracts correlation keys from a Ballerina BMap based on its record type.
     * <p>
     * This overload extracts the RecordType from the BMap itself.
     *
     * @param inputData the input data as a BMap
     * @return map of correlation key names to values
     */
    public static Map<String, Object> extractCorrelationKeysFromMap(BMap<BString, Object> inputData) {
        Map<String, Object> correlationKeys = new LinkedHashMap<>();

        if (inputData == null) {
            return correlationKeys;
        }

        // Try to get the record type from the BMap
        Type type = inputData.getType();
        RecordType recordType = null;

        if (type instanceof RecordType) {
            recordType = (RecordType) type;
        } else if (type instanceof ReferenceType) {
            Type referredType = ((ReferenceType) type).getReferredType();
            if (referredType instanceof RecordType) {
                recordType = (RecordType) referredType;
            }
        }

        if (recordType != null) {
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                Field field = entry.getValue();
                String fieldName = entry.getKey();

                if (isReadonlyField(field)) {
                    Object value = inputData.get(StringUtils.fromString(fieldName));
                    if (value != null) {
                        // Convert BString to String for consistency
                        if (value instanceof BString) {
                            correlationKeys.put(fieldName, ((BString) value).getValue());
                        } else {
                            correlationKeys.put(fieldName, value);
                        }
                    }
                }
            }
        }

        // Note: We only extract readonly fields as correlation keys
        // The 'id' field is used for workflowId generation, not for correlation search attributes
        // unless it's explicitly marked as readonly

        return correlationKeys;
    }

    /**
     * Extracts correlation keys from a plain Java Map (after type conversion).
     *
     * @param inputData  the input data as a Java Map
     * @param recordType the record type definition (can be null)
     * @return map of correlation key names to values
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractCorrelationKeysFromMap(
            Object inputData, RecordType recordType) {

        Map<String, Object> correlationKeys = new LinkedHashMap<>();

        if (!(inputData instanceof Map)) {
            return correlationKeys;
        }

        Map<String, Object> inputMap = (Map<String, Object>) inputData;

        if (recordType != null) {
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                Field field = entry.getValue();
                String fieldName = entry.getKey();

                if (isReadonlyField(field)) {
                    Object value = inputMap.get(fieldName);
                    if (value != null) {
                        correlationKeys.put(fieldName, value);
                    }
                }
            }
        }

        // Note: We only extract readonly fields as correlation keys
        // The 'id' field is used for workflowId generation, not for correlation search attributes
        // unless it's explicitly marked as readonly

        return correlationKeys;
    }

    /**
     * Checks if a field is marked as readonly.
     *
     * @param field the field to check
     * @return true if the field is readonly
     */
    public static boolean isReadonlyField(Field field) {
        return SymbolFlags.isFlagOn(field.getFlags(), SymbolFlags.READONLY);
    }

    // SecureRandom for UUID v7 generation
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a workflow ID using UUID v7 (time-ordered UUID).
     * <p>
     * UUID v7 combines a Unix timestamp with random bits, providing:
     * <ul>
     *   <li>Uniqueness across machines and time</li>
     *   <li>Sortability by creation time</li>
     *   <li>No collisions even in clustered environments</li>
     * </ul>
     * Format: {@code processName-<uuid7>}
     * <p>
     * Correlation keys are stored as Temporal search attributes, not embedded in the workflow ID.
     *
     * @param processName the process name for namespacing
     * @return workflow ID in format: processName-uuid7
     */
    public static String generateWorkflowId(String processName) {
        // Generate UUID v7 (time-ordered UUID)
        // Correlation keys are stored as search attributes, not in the workflow ID
        return processName + "-" + generateUuidV7();
    }

    /**
     * Generates a UUID v7 (time-ordered UUID) as per RFC 9562.
     * <p>
     * Structure (128 bits):
     * <ul>
     *   <li>48 bits: Unix timestamp in milliseconds</li>
     *   <li>4 bits: Version (7)</li>
     *   <li>12 bits: Random</li>
     *   <li>2 bits: Variant (10)</li>
     *   <li>62 bits: Random</li>
     * </ul>
     *
     * @return UUID v7 string
     */
    private static String generateUuidV7() {
        long timestamp = System.currentTimeMillis();

        // Generate random bits
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        // Build most significant bits: 48-bit timestamp + 4-bit version + 12-bit random
        long msb = (timestamp << 16) | (7L << 12) | ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        // Build least significant bits: 2-bit variant (10) + 62-bit random
        long lsb = ((randomBytes[2] & 0x3FL) | 0x80L) << 56 // variant bits
                | ((long) (randomBytes[3] & 0xFFL) << 48)
                | ((long) (randomBytes[4] & 0xFFL) << 40)
                | ((long) (randomBytes[5] & 0xFFL) << 32)
                | ((long) (randomBytes[6] & 0xFFL) << 24)
                | ((long) (randomBytes[7] & 0xFFL) << 16)
                | ((long) (randomBytes[8] & 0xFFL) << 8)
                | (randomBytes[9] & 0xFFL);

        return new UUID(msb, lsb).toString();
    }

    /**
     * Converts correlation keys to Temporal Search Attributes format.
     * <p>
     * Field names are capitalized to follow Temporal naming conventions
     * (e.g., 'orderId' becomes 'OrderId').
     *
     * @param correlationKeys the correlation key map
     * @return search attributes map (key -> typed value)
     */
    public static Map<String, Object> toSearchAttributes(Map<String, Object> correlationKeys) {
        Map<String, Object> searchAttributes = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : correlationKeys.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Temporal search attributes use "Correlation" prefix + capitalized names
            // This matches the naming convention used in SearchAttributeRegistry
            String searchAttrKey = "Correlation" + capitalize(key);

            // Convert value to appropriate Temporal type
            searchAttributes.put(searchAttrKey, convertToSearchAttributeValue(value));
        }

        return searchAttributes;
    }

    /**
     * Checks if a record type has any readonly fields.
     *
     * @param recordType the record type to check
     * @return true if the record has at least one readonly field
     */
    public static boolean hasReadonlyFields(RecordType recordType) {
        if (recordType == null) {
            return false;
        }

        for (Field field : recordType.getFields().values()) {
            if (isReadonlyField(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a record type has an 'id' field.
     *
     * @param recordType the record type to check
     * @return true if the record has an 'id' field
     */
    public static boolean hasIdField(RecordType recordType) {
        if (recordType == null) {
            return false;
        }
        return recordType.getFields().containsKey("id");
    }

    /**
     * Gets the readonly field names and their types from a record type.
     *
     * @param recordType the record type
     * @return map of readonly field names to their types
     */
    public static Map<String, Type> getReadonlyFields(RecordType recordType) {
        Map<String, Type> readonlyFields = new LinkedHashMap<>();

        if (recordType == null) {
            return readonlyFields;
        }

        for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
            Field field = entry.getValue();
            if (isReadonlyField(field)) {
                readonlyFields.put(entry.getKey(), field.getFieldType());
            }
        }

        return readonlyFields;
    }

    /**
     * Capitalizes the first letter of a string.
     *
     * @param s the string to capitalize
     * @return the capitalized string
     */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Converts a Ballerina value to a Temporal Search Attribute value.
     *
     * @param value the value to convert
     * @return the converted value
     */
    private static Object convertToSearchAttributeValue(Object value) {
        if (value instanceof BString) {
            return ((BString) value).getValue();
        }
        // Primitive types (Long, Double, Boolean) are compatible with Temporal
        return value;
    }
}
