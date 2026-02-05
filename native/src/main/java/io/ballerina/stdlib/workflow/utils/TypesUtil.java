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

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.utils.ValueUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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

        // Check if this is a serialized error
        if (javaValue instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) javaValue;
            if (Boolean.TRUE.equals(map.get(ERROR_MARKER))) {
                String message = (String) map.getOrDefault(ERROR_MESSAGE, "Unknown error");
                return ErrorCreator.createError(StringUtils.fromString(message));
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

        if (ballerinaValue instanceof BDecimal) {
            return ((BDecimal) ballerinaValue).decimalValue();
        }

        if (ballerinaValue instanceof BError) {
            return serializeError((BError) ballerinaValue);
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
            bMap.put(StringUtils.fromString(entry.getKey()),
                    convertJavaToBallerinaType(entry.getValue()));
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
     * This is used for dependent typing support - converting the activity result
     * to the expected type specified by the typedesc parameter.
     *
     * @param value the value to clone/convert
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
            return ErrorCreator.createError(
                    StringUtils.fromString("Type conversion failed: " + e.getMessage()));
        }
    }
}
