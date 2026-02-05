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

import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.ReferenceType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.stdlib.workflow.registry.EventInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Utility class for extracting event information from process function signatures.
 * <p>
 * According to the workflow design, process functions have the following signature:
 * <pre>
 * &#64;workflow:process
 * function processName(workflow:Context ctx, T input,
 *     record { future eventX, future eventY } events) returns R|error { }
 * </pre>
 * <p>
 * The events parameter (third parameter) is a record where each field is of type future.
 * Each field represents an event/signal that the workflow can wait for. This extractor
 * analyzes the function type to extract these event definitions.
 * <p>
 * The extracted events are used at runtime to:
 * <ul>
 *   <li>Register Temporal signal handlers for each event</li>
 *   <li>Create Ballerina future values dynamically when workflow is replayed</li>
 *   <li>Complete futures when corresponding signals are received</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class EventExtractor {

    // Context type name for identifying workflow context parameter
    private static final String CONTEXT_TYPE_NAME = "Context";

    private EventExtractor() {
        // Utility class, prevent instantiation
    }

    /**
     * Extracts event information from a process function pointer.
     * <p>
     * Analyzes the function signature to find the events record parameter
     * and extracts information about each future field.
     *
     * @param processFunction the process function pointer
     * @param processName     the name of the process (for error messages)
     * @return list of EventInfo objects, empty if no events found
     */
    public static List<EventInfo> extractEvents(BFunctionPointer processFunction, String processName) {
        if (processFunction == null) {
            return Collections.emptyList();
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return Collections.emptyList();
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return Collections.emptyList();
        }

        // Find the events parameter (record with future fields)
        // Process signature: (Context? ctx, T input, record { future<...> ... } events?)
        RecordType eventsRecordType = findEventsRecordType(parameters);

        if (eventsRecordType == null) {
            return Collections.emptyList();
        }

        return extractEventsFromRecord(eventsRecordType, processName);
    }

    /**
     * Finds the events record type from the function parameters.
     * <p>
     * The events parameter is identified as a record type where at least one
     * field is of type future&lt;T&gt;.
     *
     * @param parameters the parameters of the function
     * @return the events record type, or null if not found
     */
    private static RecordType findEventsRecordType(Parameter[] parameters) {
        for (Parameter param : parameters) {
            Type paramType = param.type;
            
            // Dereference type references to get the actual type
            Type actualType = dereferenceType(paramType);
            
            // Check if it's a record type
            if (actualType.getTag() == TypeTags.RECORD_TYPE_TAG) {
                RecordType recordType = (RecordType) actualType;
                
                // Check if this record contains future fields (events record signature)
                if (containsFutureFields(recordType)) {
                    return recordType;
                }
            }
        }
        return null;
    }
    
    /**
     * Dereferences a type if it's a ReferenceType.
     * This handles named types like "type MyRecord record {...}".
     *
     * @param type the type to dereference
     * @return the referred type if it's a ReferenceType, otherwise the original type
     */
    private static Type dereferenceType(Type type) {
        // Keep track of max depth to prevent infinite loops with self-referencing types
        int maxDepth = 10;
        Type current = type;
        
        while (current instanceof ReferenceType refType && maxDepth > 0) {
            Type referredType = refType.getReferredType();
            
            // If the referred type is the same object, stop to prevent infinite loop
            if (referredType == current) {
                break;
            }
            
            current = referredType;
            maxDepth--;
        }
        
        return current;
    }
    /**
     * Checks if a record type contains at least one future field.
     *
     * @param recordType the record type to check
     * @return true if the record contains future fields
     */
    private static boolean containsFutureFields(RecordType recordType) {
        Map<String, Field> fields = recordType.getFields();
        if (fields == null || fields.isEmpty()) {
            return false;
        }

        for (Field field : fields.values()) {
            Type fieldType = field.getFieldType();
            // Dereference in case it's a type reference
            Type actualFieldType = dereferenceType(fieldType);
            if (actualFieldType.getTag() == TypeTags.FUTURE_TAG) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts event information from all future fields in a record type.
     *
     * @param recordType  the events record type
     * @param processName the name of the process
     * @return list of EventInfo objects
     */
    private static List<EventInfo> extractEventsFromRecord(RecordType recordType, String processName) {
        List<EventInfo> events = new ArrayList<>();
        Map<String, Field> fields = recordType.getFields();

        if (fields == null || fields.isEmpty()) {
            return events;
        }

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Field field = entry.getValue();
            Type fieldType = field.getFieldType();
            
            // Dereference in case it's a type reference
            Type actualFieldType = dereferenceType(fieldType);

            // Only process future fields
            if (actualFieldType.getTag() == TypeTags.FUTURE_TAG) {
                // FutureType doesn't expose the constraint type directly through the API,
                // so we store the futureType itself and can introspect it if needed
                EventInfo eventInfo = new EventInfo(fieldName, actualFieldType, processName);
                events.add(eventInfo);
            }
        }
        
        return events;
    }

    /**
     * Extracts event names only (without full type information).
     * <p>
     * This is useful when only the signal names are needed for
     * registering Temporal signal handlers.
     *
     * @param processFunction the process function pointer
     * @return list of event field names, empty if no events found
     */
    public static List<String> extractEventNames(BFunctionPointer processFunction) {
        if (processFunction == null) {
            return Collections.emptyList();
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return Collections.emptyList();
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return Collections.emptyList();
        }

        RecordType eventsRecordType = findEventsRecordType(parameters);
        if (eventsRecordType == null) {
            return Collections.emptyList();
        }

        return extractEventNamesFromRecord(eventsRecordType);
    }

    /**
     * Extracts just the event field names from a record type.
     *
     * @param recordType the events record type
     * @return list of field names that are future types
     */
    private static List<String> extractEventNamesFromRecord(RecordType recordType) {
        List<String> eventNames = new ArrayList<>();
        Map<String, Field> fields = recordType.getFields();

        if (fields == null || fields.isEmpty()) {
            return eventNames;
        }

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            Field field = entry.getValue();
            if (field.getFieldType().getTag() == TypeTags.FUTURE_TAG) {
                eventNames.add(entry.getKey());
            }
        }

        return eventNames;
    }

    /**
     * Checks if a process function has any events defined.
     *
     * @param processFunction the process function pointer
     * @return true if the function has an events parameter with future fields
     */
    public static boolean hasEvents(BFunctionPointer processFunction) {
        if (processFunction == null) {
            return false;
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return false;
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return false;
        }

        return findEventsRecordType(parameters) != null;
    }

    /**
     * Gets the events record type from a process function signature.
     * <p>
     * The events record type is identified as a record containing future fields.
     * This is used to create TemporalFutureValue instances for each event.
     *
     * @param processFunction the process function pointer
     * @return the events RecordType, or null if no events parameter found
     */
    public static RecordType getEventsRecordType(BFunctionPointer processFunction) {
        if (processFunction == null) {
            return null;
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return null;
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return null;
        }

        return findEventsRecordType(parameters);
    }

    /**
     * Gets the input parameter record type from a process function.
     * <p>
     * This is used to extract correlation keys (readonly fields) from the input type.
     *
     * @param processFunction the process function pointer
     * @return the input RecordType, or null if input is not a record
     */
    public static RecordType getInputRecordType(BFunctionPointer processFunction) {
        Type inputType = getInputType(processFunction);
        if (inputType == null) {
            return null;
        }

        Type actualType = dereferenceType(inputType);
        if (actualType.getTag() == TypeTags.RECORD_TYPE_TAG) {
            return (RecordType) actualType;
        }

        return null;
    }

    /**
     * Gets the signal record type for a specific signal name from a process function.
     * <p>
     * This is used to extract correlation keys from signal data when sending events.
     *
     * @param processFunction the process function pointer
     * @param signalName the name of the signal field in the events record
     * @return the signal RecordType, or null if not found or not a record
     */
    public static RecordType getSignalRecordType(BFunctionPointer processFunction, String signalName) {
        RecordType eventsRecordType = getEventsRecordType(processFunction);
        if (eventsRecordType == null || signalName == null) {
            return null;
        }

        Map<String, Field> fields = eventsRecordType.getFields();
        if (fields == null || !fields.containsKey(signalName)) {
            return null;
        }

        Field signalField = fields.get(signalName);
        Type fieldType = signalField.getFieldType();
        Type actualFieldType = dereferenceType(fieldType);

        // Signal field should be future<T>
        if (actualFieldType.getTag() != TypeTags.FUTURE_TAG) {
            return null;
        }

        // Get the constraint type from the future
        Type constraintType = getConstraintType(actualFieldType);
        if (constraintType == null) {
            return null;
        }

        Type actualConstraintType = dereferenceType(constraintType);
        if (actualConstraintType.getTag() == TypeTags.RECORD_TYPE_TAG) {
            return (RecordType) actualConstraintType;
        }

        return null;
    }

    /**
     * Gets information about the input parameter type of a process function.
     *
     * @param processFunction the process function pointer
     * @return the input type, or null if not determinable
     */
    public static Type getInputType(BFunctionPointer processFunction) {
        if (processFunction == null) {
            return null;
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return null;
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return null;
        }

        // The input parameter is:
        // - First param if no Context
        // - Second param if Context is present
        int inputIndex = 0;
        if (isContextType(parameters[0].type)) {
            inputIndex = 1;
        }

        if (inputIndex < parameters.length) {
            Type potentialInput = parameters[inputIndex].type;
            // Make sure it's not the events record
            if (potentialInput.getTag() != TypeTags.RECORD_TYPE_TAG || 
                !containsFutureFields((RecordType) potentialInput)) {
                return potentialInput;
            }
        }

        return null;
    }

    /**
     * Checks if a type is the workflow Context type.
     *
     * @param type the type to check
     * @return true if it's the Context type
     */
    public static boolean isContextType(Type type) {
        if (type == null) {
            return false;
        }
        // Check by type name - Context is a record type from workflow module
        String typeName = type.getName();
        return CONTEXT_TYPE_NAME.equals(typeName);
    }

    /**
     * Checks if a process function has a Context parameter as its first parameter.
     *
     * @param processFunction the process function pointer
     * @return true if the first parameter is Context
     */
    public static boolean hasContextParameter(BFunctionPointer processFunction) {
        if (processFunction == null) {
            return false;
        }

        Type funcType = processFunction.getType();
        if (!(funcType instanceof FunctionType functionType)) {
            return false;
        }

        Parameter[] parameters = functionType.getParameters();

        if (parameters == null || parameters.length == 0) {
            return false;
        }

        return isContextType(parameters[0].type);
    }

    /**
     * Infers the signal name from event data by matching its structure against the events record type.
     * <p>
     * This method attempts to find a matching signal by comparing the set of keys in the event data
     * with the expected structure of each signal type in the events record.
     * <p>
     * If multiple signals match the event data structure (ambiguous), returns null.
     * If no signals match, returns null.
     * If exactly one signal matches, returns the signal name.
     *
     * @param processFunction the process function pointer with events definition
     * @param eventDataKeys the set of keys in the event data
     * @return the matching signal name, or null if ambiguous or no match
     */
    public static String inferSignalName(BFunctionPointer processFunction, java.util.Set<String> eventDataKeys) {
        if (processFunction == null || eventDataKeys == null || eventDataKeys.isEmpty()) {
            return null;
        }

        RecordType eventsRecordType = getEventsRecordType(processFunction);
        if (eventsRecordType == null) {
            return null;
        }

        Map<String, Field> fields = eventsRecordType.getFields();
        if (fields == null || fields.isEmpty()) {
            return null;
        }

        List<String> matchingSignals = new ArrayList<>();

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String signalName = entry.getKey();
            Field field = entry.getValue();
            Type fieldType = field.getFieldType();
            
            // Dereference the type to get the actual type
            Type actualFieldType = dereferenceType(fieldType);
            
            if (actualFieldType.getTag() != TypeTags.FUTURE_TAG) {
                continue;
            }
            
            // Get the constraint type from the future
            Type constraintType = getConstraintType(actualFieldType);
            if (constraintType == null) {
                // No constraint type, match any event data
                matchingSignals.add(signalName);
                continue;
            }
            
            // Check if the event data keys match the constraint type
            if (matchesStructure(constraintType, eventDataKeys)) {
                matchingSignals.add(signalName);
            }
        }

        // Return the signal name only if exactly one match
        if (matchingSignals.size() == 1) {
            return matchingSignals.getFirst();
        }

        return null;
    }

    /**
     * Gets a list of all signal names that match the given event data structure.
     * Used for generating detailed error messages when ambiguous.
     *
     * @param processFunction the process function pointer with events definition
     * @param eventDataKeys the set of keys in the event data
     * @return list of matching signal names (may be empty or have multiple entries)
     */
    public static List<String> getMatchingSignals(BFunctionPointer processFunction, 
            java.util.Set<String> eventDataKeys) {
        List<String> matchingSignals = new ArrayList<>();
        
        if (processFunction == null || eventDataKeys == null || eventDataKeys.isEmpty()) {
            return matchingSignals;
        }

        RecordType eventsRecordType = getEventsRecordType(processFunction);
        if (eventsRecordType == null) {
            return matchingSignals;
        }

        Map<String, Field> fields = eventsRecordType.getFields();
        if (fields == null || fields.isEmpty()) {
            return matchingSignals;
        }

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String signalName = entry.getKey();
            Field field = entry.getValue();
            Type fieldType = field.getFieldType();
            
            Type actualFieldType = dereferenceType(fieldType);
            
            if (actualFieldType.getTag() != TypeTags.FUTURE_TAG) {
                continue;
            }
            
            Type constraintType = getConstraintType(actualFieldType);
            if (constraintType == null || matchesStructure(constraintType, eventDataKeys)) {
                matchingSignals.add(signalName);
            }
        }

        return matchingSignals;
    }

    /**
     * Gets the constraint type from a future type.
     *
     * @param futureType the future type
     * @return the constraint type, or null if not available
     */
    private static Type getConstraintType(Type futureType) {
        if (futureType == null || futureType.getTag() != TypeTags.FUTURE_TAG) {
            return null;
        }
        
        // FutureType in io.ballerina.runtime.api.types doesn't directly expose constraint
        // We need to use the type name or reflection to get it
        // For now, check if it's a ParameterizedType
        if (futureType instanceof io.ballerina.runtime.internal.types.BFutureType bFutureType) {
            return bFutureType.getConstrainedType();
        }
        
        return null;
    }

    /**
     * Checks if event data keys match the expected structure of a constraint type.
     *
     * @param constraintType the expected type structure
     * @param eventDataKeys the keys present in the event data
     * @return true if the structure matches
     */
    private static boolean matchesStructure(Type constraintType, java.util.Set<String> eventDataKeys) {
        Type actualType = dereferenceType(constraintType);
        
        // For record types, compare field names
        if (actualType.getTag() == TypeTags.RECORD_TYPE_TAG) {
            RecordType recordType = (RecordType) actualType;
            Map<String, Field> fields = recordType.getFields();
            
            if (fields == null || fields.isEmpty()) {
                // Empty record matches empty event data
                return eventDataKeys.isEmpty();
            }
            
            // Get required fields (non-optional fields without defaults)
            java.util.Set<String> allFieldNames = new java.util.HashSet<>();
            
            for (Map.Entry<String, Field> entry : fields.entrySet()) {
                allFieldNames.add(entry.getKey());
                // Consider all fields as potentially required for matching
                // (simpler approach - matching is based on key overlap)
            }
            
            // Match if all event data keys are present in the record's fields
            // and all required fields are present in event data
            for (String key : eventDataKeys) {
                if (!allFieldNames.contains(key) && !"id".equals(key)) {
                    // Event has a key not in the record (ignore "id" as it's for correlation)
                    return false;
                }
            }
            
            return true;
        }
        
        // For map types, accept any keys
        if (actualType.getTag() == TypeTags.MAP_TAG) {
            return true;
        }
        
        // For other types (primitives, etc.), event data should have a single value
        return eventDataKeys.size() <= 1 || (eventDataKeys.size() == 2 && eventDataKeys.contains("id"));
    }
}
