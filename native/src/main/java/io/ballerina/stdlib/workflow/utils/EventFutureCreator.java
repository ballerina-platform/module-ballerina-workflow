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

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.FutureType;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.internal.scheduling.Scheduler;
import io.ballerina.runtime.internal.types.BFutureType;
import io.ballerina.stdlib.workflow.context.SignalAwaitWrapper;
import io.ballerina.stdlib.workflow.context.TemporalFutureValue;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Utility class for creating TemporalFutureValue objects for workflow events.
 * <p>
 * This class creates TemporalFutureValue instances that extend Ballerina's FutureValue
 * and can be used with the standard {@code wait} action. Each TemporalFutureValue wraps a 
 * Temporal CompletablePromise for cooperative blocking that is compatible with Temporal's 
 * deterministic replay.
 *
 * @since 0.1.0
 */
public final class EventFutureCreator {

    private static final Logger LOGGER = Workflow.getLogger(EventFutureCreator.class);

    private EventFutureCreator() {
        // Utility class
    }

    /**
     * Creates a record containing TemporalFutureValue objects for each event field.
     * <p>
     * The returned record can be passed as the events parameter to a workflow function.
     * Each future can be awaited using Ballerina's standard {@code wait} action.
     *
     * @param eventsRecordType the record type describing the events (with future fields)
     * @param signalWrapper the SignalAwaitWrapper for creating and managing promises
     * @param scheduler the Ballerina scheduler for creating strands (can be null)
     * @return a BMap representing the events record with future fields
     */
    public static BMap<BString, Object> createEventsRecord(
            RecordType eventsRecordType,
            SignalAwaitWrapper signalWrapper,
            Scheduler scheduler) {

        // Create a mutable map to hold the event futures
        Map<String, Field> fields = eventsRecordType.getFields();
        BMap<BString, Object> eventsRecord = ValueCreator.createRecordValue(eventsRecordType);

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Field field = entry.getValue();
            Type fieldType = field.getFieldType();

            // Extract the constraint type from future<T>
            Type constraintType = extractConstraintType(fieldType);

            // Create a TemporalFutureValue for this signal
            TemporalFutureValue futureValue = createTemporalFutureValue(
                    fieldName, signalWrapper, constraintType, scheduler);

            // Add to the events record
            eventsRecord.put(StringUtils.fromString(fieldName), futureValue);

            LOGGER.debug("[EventFutureCreator] Created TemporalFutureValue for event: {} (constraint type: {})",
                    fieldName, constraintType != null ? constraintType.getName() : "anydata");
        }

        return eventsRecord;
    }

    /**
     * Extracts the constraint type from a future type.
     *
     * @param fieldType the field type (should be a future type)
     * @return the constraint type, or null if not a future type
     */
    private static Type extractConstraintType(Type fieldType) {
        if (fieldType instanceof BFutureType) {
            return ((BFutureType) fieldType).getConstrainedType();
        } else if (fieldType instanceof FutureType) {
            // Try to get constraint type via reflection if it's the interface
            // This shouldn't happen as BFutureType is the concrete implementation
            LOGGER.warn("[EventFutureCreator] FutureType interface without BFutureType, returning null constraint");
            return null;
        }
        // Not a future type - log warning and return null
        LOGGER.warn("[EventFutureCreator] Field type {} is not a future type", fieldType.getName());
        return null;
    }

    /**
     * Creates a TemporalFutureValue for the given signal name.
     * <p>
     * The TemporalFutureValue extends FutureValue and wraps a Temporal CompletablePromise 
     * that will be completed when the corresponding signal is received.
     *
     * @param signalName the name of the signal/event
     * @param signalWrapper the SignalAwaitWrapper containing the promise
     * @param constraintType the expected Ballerina type for the signal data
     * @param scheduler the Ballerina scheduler for creating strands (can be null)
     * @return a TemporalFutureValue
     */
    private static TemporalFutureValue createTemporalFutureValue(
            String signalName,
            SignalAwaitWrapper signalWrapper,
            Type constraintType,
            Scheduler scheduler) {

        // Get or create the promise for this signal
        CompletablePromise<SignalAwaitWrapper.SignalData> promise = signalWrapper.getSignalFuture(signalName);

        // Create the TemporalFutureValue that extends FutureValue
        return new TemporalFutureValue(promise, signalName, constraintType, scheduler);
    }
}
