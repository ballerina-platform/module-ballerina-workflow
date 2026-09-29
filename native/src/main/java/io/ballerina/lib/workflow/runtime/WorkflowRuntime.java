/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import io.ballerina.lib.workflow.TaskKeys;
import io.ballerina.lib.workflow.observability.TraceContextPropagator;
import io.ballerina.lib.workflow.observability.WorkerSpans;
import io.ballerina.lib.workflow.observability.WorkflowMetrics;
import io.ballerina.lib.workflow.observability.WorkflowSampleLog;
import io.ballerina.lib.workflow.utils.CorrelationExtractor;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
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
 * This class serves as the central runtime for the workflow module, coordinating the execution of workflow processes
 * and activities. It delegates to the singleton WorkflowWorkerNative for Temporal integration.
 *
 * @since 0.1.0
 */
public final class WorkflowRuntime {

    /** Memo key of the starter identity the management API records. */
    public static final String STARTED_BY_MEMO = "startedBy";
    private static final String RUNNING_STATUS = "RUNNING";

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
     * The actual Temporal initialization is done through WorkflowWorkerNative.initSingletonWorker(). This method just
     * marks the runtime as initialized.
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
     * Starts a new workflow process under a generated UUID v7 id, with no id policies set.
     *
     * @param processName the name of the process to start
     * @param input       the input data for the process
     * @return the workflow ID
     * @throws IllegalArgumentException if the process is not registered
     * @throws IllegalStateException    if the runtime is not properly initialized
     */
    public String createInstance(String processName, Object input) {
        return createInstance(processName, input, StartOptions.generated()).workflowId();
    }

    /**
     * Starts a workflow process — the one start path every entry point converges on: {@code run},
     * {@code runWithId}, a durable agent's run and the management API's start.
     *
     * @param processName the registered Temporal type
     * @param input       the input data for the process
     * @param options     the caller's id and policies; validated here
     * @return the instance and run the caller now holds
     * @throws IllegalArgumentException       when the process is unknown
     * @throws InvalidStartOptionsException   when the id or a policy is not acceptable
     * @throws InstanceAlreadyExistsException when the id is held and the policy refused the start
     * @throws IllegalStateException          when the runtime is not initialized or the engine refused
     */
    public StartedInstance createInstance(String processName, Object input, StartOptions options) {
        if (!WorkflowWorkerNative.getProcessRegistry().containsKey(processName)) {
            throw new IllegalArgumentException("Process not registered: " + processName);
        }
        options.validate();
        String workflowId = options.hasCallerId() ? options.instanceId() : CorrelationExtractor.generateWorkflowId();

        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            throw new IllegalStateException("Workflow client not initialized. Ensure worker is initialized.");
        }
        String taskQueue = WorkflowWorkerNative.getTaskQueue();
        if (taskQueue == null) {
            throw new IllegalStateException("Task queue not configured.");
        }

        boolean joinIfRunning = options.hasCallerId()
                && options.conflictPolicy() == WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
        try {
            // The kind memo is how a consumer learns what an instance is without parsing its id.
            String kind = WorkflowWorkerNative.isAgentWorkflowType(processName) ? "AGENT" : "WORKFLOW";
            java.util.Map<String, Object> memo = new java.util.HashMap<>();
            memo.put(TaskKeys.KIND, kind);
            if (options.startedBy() != null && !options.startedBy().isBlank()) {
                memo.put(STARTED_BY_MEMO, options.startedBy());
            }
            WorkflowOptions.Builder optionsBuilder = WorkflowOptions
                    .newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue)
                    .setMemo(memo);
            if (options.timeoutSeconds() != null) {
                optionsBuilder.setWorkflowExecutionTimeout(java.time.Duration.ofSeconds(options.timeoutSeconds()));
            }
            if (options.hasCallerId()) {
                // Only a chosen id can collide, so only then are the policies sent. USE_EXISTING is
                // asked of the engine as FAIL and joined here from the refusal, which names the run
                // that holds the id — the SDK's start would not say whether it joined or created.
                optionsBuilder.setWorkflowIdConflictPolicy(joinIfRunning
                        ? WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL : options.conflictPolicy());
                optionsBuilder.setWorkflowIdReusePolicy(options.reusePolicy());
            }
            if (WorkflowWorkerNative.isKindSearchAttributeReady()) {
                optionsBuilder.setTypedSearchAttributes(io.temporal.common.SearchAttributes.newBuilder()
                        .set(WorkflowWorkerNative.WORKFLOW_KIND_KEY, kind).build());
            }
            WorkflowStub workflowStub = client.newUntypedWorkflowStub(processName, optionsBuilder.build());

