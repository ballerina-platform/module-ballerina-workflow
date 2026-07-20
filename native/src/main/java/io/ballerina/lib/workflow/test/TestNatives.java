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

package io.ballerina.lib.workflow.test;

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;

import java.util.HashMap;
import java.util.Map;

/**
 * Native implementations for test-only external functions.
 * <p>
 * These functions back dependently-typed {@code @Activity} external functions used in unit tests. Dependently-typed
 * functions require an {@code external} body in Ballerina, so a Java implementation is necessary.
 *
 * @since 0.2.1
 */
public final class TestNatives {

    private TestNatives() {
        // Utility class, prevent instantiation
    }

    /**
     * Dependently-typed activity implementation for tests. Converts the input string to the target type specified by
     * the typedesc.
     *
     * @param data     the input string data
     * @param typedesc the target type descriptor (from dependent typing)
     * @return the data converted to the target type, or an error
     */
    public static Object convertData(BString data, BTypedesc typedesc) {
        Type targetType = typedesc.getDescribingType();
        return TypesUtil.cloneWithType(data, targetType);
    }

    /**
     * Simulates the {@code sendData} round-trip for the given value without a live workflow server.
     * <p>
     * It mirrors the runtime path that broke for non-record payloads: the value is converted to its Java
     * representation on the send side ({@link TypesUtil#convertBallerinaToJavaType}), converted back on the
     * receive side ({@link TypesUtil#convertJavaToBallerinaType}), and finally validated/coerced to the event
     * future's constraint type ({@link TypesUtil#validateAndConvert}, matching {@code WaitUtils}). This lets unit
     * tests assert that primitives, json and xml survive the round-trip (not only records), that a nil is accepted
     * only when the target type is nilable, and that mismatched payloads surface an error.
     *
     * @param data     the value being sent (any anydata, including nil)
     * @param typedesc the target type the receiving {@code future<T>} expects
     * @return the value after the full send/receive/convert round-trip, or an error
     */
    public static Object roundTripSendData(Object data, BTypedesc typedesc) {
        Object javaData = TypesUtil.convertBallerinaToJavaType(data);
        Object ballerinaData = TypesUtil.convertJavaToBallerinaType(javaData);
        return TypesUtil.validateAndConvert(ballerinaData, typedesc.getDescribingType());
    }

    /**
     * Builds the JSON Schema string for the type described by {@code typedesc}. Backs unit tests that exercise
     * {@link TypesUtil#toJsonSchema(Type)} - the schema builder used to generate workflow input schemas.
     *
     * @param typedesc the type to describe
     * @return the JSON Schema as a string
     */
    public static BString buildJsonSchema(BTypedesc typedesc) {
        return StringUtils.fromString(TypesUtil.toJsonSchema(typedesc.getDescribingType()));
    }

    /**
     * Simulates the human task completion payload path against the task's expected result type, without a live
     * workflow server.
     * <p>
     * It mirrors the runtime: the completion value is serialised on the send side
     * ({@link TypesUtil#convertBallerinaToJavaType}), deserialised on the receive side
     * ({@link TypesUtil#convertJavaToBallerinaType}), and validated/coerced against the expected type
     * ({@link TypesUtil#validateAndConvert}). This lets unit tests assert that empty (nil), basic, and complex
     * payloads succeed for compatible types and that mismatched payloads return an error instead of completing the
     * task (ballerina-library#8866).
     *
     * @param result   the completion value (any anydata, including nil)
     * @param typedesc the task's expected result type {@code T}
     * @return the value after validation/coercion, or an error when it does not match {@code T}
     */
    public static Object simulateHumanTaskCompletion(Object result, BTypedesc typedesc) {
        Object javaResult = TypesUtil.convertBallerinaToJavaType(result);
        Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(javaResult);
        return TypesUtil.validateAndConvert(ballerinaResult, typedesc.getDescribingType());
    }

    /**
     * Backs the {@code generate} remote method of the mock {@code ai:ModelProvider} used in agent tests. Because
     * {@code ai:ModelProvider.generate} is dependently typed, implementations must have an external body — real
     * providers (Wso2, Anthropic, OpenAI) all bind it to Java. This mock returns a fixed structured value coerced
     * to the requested type.
     *
     * @param self     the mock model provider object (unused)
     * @param prompt   the prompt object (unused)
     * @param typedesc the expected return type
     * @return the fixed value coerced to {@code typedesc}, or an error
     */
    public static Object mockGenerate(BObject self, BObject prompt, BTypedesc typedesc) {
        Map<String, Object> fixed = new HashMap<>();
        fixed.put("summary", "generated summary");
        fixed.put("score", 7L);
        Object ballerinaValue = TypesUtil.convertJavaToBallerinaType(fixed);
        return TypesUtil.cloneWithType(ballerinaValue, typedesc.getDescribingType());
    }
}
