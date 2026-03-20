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

package io.ballerina.lib.workflow.runtime;

import io.ballerina.lib.workflow.utils.CorrelationExtractor;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     *
     * @param processName the name of the process to start
     * @param input the input data for the process
     * @return the workflow ID
     * @throws RuntimeException if the process is not registered
     */
    public String createInstance(String processName, Object input) {
        // Verify the process is registered in WorkflowWorkerNative
        if (!WorkflowWorkerNative.getProcessRegistry().containsKey(processName)) {
            throw new RuntimeException("Process not registered: " + processName);
        }

        // Generate workflow ID using UUID v7
        String workflowId = CorrelationExtractor.generateWorkflowId();

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
            // Build workflow options
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue)
                    .build();

            // Create an untyped workflow stub for dynamic workflow execution
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(processName, options);

            // Start the workflow asynchronously with the input data
            workflowStub.start(input);

            LOGGER.debug("Started workflow: type={}, id={}", processName, workflowId);
            return workflowId;

        } catch (Exception e) {
            LOGGER.error("Failed to start workflow {}: {}", processName, e.getMessage(), e);
            throw new RuntimeException("Failed to start workflow: " + e.getMessage(), e);
        }
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
     * Sends a signal directly to a workflow by its workflow ID.
     * <p>
     * This method is used when the caller knows the exact workflow ID.
     * No correlation key lookup is needed.
     *
     * @param workflowId the workflow ID to send the signal to
     * @param signalName the name of the signal to send
     * @param signalData the signal data (can be null)
     * @return true if the signal was sent successfully
     * @throws RuntimeException if the workflow client is not initialized or signal fails
     */
    public boolean sendSignalToWorkflow(String workflowId, String signalName, Object signalData) {
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            throw new RuntimeException("Workflow client not initialized. Ensure worker is initialized.");
        }

        if (workflowId == null || workflowId.isEmpty()) {
            throw new RuntimeException("Workflow ID is required when sending signal by workflowId");
        }

        if (signalName == null || signalName.isEmpty()) {
            throw new RuntimeException("Signal name is required when sending signal by workflowId");
        }

        try {
            // Create an untyped workflow stub for the existing workflow
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(workflowId);

            // Send the signal to the workflow
            if (signalData != null) {
                workflowStub.signal(signalName, signalData);
            } else {
                workflowStub.signal(signalName);
            }

            LOGGER.debug("Sent signal directly to workflow: id={}, signalName={}",
                    workflowId, signalName);
            return true;

        } catch (WorkflowNotFoundException e) {
            // The workflow already completed before this signal was delivered.
            // This is a normal race condition (e.g. alternate-wait, timeout) — not an error.
            LOGGER.debug("Signal '{}' dropped: workflow {} has already completed",
                    signalName, workflowId);
            throw new RuntimeException("Failed to send signal: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.error("Failed to send signal to workflow {}: {}", workflowId, e.getMessage(), e);
            throw new RuntimeException("Failed to send signal: " + e.getMessage(), e);
        }
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
