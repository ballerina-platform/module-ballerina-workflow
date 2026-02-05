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

package io.ballerina.stdlib.workflow.runtime;

import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.workflow.utils.CorrelationExtractor;
import io.ballerina.stdlib.workflow.utils.EventExtractor;
import io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workflow Runtime for managing workflow processes and activities.
 * <p>
 * This class serves as the central runtime for the workflow module,
 * coordinating the execution of workflow processes and activities.
 * It delegates to the singleton WorkflowWorkerNative for Temporal integration.
 *
 * @since 0.1.0
 */
public final class WorkflowRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRuntime.class);

    // Singleton instance
    private static final WorkflowRuntime INSTANCE = new WorkflowRuntime();

    // Executor service for async operations
    private final ExecutorService executor;

    // Flag to track if runtime is initialized
    private volatile boolean initialized;

    // Retry configuration for visibility queries (eventual consistency)
    private static final int VISIBILITY_MAX_RETRIES = 10;
    private static final long VISIBILITY_RETRY_DELAY_MS = 200;

    // Error type for duplicate workflow detection
    private static final String DUPLICATE_WORKFLOW_ERROR = "DuplicateWorkflowError";

    private WorkflowRuntime() {
        // Use virtual threads for efficient concurrency (Java 21+)
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.initialized = false;
    }

    /**
     * Gets the singleton instance of the WorkflowRuntime.
     *
     * @return the WorkflowRuntime instance
     */
    public static WorkflowRuntime getInstance() {
        return INSTANCE;
    }

    /**
     * Gets the executor service for async operations.
     *
     * @return the ExecutorService
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Initializes the workflow runtime.
     * <p>
     * The actual Temporal initialization is done through WorkflowWorkerNative.initSingletonWorker().
     * This method just marks the runtime as initialized.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        // The actual Temporal client and workers are initialized through
        // WorkflowWorkerNative.initSingletonWorker() which is called from Ballerina init
        initialized = true;
        LOGGER.debug("WorkflowRuntime initialized");
    }

    /**
     * Checks if the runtime is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Starts a new workflow process.
     * <p>
     * The workflow ID is generated using UUID v7 for uniqueness and sortability.
     * If readonly fields are defined, they are stored as Temporal search attributes
     * for correlation-based signal routing.
     * <p>
     * <b>Validation:</b> If the process expects events (signals) but no readonly
     * fields are defined, an error is thrown since signals cannot be correlated.
     *
     * @param processName the name of the process to start
     * @param input the input data for the process
     * @return the workflow ID
     * @throws RuntimeException if the process is not registered, or if events are 
     *                          expected but no correlation keys (readonly fields) are defined
     */
    @SuppressWarnings("unchecked")
    public String createInstance(String processName, Object input) {
        // Verify the process is registered in WorkflowWorkerNative
        if (!WorkflowWorkerNative.getProcessRegistry().containsKey(processName)) {
            throw new RuntimeException("Process not registered: " + processName);
        }

        // Get the process function to extract input type for correlation
        BFunctionPointer processFunction = WorkflowWorkerNative.getProcessRegistry().get(processName);
        RecordType inputRecordType = processFunction != null ? 
                EventExtractor.getInputRecordType(processFunction) : null;

        // Check if process expects events (has events record parameter)
        RecordType eventsRecordType = processFunction != null ?
                EventExtractor.getEventsRecordType(processFunction) : null;
        boolean hasEvents = eventsRecordType != null && !eventsRecordType.getFields().isEmpty();

        // Extract correlation keys from input (only readonly fields)
        Map<String, Object> correlationKeys;
        if (input instanceof Map) {
            correlationKeys = CorrelationExtractor.extractCorrelationKeysFromMap(
                    input, inputRecordType);
        } else if (input instanceof BMap) {
            @SuppressWarnings("unchecked")
            BMap<BString, Object> bMapInput = (BMap<BString, Object>) input;
            correlationKeys = CorrelationExtractor.extractCorrelationKeys(bMapInput, inputRecordType);
        } else {
            throw new RuntimeException("Input must be a Map or BMap type");
        }

        // Validation: If process expects events but no correlation keys are defined,
        // signals cannot be routed to this workflow
        if (hasEvents && correlationKeys.isEmpty()) {
            throw new RuntimeException(
                "Process '" + processName + "' expects events but input has no readonly fields. " +
                "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')");
        }

        // Check for duplicate workflow with same correlation keys (if any exist)
        // Use single attempt (no retry) since we're just checking for existing workflow
        if (!correlationKeys.isEmpty()) {
            String existingWorkflowId = findWorkflowByCorrelationKeys(processName, correlationKeys, true, false);
            if (existingWorkflowId != null) {
                throw new DuplicateWorkflowException(
                    "A running workflow already exists for process '" + processName + 
                    "' with the same correlation keys. Existing workflow ID: " + existingWorkflowId,
                    existingWorkflowId, processName, correlationKeys);
            }
        }

        // Generate workflow ID using UUID v7
        String workflowId = CorrelationExtractor.generateWorkflowId(processName);

        // Get the singleton workflow client from WorkflowWorkerNative
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            throw new RuntimeException("Workflow client not initialized. Ensure worker is initialized.");
        }

        String taskQueue = WorkflowWorkerNative.getTaskQueue();
        if (taskQueue == null) {
            throw new RuntimeException("Task queue not configured.");
        }

        try {
            // Build workflow options with search attributes
            WorkflowOptions.Builder optionsBuilder = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue);

            // Add search attributes for correlation keys (if any readonly fields exist)
            if (!correlationKeys.isEmpty()) {
                Map<String, Object> searchAttrs = CorrelationExtractor.toSearchAttributes(correlationKeys);
                SearchAttributes typedSearchAttrs = buildTypedSearchAttributes(searchAttrs);
                if (typedSearchAttrs != null) {
                    optionsBuilder.setTypedSearchAttributes(typedSearchAttrs);
                }
            }

            WorkflowOptions options = optionsBuilder.build();

            // Create an untyped workflow stub for dynamic workflow execution
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(processName, options);

            // Start the workflow asynchronously with the input data
            workflowStub.start(input);

            LOGGER.debug("Started workflow: type={}, id={}, correlationKeys={}", 
                    processName, workflowId, correlationKeys.keySet());
            return workflowId;

        } catch (Exception e) {
            LOGGER.error("Failed to start workflow {}: {}", processName, e.getMessage(), e);
            throw new RuntimeException("Failed to start workflow: " + e.getMessage(), e);
        }
    }

    /**
     * Builds TypedSearchAttributes from correlation key map.
     *
     * @param attributes the correlation keys as search attributes
     * @return SearchAttributes or null if empty
     */
    private SearchAttributes buildTypedSearchAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        SearchAttributes.Builder builder = SearchAttributes.newBuilder();

        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                builder.set(SearchAttributeKey.forKeyword(key), (String) value);
            } else if (value instanceof Long) {
                builder.set(SearchAttributeKey.forLong(key), (Long) value);
            } else if (value instanceof Integer) {
                builder.set(SearchAttributeKey.forLong(key), ((Integer) value).longValue());
            } else if (value instanceof Boolean) {
                builder.set(SearchAttributeKey.forBoolean(key), (Boolean) value);
            } else if (value instanceof Double) {
                builder.set(SearchAttributeKey.forDouble(key), (Double) value);
            } else if (value instanceof Float) {
                builder.set(SearchAttributeKey.forDouble(key), ((Float) value).doubleValue());
            } else if (value != null) {
                // Default to keyword (string) type
                builder.set(SearchAttributeKey.forKeyword(key), value.toString());
            }
        }

        return builder.build();
    }

    /**
     * Executes an activity within the current workflow context.
     * Note: Activity execution is handled by the Temporal SDK through WorkflowWorkerNative.
     * This method is kept for compatibility but actual activity execution goes through
     * the dynamic activity adapter in WorkflowWorkerNative.
     *
     * @param activityName the name of the activity to execute
     * @param args the arguments to pass to the activity
     * @return the result of the activity
     * @throws RuntimeException if the activity is not registered
     */
    public Object executeActivity(String activityName, Object[] args) {
        // Verify the activity is registered in WorkflowWorkerNative
        if (!WorkflowWorkerNative.getActivityRegistry().containsKey(activityName)) {
            throw new RuntimeException("Activity not registered: " + activityName);
        }

        // Activity execution is handled by Temporal through the BallerinaActivityAdapter
        // in WorkflowWorkerNative. This method should not be called directly.
        // The module-level callActivity() function in Ballerina uses WorkflowNative.callActivity()
        // which delegates to Temporal's activity stub.
        throw new RuntimeException(
                "Direct activity execution not supported. Use module-level callActivity() function.");
    }

    /**
     * Sends an event (signal) to a running workflow.
     * The target workflow ID is extracted from the "id" field in the event data.
     *
     * @param processName the name of the process (workflow type)
     * @param eventData the event data to send (must be a Map with "id" field)
     * @return true if the event was sent successfully
     * @throws RuntimeException if the id field is missing
     */
    public boolean sendEvent(String processName, Object eventData) {
        return sendEvent(processName, eventData, null);
    }

    /**
     * Sends an event (signal) to a running workflow with a specific signal name.
     * <p>
     * The target workflow is found using Temporal Visibility API by querying
     * search attributes that match the correlation keys (readonly fields) in the event data.
     *
     * @param processName the name of the process (workflow type)
     * @param eventData the event data to send (must have readonly correlation keys)
     * @param signalName the name of the signal to send (if null, uses processName)
     * @return true if the event was sent successfully
     * @throws RuntimeException if no correlation keys are found or no matching workflow exists
     */
    @SuppressWarnings("unchecked")
    public boolean sendEvent(String processName, Object eventData, String signalName) {
        // Get the process function to extract signal type for correlation
        BFunctionPointer processFunction = WorkflowWorkerNative.getProcessRegistry().get(processName);
        RecordType signalRecordType = null;
        
        // Try to get the signal type from the process function's events record
        if (processFunction != null && signalName != null) {
            signalRecordType = EventExtractor.getSignalRecordType(processFunction, signalName);
        }

        // Extract correlation keys from event data (only readonly fields)
        Map<String, Object> correlationKeys;
        if (eventData instanceof Map) {
            correlationKeys = CorrelationExtractor.extractCorrelationKeysFromMap(
                    eventData, signalRecordType);
        } else if (eventData instanceof BMap) {
            @SuppressWarnings("unchecked")
            BMap<BString, Object> bMapEventData = (BMap<BString, Object>) eventData;
            correlationKeys = CorrelationExtractor.extractCorrelationKeys(
                    bMapEventData, signalRecordType);
        } else {
            throw new RuntimeException("Event data must be a Map or BMap type");
        }

        if (correlationKeys.isEmpty()) {
            throw new RuntimeException(
                "Event data must contain readonly correlation keys. " +
                "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')");
        }

        // Use Visibility API to find workflow by search attributes
        String workflowId = findWorkflowByCorrelationKeys(processName, correlationKeys);
        if (workflowId == null) {
            throw new RuntimeException(
                "No running workflow found for process " + processName + 
                " with correlation keys: " + correlationKeys);
        }

        // Get the singleton workflow client from WorkflowWorkerNative
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            throw new RuntimeException("Workflow client not initialized. Ensure worker is initialized.");
        }

        // Determine the actual signal name to use
        String actualSignalName = (signalName != null && !signalName.isEmpty()) ? signalName : processName;

        try {
            // Create an untyped workflow stub for the existing workflow
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(workflowId);

            // Send the signal to the workflow
            workflowStub.signal(actualSignalName, eventData);

            LOGGER.debug("Sent signal to workflow: id={}, signalName={}, correlationKeys={}", 
                    workflowId, actualSignalName, correlationKeys.keySet());
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to send signal to workflow {}: {}", workflowId, e.getMessage(), e);
            throw new RuntimeException("Failed to send signal: " + e.getMessage(), e);
        }
    }

    /**
     * Finds a workflow by correlation keys using Temporal Visibility API.
     * Uses retry logic for eventual consistency when waiting for a workflow to appear.
     *
     * @param processName the workflow type name
     * @param correlationKeys the correlation key values
     * @return the workflow ID if found, null otherwise
     */
    private String findWorkflowByCorrelationKeys(String processName, Map<String, Object> correlationKeys) {
        return findWorkflowByCorrelationKeys(processName, correlationKeys, true, true);
    }

    /**
     * Finds a workflow by correlation keys using Temporal Visibility API.
     * <p>
     * This method can be used when the composite ID pattern is insufficient
     * and a visibility query is needed.
     *
     * @param processName the workflow type name
     * @param correlationKeys the correlation key values
     * @param runningOnly if true, only search for running workflows; if false, search all statuses
     * @param withRetry if true, retry with backoff for eventual consistency; if false, single attempt
     * @return the workflow ID if found, null otherwise
     */
    private String findWorkflowByCorrelationKeys(String processName, Map<String, Object> correlationKeys, 
            boolean runningOnly, boolean withRetry) {
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            return null;
        }

        // Build visibility query using search attribute naming convention
        // Search attributes are named: Correlation + capitalize(fieldName)
        StringBuilder query = new StringBuilder();
        query.append("WorkflowType = '").append(processName).append("'");
        if (runningOnly) {
            query.append(" AND ExecutionStatus = 'Running'");
        }

        for (Map.Entry<String, Object> entry : correlationKeys.entrySet()) {
            String attrName = "Correlation" + CorrelationExtractor.capitalize(entry.getKey());
            Object value = entry.getValue();

            query.append(" AND ").append(attrName).append(" = ");
            if (value instanceof String) {
                query.append("'").append(value).append("'");
            } else {
                query.append(value);
            }
        }

        String queryStr = query.toString();
        LOGGER.debug("Searching for workflow with query: {}", queryStr);

        int maxAttempts = withRetry ? VISIBILITY_MAX_RETRIES : 1;
        int attempt;
        
        // Retry with exponential backoff to handle Temporal visibility eventual consistency
        for (attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace())
                        .setQuery(queryStr)
                        .setPageSize(1)
                        .build();

                ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .listWorkflowExecutions(request);

                if (response.getExecutionsCount() > 0) {
                    String foundId = response.getExecutions(0).getExecution().getWorkflowId();
                    LOGGER.debug("Found workflow by correlation keys (attempt {}): {}", attempt, foundId);
                    return foundId;
                }

                // Not found yet, wait before retry (visibility eventual consistency)
                if (withRetry && attempt < maxAttempts) {
                    LOGGER.debug("Workflow not found in visibility, retrying... (attempt {}/{})", 
                            attempt, maxAttempts);
                    Thread.sleep(VISIBILITY_RETRY_DELAY_MS * attempt);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Visibility query interrupted");
                return null;
            } catch (Exception e) {
                LOGGER.warn("Visibility query failed (attempt {}): {}", attempt, e.getMessage());
                if (withRetry && attempt < maxAttempts) {
                    try {
                        Thread.sleep(VISIBILITY_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        // Only log warning when retries were used and still not found (signal sending case)
        if (withRetry) {
            LOGGER.debug("No running workflow found for correlation keys after {} attempts: {}", 
                    attempt - 1, correlationKeys);
        }
        return null;
    }

    /**
     * Shuts down the workflow runtime.
     */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }

        // Shutdown the executor
        executor.shutdown();

        // The Temporal workers are shutdown through WorkflowWorkerNative.stopSingletonWorker()
        // which is called from Ballerina stop lifecycle

        initialized = false;
        LOGGER.debug("WorkflowRuntime shutdown");
    }
}
