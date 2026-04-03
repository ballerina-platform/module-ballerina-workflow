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
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.runtime.internal.values.FutureValue;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Native implementation for workflow data-wait utility functions.
 * <p>
 * Provides {@code awaitFutures} — a Temporal-safe, replay-aware way to wait for
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

    private static final BString HOURS_KEY = StringUtils.fromString("hours");
    private static final BString MINUTES_KEY = StringUtils.fromString("minutes");
    private static final BString SECONDS_KEY = StringUtils.fromString("seconds");

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
     * <p>
     * When {@code timeout} is non-null, uses the timed overload of
     * {@code Workflow.await(duration, condition)}: if the required number of futures
     * have not completed within the given duration an error is returned.
     *
     * @param self     the Context BObject (unused — needed for remote method routing)
     * @param futures  a Ballerina array of {@code future<anydata>} values
     * @param minCount the minimum number of futures that must complete
     * @param timeout  optional Ballerina {@code time:Duration} record; {@code null} means wait forever
     * @param typedesc the expected return type (inferred by the compiler via {@code T = <>})
     * @return a typed tuple or array of completed values, or an error
     */
    @SuppressWarnings("unchecked")
    public static Object awaitFutures(BObject self, BArray futures, long minCount,
                                      Object timeout, BTypedesc typedesc) {
        int originalTotal = futures.size();

        // Store original futures for positional result alignment.
        // Each position in the result must correspond to the same position in the input array.
        FutureValue[] originalFutures = new FutureValue[originalTotal];
        for (int i = 0; i < originalTotal; i++) {
            originalFutures[i] = (FutureValue) futures.get(i);
        }

        // Extract unique FutureValue references using identity comparison to prevent
        // duplicate future instances from being counted more than once.
        java.util.IdentityHashMap<FutureValue, Boolean> seen = new java.util.IdentityHashMap<>(originalTotal);
        FutureValue[] uniqueFutures = new FutureValue[originalTotal];
        int uniqueCount = 0;
        for (int i = 0; i < originalTotal; i++) {
            if (seen.putIfAbsent(originalFutures[i], Boolean.TRUE) == null) {
                uniqueFutures[uniqueCount++] = originalFutures[i];
            }
        }
        uniqueFutures = java.util.Arrays.copyOf(uniqueFutures, uniqueCount);

        // Validate minCount as long before casting to int to avoid truncation overflow.
        if ((minCount < 1 && uniqueCount > 0) || minCount > uniqueCount) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Invalid minCount=" + minCount + " for " + uniqueCount
                            + " futures: minCount must be between 1 and " + uniqueCount));
        }
        int required = (int) minCount;

        // Final aliases required for use inside lambda expressions.
        final FutureValue[] lambdaFutures = uniqueFutures;
        final int lambdaRequired = required;

        boolean replaying = Workflow.isReplaying();
        if (!replaying) {
            LOGGER.debug("[WaitUtils] awaitFutures: waiting for {}/{} futures", required, originalTotal);
        }

        // Cooperatively block until the required number of futures are done (or timeout).
        // During replay this condition is immediately true — no blocking occurs.
        boolean conditionMet;
        if (timeout instanceof BMap<?, ?> timeoutMap) {
            long timeoutMillis = durationToMillis((BMap<BString, Object>) timeoutMap);
            conditionMet = Workflow.await(
                    java.time.Duration.ofMillis(timeoutMillis),
                    () -> countDone(lambdaFutures) >= lambdaRequired);
        } else {
            Workflow.await(() -> countDone(lambdaFutures) >= lambdaRequired);
            conditionMet = true;
        }

        if (!conditionMet) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Timeout waiting for futures: only " + countDone(uniqueFutures)
                            + " of " + required + " completed within the specified duration"));
        }

        if (!replaying) {
            LOGGER.debug("[WaitUtils] awaitFutures: {}/{} futures completed",
                    countDone(uniqueFutures), originalTotal);
        }

        // Collect completed values as a positional sparse array aligned to input positions.
        // For full waits (minCount == total): all positions are populated (no nil).
        // For partial waits (minCount < total): incomplete positions carry null (→ Ballerina nil).
        // A separate boolean[] tracks completion state so that a future whose payload is
        // genuinely null (Ballerina nil) is not confused with an incomplete position.
        Object[] results = new Object[originalTotal];
        boolean[] completed = new boolean[originalTotal];
        for (int i = 0; i < originalTotal; i++) {
            FutureValue fv = originalFutures[i];
            if (fv.completableFuture.isDone()) {
                try {
                    results[i] = fv.completableFuture.join();
                    completed[i] = true;
                } catch (Exception e) {
                    return ErrorCreator.createError(StringUtils.fromString(
                            "Error retrieving completed future value: " + e.getMessage()));
                }
            }
            // else: results[i] remains null, completed[i] remains false
        }

        // Convert results to the caller's expected type (dependent-typing via typedesc)
        return convertResults(results, completed, typedesc.getDescribingType());
    }

    /**
     * Converts a Ballerina {@code time:Duration} record to milliseconds.
     */
    private static long durationToMillis(BMap<BString, Object> duration) {
        long hours = getLongField(duration, HOURS_KEY);
        long minutes = getLongField(duration, MINUTES_KEY);
        Object secObj = duration.get(SECONDS_KEY);
        long secondsMillis = 0;
        if (secObj instanceof Long secLong) {
            secondsMillis = secLong * 1000L;
        } else if (secObj instanceof Double secDouble) {
            secondsMillis = (long) (secDouble * 1000.0);
        } else if (secObj instanceof io.ballerina.runtime.api.values.BDecimal secDec) {
            secondsMillis = secDec.decimalValue().multiply(
                    java.math.BigDecimal.valueOf(1000)).longValue();
        }
        return (hours * 3600_000L) + (minutes * 60_000L) + secondsMillis;
    }

    private static long getLongField(BMap<BString, Object> map, BString key) {
        Object val = map.get(key);
        if (val instanceof Long l) {
            return l;
        }
        return 0L;
    }

    /**
     * Converts the raw result array to the target type described by {@code targetType}.
     * <p>
     * The {@code completed} array distinguishes incomplete positions from completed futures
     * that carry a null (Ballerina nil) payload.
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
    private static Object convertResults(Object[] results, boolean[] completed, Type targetType) {
        if (targetType instanceof TupleType tupleType) {
            java.util.List<Type> memberTypes = tupleType.getTupleTypes();
            BArray tupleValue = ValueCreator.createTupleValue(tupleType);
            for (int i = 0; i < results.length; i++) {
                Type memberType = i < memberTypes.size() ? memberTypes.get(i) : tupleType.getRestType();
                if (!completed[i]) {
                    // Incomplete future position → nil ()
                    tupleValue.add(i, (Object) null);
                    continue;
                }
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
