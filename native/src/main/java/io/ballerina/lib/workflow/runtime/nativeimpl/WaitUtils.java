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

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.TupleType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.runtime.internal.values.FutureValue;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Native implementation for workflow data-wait utility functions.
 * <p>
 * Provides {@code waitForData} — a Temporal-safe, replay-aware way to wait for
 * N out of M data futures to complete. Uses {@link Workflow#await(java.util.function.Supplier)}
 * to cooperatively yield the workflow thread, avoiding the deadlocks that occur with
 * Ballerina's built-in {@code wait { ... }} syntax on Temporal signal futures.
 * During event-history replay the condition is already satisfied, so the function
 * completes immediately without any blocking.
 *
 * @since 0.3.0
 */
public final class WaitUtils {

    private static final Logger LOGGER = Workflow.getLogger(WaitUtils.class);

    private WaitUtils() {
        // Utility class
    }

    /**
     * Waits for at least {@code minCount} of the provided data futures to complete.
     * <p>
     * Uses {@code Workflow.await()} so it cooperates with Temporal's deterministic
     * scheduler and is a no-op during event-history replay (the condition is
     * already met). Returns the completed values converted to the type described
     * by {@code typedesc} — either a tuple {@code [T1, T2, ...]} or a plain
     * {@code anydata[]} array.
     *
     * @param futures  a Ballerina array of {@code future<anydata>} values
     * @param minCount the minimum number of futures that must complete
     * @param typedesc the expected return type (inferred by the compiler via {@code T = <>})
     * @return a typed tuple or array of completed values, or an error
     */
    public static Object waitForData(BArray futures, long minCount, BTypedesc typedesc) {
        int total = futures.size();
        int required = (int) minCount;

        if (required < 1 || required > total) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Invalid minCount=" + required + " for " + total
                            + " futures: minCount must be between 1 and " + total));
        }

        // Extract FutureValue references
        FutureValue[] futureValues = new FutureValue[total];
        for (int i = 0; i < total; i++) {
            futureValues[i] = (FutureValue) futures.get(i);
        }

        boolean replaying = Workflow.isReplaying();
        if (!replaying) {
            LOGGER.debug("[WaitUtils] waitForData: waiting for {}/{} futures", required, total);
        }

        // Cooperatively block until the required number of futures are done.
        // During replay this condition is immediately true — no blocking occurs.
        Workflow.await(() -> countDone(futureValues) >= required);

        if (!replaying) {
            LOGGER.debug("[WaitUtils] waitForData: {}/{} futures completed", required, total);
        }

        // Collect completed values in input-array order
        Object[] results = new Object[required];
        int collected = 0;
        for (FutureValue fv : futureValues) {
            if (collected >= required) {
                break;
            }
            if (fv.completableFuture.isDone()) {
                try {
                    results[collected] = fv.completableFuture.join();
                    collected++;
                } catch (Exception e) {
                    return ErrorCreator.createError(StringUtils.fromString(
                            "Error retrieving completed future value: " + e.getMessage()));
                }
            }
        }

        // Convert results to the caller's expected type (dependent-typing via typedesc)
        return convertResults(results, typedesc.getDescribingType());
    }

    /**
     * Converts the raw result array to the target type described by {@code targetType}.
     * <p>
     * <ul>
     *   <li>If {@code targetType} is a {@link TupleType}, each element is converted to
     *       its corresponding member type — enabling direct typed access without
     *       {@code cloneWithType}.</li>
     *   <li>If {@code targetType} is an {@link ArrayType} with a non-anydata element type,
     *       each element is uniformly converted to the array element type.</li>
     *   <li>Otherwise the results are returned as a plain {@code anydata[]} array.</li>
     * </ul>
     */
    private static Object convertResults(Object[] results, Type targetType) {
        if (targetType instanceof TupleType tupleType) {
            java.util.List<Type> memberTypes = tupleType.getTupleTypes();
            BArray tupleValue = ValueCreator.createTupleValue(tupleType);
            for (int i = 0; i < results.length; i++) {
                Type memberType = i < memberTypes.size() ? memberTypes.get(i) : tupleType.getRestType();
                Object raw = TypesUtil.convertJavaToBallerinaType(results[i]);
                Object converted = memberType != null
                        ? TypesUtil.cloneWithType(raw, memberType)
                        : raw;
                if (converted instanceof BError err) {
                    return err;
                }
                tupleValue.add(i, converted);
            }
            return tupleValue;
        }

        if (targetType instanceof ArrayType arrayType
                && arrayType.getElementType() != PredefinedTypes.TYPE_ANYDATA) {
            Type elemType = arrayType.getElementType();
            Object[] converted = new Object[results.length];
            for (int i = 0; i < results.length; i++) {
                Object raw = TypesUtil.convertJavaToBallerinaType(results[i]);
                Object conv = TypesUtil.cloneWithType(raw, elemType);
                if (conv instanceof BError err) {
                    return err;
                }
                converted[i] = conv;
            }
            return ValueCreator.createArrayValue(converted, arrayType);
        }

        // Default: return as anydata[]
        Object[] ballerinaResults = new Object[results.length];
        for (int i = 0; i < results.length; i++) {
            ballerinaResults[i] = TypesUtil.convertJavaToBallerinaType(results[i]);
        }
        return ValueCreator.createArrayValue(ballerinaResults,
                TypeCreator.createArrayType(PredefinedTypes.TYPE_ANYDATA));
    }

    private static int countDone(FutureValue[] futures) {
        int count = 0;
        for (FutureValue fv : futures) {
            if (fv.completableFuture.isDone()) {
                count++;
            }
        }
        return count;
    }
}