            // The started event is counted at the worker's first execution, where every start
            // path converges. The run's spans open in the instance's own trace.
            WorkflowExecution execution = TraceContextPropagator.runWith(WorkerSpans.instanceContext(workflowId),
                    () -> workflowStub.start(input));
            LOGGER.debug("Started workflow: type={}, id={}", processName, workflowId);
            return new StartedInstance(execution.getWorkflowId(), execution.getRunId(), true);
        } catch (WorkflowExecutionAlreadyStarted e) {
            String status = describeStatus(client, workflowId);
            if (joinIfRunning && RUNNING_STATUS.equals(status) && e.getExecution() != null) {
                return new StartedInstance(workflowId, e.getExecution().getRunId(), false);
            }
            throw new InstanceAlreadyExistsException(workflowId, status);
        } catch (Exception e) {
            LOGGER.error("Failed to start workflow {}: {}", processName, e.getMessage(), e);
            throw new IllegalStateException("Failed to start workflow: " + e.getMessage(), e);
        }
    }

    private static String describeStatus(WorkflowClient client, String workflowId) {
        try {
            return io.ballerina.lib.workflow.runtime.nativeimpl.WorkflowNative.convertStatus(
                    client.newUntypedWorkflowStub(workflowId).describe().getWorkflowExecutionInfo().getStatus());
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Executes an activity within the current workflow context. Note: Activity execution is handled by the Temporal SDK
     * through WorkflowWorkerNative. This method is kept for compatibility but actual activity execution goes through
     * the dynamic activity adapter in WorkflowWorkerNative.
     *
     * @param activityName the name of the activity to execute
     * @param args         the arguments to pass to the activity
     * @return the result of the activity
     * @throws IllegalArgumentException if the activity is not registered
     * @throws UnsupportedOperationException always, once the registration check passes
     */
    public Object executeActivity(String activityName, Object[] args) {
        // Verify the activity is registered in WorkflowWorkerNative
        if (!WorkflowWorkerNative.getActivityRegistry().containsKey(activityName)) {
            throw new IllegalArgumentException("Activity not registered: " + activityName);
        }

        // Activity execution is handled by Temporal through the BallerinaActivityAdapter
        // in WorkflowWorkerNative. This method should not be called directly.
        // The module-level callActivity() function in Ballerina uses WorkflowNative.callActivity()
        // which delegates to Temporal's activity stub.
        throw new UnsupportedOperationException(
                "Direct activity execution not supported. Use module-level callActivity() function.");
    }

    /**
     * Sends a signal directly to a workflow by its workflow ID.
     * <p>
     * This method is used when the caller knows the exact workflow ID. No correlation key lookup is needed.
     *
     * @param workflowId the workflow ID to send the signal to
     * @param signalName the name of the signal to send
     * @param signalData the signal data (can be null)
     * @return true if the signal was sent successfully
     * @throws IllegalStateException if the workflow client is not initialized, or the signal fails
     * @throws IllegalArgumentException if the workflow ID or signal name is missing
     */
    public boolean sendSignalToWorkflow(String workflowId, String signalName, Object signalData) {
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        if (client == null) {
            throw new IllegalStateException("Workflow client not initialized. Ensure worker is initialized.");
        }

        if (workflowId == null || workflowId.isEmpty()) {
            throw new IllegalArgumentException("Workflow ID is required when sending signal by workflowId");
        }

        if (signalName == null || signalName.isEmpty()) {
            throw new IllegalArgumentException("Signal name is required when sending signal by workflowId");
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

            WorkflowMetrics.recordDataSent(signalName, null);
            WorkflowSampleLog.dataSent(signalName, workflowId, false);
            LOGGER.debug("Sent signal directly to workflow: id={}, signalName={}", workflowId, signalName);
            return true;

        } catch (WorkflowNotFoundException e) {
            // The workflow completed or was terminated before this signal was delivered.
            // Returns false so the caller can decide whether to surface this as an error.
            WorkflowMetrics.recordDataSent(signalName, e);
            WorkflowSampleLog.dataSent(signalName, workflowId, true);
            LOGGER.debug("Signal '{}' dropped: workflow {} is no longer running", signalName, workflowId);
            return false;
        } catch (Exception e) {
            WorkflowMetrics.recordDataSent(signalName, e);
            WorkflowSampleLog.dataSent(signalName, workflowId, true);
            LOGGER.error("Failed to send signal to workflow {}: {}", workflowId, e.getMessage(), e);
            throw new IllegalStateException("Failed to send signal: " + e.getMessage(), e);
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
