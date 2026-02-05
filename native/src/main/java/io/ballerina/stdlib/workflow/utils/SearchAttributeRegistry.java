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
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.grpc.ManagedChannel;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages registration of Temporal Search Attributes for correlation keys.
 * <p>
 * Search attributes must be registered with the Temporal server before they can be used.
 * This class handles the registration automatically when processes are registered,
 * ensuring that correlation keys can be used for workflow visibility and querying.
 * <p>
 * Thread-safe: Uses a concurrent set to track registered attributes and avoid
 * duplicate registration attempts.
 *
 * @since 0.1.0
 */
public final class SearchAttributeRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAttributeRegistry.class);

    /**
     * Set of search attributes that have been successfully registered.
     * Used to avoid duplicate registration attempts.
     */
    private static final Set<String> REGISTERED_ATTRIBUTES = ConcurrentHashMap.newKeySet();

    /**
     * The gRPC channel for communicating with Temporal operator service.
     */
    private static volatile ManagedChannel grpcChannel;

    /**
     * The namespace for registering search attributes.
     */
    private static volatile String namespace;

    private SearchAttributeRegistry() {
        // Utility class, prevent instantiation
    }

    /**
     * Initializes the registry with the workflow service stubs and namespace.
     * Must be called before registering any search attributes.
     *
     * @param workflowStubs     the workflow service stubs (used to get gRPC channel)
     * @param ns                the Temporal namespace
     * @param serverUrl         the Temporal server URL (unused, kept for signature compatibility)
     */
    public static void initialize(WorkflowServiceStubs workflowStubs, String ns, String serverUrl) {
        // Use the raw gRPC channel from workflow stubs to create operator service stub directly
        // This avoids metrics scope issues with creating a separate OperatorServiceStubs
        grpcChannel = workflowStubs.getRawChannel();
        namespace = ns;
        LOGGER.debug("SearchAttributeRegistry initialized for namespace: {}", ns);
    }

    /**
     * Registers search attributes for all readonly fields in a record type.
     * <p>
     * This method extracts readonly fields from the input record type and
     * registers them as search attributes with the Temporal server.
     * Already registered attributes are skipped.
     *
     * @param inputRecordType the input record type containing correlation key definitions
     * @param processName     the process name for logging context
     */
    public static void registerFromRecordType(RecordType inputRecordType, String processName) {
        if (inputRecordType == null) {
            LOGGER.debug("No input record type for process {}, skipping search attribute registration", processName);
            return;
        }

        if (grpcChannel == null) {
            LOGGER.warn("SearchAttributeRegistry not initialized, skipping registration for process {}", processName);
            return;
        }

        for (Map.Entry<String, Field> entry : inputRecordType.getFields().entrySet()) {
            Field field = entry.getValue();
            String fieldName = entry.getKey();

            // Only register readonly fields as search attributes
            if (SymbolFlags.isFlagOn(field.getFlags(), SymbolFlags.READONLY)) {
                registerSearchAttribute(fieldName, field.getFieldType(), processName);
            }
        }
    }

    /**
     * Registers a single search attribute with the Temporal server.
     *
     * @param attributeName the name of the search attribute (field name from Ballerina record)
     * @param ballerinaType the Ballerina type of the field
     * @param processName   the process name for logging context
     */
    public static void registerSearchAttribute(String attributeName, Type ballerinaType, String processName) {
        // Convert to Correlation + Capitalized for Temporal convention
        // e.g., id -> CorrelationId, orderId -> CorrelationOrderId
        // This matches the naming used in BallerinaWorkflowAdapter.upsertCorrelationSearchAttributes()
        String temporalAttributeName = "Correlation" + CorrelationExtractor.capitalize(attributeName);

        // Check if already registered
        if (REGISTERED_ATTRIBUTES.contains(temporalAttributeName)) {
            LOGGER.debug("Search attribute {} already registered, skipping", temporalAttributeName);
            return;
        }

        // Determine the Temporal indexed value type based on Ballerina type
        IndexedValueType indexedType = mapToTemporalType(ballerinaType);
        if (indexedType == null) {
            LOGGER.warn("Unsupported type {} for search attribute {} in process {}, skipping",
                    ballerinaType.getName(), attributeName, processName);
            return;
        }

        try {
            // Create operator stub directly from gRPC channel
            OperatorServiceGrpc.OperatorServiceBlockingStub operatorStub =
                    OperatorServiceGrpc.newBlockingStub(grpcChannel);

            AddSearchAttributesRequest request = AddSearchAttributesRequest.newBuilder()
                    .setNamespace(namespace)
                    .putSearchAttributes(temporalAttributeName, indexedType)
                    .build();

            operatorStub.addSearchAttributes(request);

            REGISTERED_ATTRIBUTES.add(temporalAttributeName);
            LOGGER.debug("Registered search attribute '{}' (type: {}) for process {}",
                    temporalAttributeName, indexedType, processName);

        } catch (io.grpc.StatusRuntimeException e) {
            // Check if the attribute already exists (ALREADY_EXISTS status)
            if (e.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                REGISTERED_ATTRIBUTES.add(temporalAttributeName);
                LOGGER.debug("Search attribute {} already exists on server", temporalAttributeName);
            } else {
                LOGGER.warn("Failed to register search attribute {} for process {}: {}",
                        temporalAttributeName, processName, e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to register search attribute {} for process {}: {}",
                    temporalAttributeName, processName, e.getMessage());
        }
    }

    /**
     * Maps a Ballerina type to a Temporal indexed value type.
     *
     * @param type the Ballerina type
     * @return the corresponding Temporal IndexedValueType, or null if unsupported
     */
    private static IndexedValueType mapToTemporalType(Type type) {
        int tag = type.getTag();

        return switch (tag) {
            case TypeTags.STRING_TAG, TypeTags.CHAR_STRING_TAG -> IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD;

            case TypeTags.INT_TAG, TypeTags.BYTE_TAG, TypeTags.SIGNED8_INT_TAG, TypeTags.SIGNED16_INT_TAG,
                 TypeTags.SIGNED32_INT_TAG, TypeTags.UNSIGNED8_INT_TAG, TypeTags.UNSIGNED16_INT_TAG,
                 TypeTags.UNSIGNED32_INT_TAG -> IndexedValueType.INDEXED_VALUE_TYPE_INT;

            case TypeTags.FLOAT_TAG, TypeTags.DECIMAL_TAG -> IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE;

            case TypeTags.BOOLEAN_TAG -> IndexedValueType.INDEXED_VALUE_TYPE_BOOL;

            // For complex types, use keyword (will be serialized as string)
            default -> IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD;
        };
    }

    /**
     * Checks if a search attribute has been registered.
     *
     * @param fieldName the field name from the Ballerina record
     * @return true if registered
     */
    public static boolean isRegistered(String fieldName) {
        String temporalName = "Correlation" + CorrelationExtractor.capitalize(fieldName);
        return REGISTERED_ATTRIBUTES.contains(temporalName);
    }

    /**
     * Clears the registry. Used for testing.
     */
    public static void clear() {
        REGISTERED_ATTRIBUTES.clear();
    }

    /**
     * Gets the Temporal-formatted attribute name (Correlation + Capitalized).
     *
     * @param fieldName the field name
     * @return the Temporal attribute name (e.g., id -> CorrelationId)
     */
    public static String getTemporalAttributeName(String fieldName) {
        return "Correlation" + CorrelationExtractor.capitalize(fieldName);
    }
}
