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

package io.ballerina.stdlib.workflow.worker;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.concurrent.StrandMetadata;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.internal.values.FPValue;
import io.ballerina.stdlib.workflow.ModuleUtils;
import io.ballerina.stdlib.workflow.context.SignalAwaitWrapper;
import io.ballerina.stdlib.workflow.context.WorkflowContextNative;
import io.ballerina.stdlib.workflow.registry.EventInfo;
import io.ballerina.stdlib.workflow.runtime.WorkflowRuntime;
import io.ballerina.stdlib.workflow.utils.EventExtractor;
import io.ballerina.stdlib.workflow.utils.EventFutureCreator;
import io.ballerina.stdlib.workflow.utils.TypesUtil;
import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.EncodedValues;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native implementation for workflow worker operations.
 * Implements a singleton worker pattern - only one Temporal SDK instance per JVM.
 *
 * @since 0.1.0
 */
public final class WorkflowWorkerNative {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowWorkerNative.class);

    // Static registry to store service objects accessible during workflow execution
    private static final Map<String, BObject> SERVICE_REGISTRY = new ConcurrentHashMap<>();

    // Static registry to store activity implementations (activity name to BFunctionPointer)
    private static final Map<String, BFunctionPointer> ACTIVITY_REGISTRY = new ConcurrentHashMap<>();

    // Static registry to store process functions (workflow type to BFunctionPointer)
    private static final Map<String, BFunctionPointer> PROCESS_REGISTRY = new ConcurrentHashMap<>();

    // Static registry to store event names per process (process name to list of event names)
    private static final Map<String, List<String>> EVENT_REGISTRY = new ConcurrentHashMap<>();

    // Singleton worker components
    private static volatile WorkflowServiceStubs serviceStubs;
    private static volatile WorkflowClient workflowClient;
    private static volatile WorkerFactory workerFactory;
    private static volatile Worker singletonWorker;
    private static volatile String taskQueue;
    private static volatile TestWorkflowEnvironment testEnvironment;

    // Flags for singleton state
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean started = new AtomicBoolean(false);

    // Deadline for blocking gRPC introspection calls (e.g., describeWorkflowExecution).
    // Increase if the Temporal server is remote and latency is higher than 5 seconds.
    private static final int GET_INFO_DEADLINE_SECONDS = 5;
    private static volatile boolean inMemoryMode = false;

    // Global default activity retry policy (set from WorkerConfig.defaultActivityRetryPolicy)
    private static volatile io.temporal.common.RetryOptions defaultActivityRetryOptions;

    // Store workflow module for creating Context objects
    private static Module workflowModule;

    // Store Runtime instance for creating Strands
    private static Runtime ballerinaRuntime;

    private WorkflowWorkerNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Module initialization - called by Ballerina runtime.
     * Captures the Runtime from Environment for later use.
     * The Module reference is obtained from ModuleUtils (set during initModule()).
     *
     * @param env the Ballerina runtime environment
     */
    public static void init(Environment env) {
        workflowModule = ModuleUtils.getModule();
        ballerinaRuntime = env.getRuntime();
    }

    /**
     * Initialize the singleton workflow worker.
     * This is called during Ballerina module initialization with configuration from configurable variables.
     *
     * @param url Workflow server URL
     * @param namespace Workflow namespace
     * @param workerTaskQueue Task queue for the worker
     * @param maxConcurrentWorkflows Maximum concurrent workflow executions
     * @param maxConcurrentActivities Maximum concurrent activity executions
     * @param apiKey API key for authentication (empty string if not used)
     * @param mtlsCert Path to mTLS client certificate file (empty string if not used)
     * @param mtlsKey Path to mTLS client private key file (empty string if not used)
     * @param caCert Path to CA certificate for server trust (empty string to use JVM default trust store)
     * @param defaultRetryPolicy Default activity retry policy from WorkerConfig
     * @return null on success, error on failure
     */
    @SuppressWarnings("unchecked")
    public static Object initSingletonWorker(
            BString url,
            BString namespace,
            BString workerTaskQueue,
            long maxConcurrentWorkflows,
            long maxConcurrentActivities,
            BString apiKey,
            BString mtlsCert,
            BString mtlsKey,
            BString caCert,
            BMap<BString, Object> defaultRetryPolicy) {

        if (!initialized.compareAndSet(false, true)) {
            LOGGER.debug("Singleton worker already initialized");
            return null;
        }

        try {
            String serverUrl = url.getValue();
            String ns = namespace.getValue();
            taskQueue = workerTaskQueue.getValue();
            String apiKeyValue = apiKey.getValue();
            String mtlsCertPath = mtlsCert.getValue();
            String mtlsKeyPath = mtlsKey.getValue();
            String caCertPath = caCert.getValue();

            LOGGER.debug("Initializing singleton workflow worker - URL: {}, Namespace: {}, TaskQueue: {}",
                    serverUrl, ns, taskQueue);

            // Create service stubs (connection to workflow server)
            WorkflowServiceStubsOptions.Builder stubsBuilder = WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(serverUrl);

            boolean hasMtls = !mtlsCertPath.isEmpty() && !mtlsKeyPath.isEmpty();
            boolean hasCaCert = !caCertPath.isEmpty();
            boolean hasApiKey = !apiKeyValue.isEmpty();

            // Configure TLS/mTLS when needed (client cert and/or custom server CA)
            if (hasMtls || hasCaCert) {
                try {
                    io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder sslBuilder =
                            io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forClient();
                    if (hasMtls) {
                        try (InputStream certStream = new FileInputStream(mtlsCertPath);
                             InputStream keyStream = new FileInputStream(mtlsKeyPath)) {
                            sslBuilder.keyManager(certStream, keyStream);
                        }
                        LOGGER.debug("mTLS client certificate configured: {}", mtlsCertPath);
                    }
                    if (hasCaCert) {
                        try (InputStream caStream = new FileInputStream(caCertPath)) {
                            sslBuilder.trustManager(caStream);
                        }
                        LOGGER.debug("Custom CA certificate configured: {}", caCertPath);
                    }
                    stubsBuilder.setSslContext(sslBuilder.build());
                    stubsBuilder.setEnableHttps(true);
                } catch (IOException e) {
                    initialized.set(false);
                    return ErrorCreator.createError(
                            StringUtils.fromString("Failed to configure TLS/mTLS: " + e.getMessage()));
                }
            }

            // Configure API key authentication if provided
            if (hasApiKey) {
                stubsBuilder.addApiKey(() -> apiKeyValue);
                if (!hasMtls && !hasCaCert) {
                    // API key over default JVM TLS (public CA trust store)
                    stubsBuilder.setEnableHttps(true);
                }
                LOGGER.debug("API key authentication configured");
            }

            WorkflowServiceStubsOptions stubsOptions = stubsBuilder.build();
            serviceStubs = WorkflowServiceStubs.newServiceStubs(stubsOptions);

            // Create a DataConverter with a custom FailureConverter that replaces
            // "JavaSDK" source with "BallerinaSDK" in failure protos
            io.temporal.common.converter.DataConverter dataConverter =
                    io.temporal.common.converter.DefaultDataConverter.newDefaultInstance()
                            .withFailureConverter(
                                    new io.ballerina.stdlib.workflow.utils.BallerinaFailureConverter());

            // Create workflow client
            io.temporal.client.WorkflowClientOptions clientOptions =
                    io.temporal.client.WorkflowClientOptions.newBuilder()
                            .setNamespace(ns)
                            .setDataConverter(dataConverter)
                            .build();
            workflowClient = WorkflowClient.newInstance(serviceStubs, clientOptions);

            // Create worker factory
            workerFactory = WorkerFactory.newInstance(workflowClient);

            // Create worker with options
            WorkerOptions workerOptions = WorkerOptions.newBuilder()
                    .setMaxConcurrentWorkflowTaskExecutionSize((int) maxConcurrentWorkflows)
                    .setMaxConcurrentActivityExecutionSize((int) maxConcurrentActivities)
                    .build();

            singletonWorker = workerFactory.newWorker(taskQueue, workerOptions);

            // Parse and store global default activity retry policy
            defaultActivityRetryOptions = parseRetryPolicy(defaultRetryPolicy);
            LOGGER.debug("Default activity retry policy: maxAttempts={}", 
                    defaultActivityRetryOptions.getMaximumAttempts());

            // Register dynamic workflow and activity adapters eagerly.
            // This must happen before workerFactory.start() is called.
            // The adapters route all workflow/activity invocations through PROCESS_REGISTRY
            // and ACTIVITY_REGISTRY, so processes can be registered at any time.
            singletonWorker.registerWorkflowImplementationTypes(BallerinaWorkflowAdapter.class);
            singletonWorker.registerActivitiesImplementations(new BallerinaActivityAdapter());
            LOGGER.debug("Registered dynamic workflow and activity adapters");

            LOGGER.debug("Singleton worker initialized successfully");
            return null;

        } catch (Exception e) {
            initialized.set(false);
            LOGGER.error("Failed to initialize singleton worker: {}", e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to initialize workflow worker: " + e.getMessage()));
        }
    }

    /**
     * Initialize an in-memory workflow worker using Temporal's TestWorkflowEnvironment.
     * This mode does not require an external server. Workflows are not persisted
     * and will be lost on restart.
     *
     * @return null on success, error on failure
     */
    public static Object initInMemoryWorker() {
        if (!initialized.compareAndSet(false, true)) {
            LOGGER.debug("Singleton worker already initialized");
            return null;
        }

        try {
            inMemoryMode = true;
            taskQueue = "BALLERINA_WORKFLOW_TASK_QUEUE";

            LOGGER.debug("Initializing in-memory workflow worker with TestWorkflowEnvironment");

            // Create a DataConverter with a custom FailureConverter that replaces
            // "JavaSDK" source with "BallerinaSDK" in failure protos
            io.temporal.common.converter.DataConverter dataConverter =
                    io.temporal.common.converter.DefaultDataConverter.newDefaultInstance()
                            .withFailureConverter(
                                    new io.ballerina.stdlib.workflow.utils.BallerinaFailureConverter());

            // Create the in-memory test environment with custom data converter
            io.temporal.testing.TestEnvironmentOptions testOptions =
                    io.temporal.testing.TestEnvironmentOptions.newBuilder()
                            .setWorkflowClientOptions(
                                    io.temporal.client.WorkflowClientOptions.newBuilder()
                                            .setDataConverter(dataConverter)
                                            .build())
                            .build();
            testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);

            // Extract components from the test environment
            workflowClient = testEnvironment.getWorkflowClient();
            workerFactory = testEnvironment.getWorkerFactory();
            serviceStubs = testEnvironment.getWorkflowServiceStubs();

            // Create worker with default options
            WorkerOptions workerOptions = WorkerOptions.newBuilder()
                    .setMaxConcurrentWorkflowTaskExecutionSize(100)
                    .setMaxConcurrentActivityExecutionSize(100)
                    .build();
            singletonWorker = testEnvironment.newWorker(taskQueue, workerOptions);

            // Set default activity retry options for in-memory mode (matches Ballerina defaults)
            defaultActivityRetryOptions = io.temporal.common.RetryOptions.newBuilder()
                    .setInitialInterval(java.time.Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(1)
                    .build();

            // Register dynamic workflow and activity adapters
            singletonWorker.registerWorkflowImplementationTypes(BallerinaWorkflowAdapter.class);
            singletonWorker.registerActivitiesImplementations(new BallerinaActivityAdapter());

            LOGGER.debug("In-memory workflow worker initialized successfully");
            return null;

        } catch (Exception e) {
            initialized.set(false);
            inMemoryMode = false;
            LOGGER.error("Failed to initialize in-memory worker: {}", e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to initialize in-memory workflow worker: " + e.getMessage()));
        }
    }

    /**
     * Register a workflow with the singleton program.
     * Called from Ballerina code for each workflow.
     *
     * @param env Environment for capturing runtime
     * @param workflowFunction The Ballerina workflow function pointer
     * @param workflowName The name of the workflow (workflow type)
     * @param activities Optional map of activity functions
     * @return true on success, error on failure
     */
    public static Object registerWorkflow(
            Environment env,
            BFunctionPointer workflowFunction,
            BString workflowName,
            Object activities) {

        // Use ModuleUtils to get the workflow module (set during initModule() in module.bal)
        // env.getCurrentModule() returns the caller's module, not ballerina/workflow
        synchronized (WorkflowWorkerNative.class) {
            if (ballerinaRuntime == null) {
                ballerinaRuntime = env.getRuntime();
            }
            if (workflowModule == null) {
                workflowModule = ModuleUtils.getModule();
            }
        }

        if (!initialized.get()) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow program not initialized. Module initialization may have failed."));
        }

        if (singletonWorker == null) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow program is null. Initialization may have failed."));
        }

        try {
            String workflowType = workflowName.getValue();
            LOGGER.debug("Registering workflow: {}", workflowType);

            // Atomically register — putIfAbsent returns the existing value (non-null) if
            // a workflow with this name is already registered, null if insertion succeeded.
            BFunctionPointer existing = PROCESS_REGISTRY.putIfAbsent(workflowType, workflowFunction);
            if (existing != null) {
                return ErrorCreator.createError(
                        StringUtils.fromString("Workflow with name '" + workflowType + "' is already registered"));
            }

            // Register activities if provided
            if (activities != null) {
                @SuppressWarnings("unchecked")
                BMap<BString, BFunctionPointer> activityMap = (BMap<BString, BFunctionPointer>) activities;
                for (BString activityName : activityMap.getKeys()) {
                    BFunctionPointer activityFunc = activityMap.get(activityName);
                    String fullActivityName = workflowType + "." + activityName.getValue();
                    ACTIVITY_REGISTRY.put(fullActivityName, activityFunc);
                    LOGGER.debug("Registered activity: {}", fullActivityName);
                }
            }

            // Extract and register events from the workflow function signature
            List<EventInfo> events = EventExtractor.extractEvents(workflowFunction, workflowType);
            if (!events.isEmpty()) {
                List<String> eventNames = new ArrayList<>();
                for (EventInfo event : events) {
                    eventNames.add(event.fieldName());
                }
                EVENT_REGISTRY.put(workflowType, eventNames);
                LOGGER.debug("Registered {} events for process: {}", eventNames.size(), workflowType);
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to register workflow {}: {}", workflowName.getValue(), e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to register workflow: " + e.getMessage()));
        }
    }

    /**
     * Creates a wrapper BObject for a process function.
     * This allows the process function to be treated like a service object.
    /**
     * Start the singleton worker.
     * This begins polling for workflow and activity tasks.
     *
     * @return null on success, error on failure
     */
    public static Object startSingletonWorker() {
        if (!initialized.get()) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Worker not initialized. Call initSingletonWorker first."));
        }

        if (!started.compareAndSet(false, true)) {
            LOGGER.debug("Singleton worker already started");
            return null;
        }

        try {
            LOGGER.debug("Starting singleton worker for task queue: {}", taskQueue);

            if (inMemoryMode && testEnvironment != null) {
                // In-memory mode: use TestWorkflowEnvironment.start()
                testEnvironment.start();
                LOGGER.debug("In-memory worker started successfully");
            } else {
                // Normal mode: start synchronously so initialization failures
                // are propagated to the caller.
                workerFactory.start();
            }

            LOGGER.debug("Singleton worker started successfully");
            return null;

        } catch (Exception e) {
            started.set(false);
            LOGGER.error("Failed to start worker: {}", e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to start worker: " + e.getMessage()));
        }
    }

    /**
     * Stop the singleton worker gracefully.
     *
     * @return null on success, error on failure
     */
    public static Object stopSingletonWorker() {
        if (!started.get()) {
            return null;
        }

        try {
            LOGGER.debug("Stopping singleton worker...");

            if (inMemoryMode && testEnvironment != null) {
                // In-memory mode: use TestWorkflowEnvironment.close()
                testEnvironment.close();
                testEnvironment = null;
                LOGGER.debug("In-memory worker closed");
            } else {
                if (workerFactory != null) {
                    workerFactory.shutdown();
                    workerFactory.awaitTermination(30, TimeUnit.SECONDS);
                }

                if (serviceStubs != null) {
                    serviceStubs.shutdown();
                }
            }

            started.set(false);
            LOGGER.debug("Singleton worker stopped");
            return null;

        } catch (Exception e) {
            LOGGER.error("Error stopping worker: {}", e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to stop worker: " + e.getMessage()));
        }
    }

    /**
     * Get the singleton WorkflowClient for starting workflows.
     *
     * @return the WorkflowClient or null if not initialized
     */
    public static WorkflowClient getWorkflowClient() {
        return workflowClient;
    }

    /**
     * Get the task queue name.
     *
     * @return the task queue name
     */
    public static String getTaskQueue() {
        return taskQueue;
    }

    /**
     * Check if the worker is running in in-memory mode.
     *
     * @return true if in-memory mode is active
     */
    public static boolean isInMemoryMode() {
        return inMemoryMode;
    }

    /**
     * Gets the service registry for testing purposes.
     *
     * @return the service registry map
     */
    public static Map<String, BObject> getServiceRegistry() {
        return Collections.unmodifiableMap(SERVICE_REGISTRY);
    }

    /**
     * Gets the activity registry for testing purposes.
     *
     * @return the activity registry map
     */
    public static Map<String, BFunctionPointer> getActivityRegistry() {
        return Collections.unmodifiableMap(ACTIVITY_REGISTRY);
    }

    /**
     * Gets the process registry for testing purposes.
     *
     * @return the process registry map
     */
    public static Map<String, BFunctionPointer> getProcessRegistry() {
        return Collections.unmodifiableMap(PROCESS_REGISTRY);
    }

    /**
     * Gets the event registry for testing purposes.
     *
     * @return the event registry map (process name to list of event names)
     */
    public static Map<String, List<String>> getEventRegistry() {
        return Collections.unmodifiableMap(EVENT_REGISTRY);
    }

    /**
     * Convert Ballerina types to Java types for Temporal serialization.
     *
     * @param ballerinaValue the Ballerina value to convert
     * @return the Java equivalent
     */
    static Object convertBallerinaToJavaType(Object ballerinaValue) {
        return TypesUtil.convertBallerinaToJavaType(ballerinaValue);
    }

    /**
     * Gets the global default activity retry options configured via WorkerConfig.
     *
     * @return the default retry options, or null if not configured
     */
    public static io.temporal.common.RetryOptions getDefaultActivityRetryOptions() {
        return defaultActivityRetryOptions;
    }

    /**
     * Parses a Ballerina ActivityRetryPolicy BMap into Temporal RetryOptions.
     *
     * @param retryMap the Ballerina retry policy record
     * @return the parsed RetryOptions
     */
    @SuppressWarnings("unchecked")
    public static io.temporal.common.RetryOptions parseRetryPolicy(BMap<BString, Object> retryMap) {
        io.temporal.common.RetryOptions.Builder retryBuilder = io.temporal.common.RetryOptions.newBuilder();

        Object initialInterval = retryMap.get(StringUtils.fromString("initialIntervalInSeconds"));
        if (initialInterval instanceof Long intervalVal) {
            if (intervalVal <= 0) {
                throw new IllegalArgumentException(
                        "initialIntervalInSeconds must be a positive integer, got " + intervalVal);
            }
            retryBuilder.setInitialInterval(java.time.Duration.ofSeconds(intervalVal));
        }

        Object backoff = retryMap.get(StringUtils.fromString("backoffCoefficient"));
        if (backoff instanceof io.ballerina.runtime.api.values.BDecimal) {
            double backoffVal = ((io.ballerina.runtime.api.values.BDecimal) backoff).floatValue();
            if (backoffVal < 1.0) {
                throw new IllegalArgumentException(
                        "backoffCoefficient must be >= 1.0, got " + backoffVal);
            }
            retryBuilder.setBackoffCoefficient(backoffVal);
        }

        Object maxInterval = retryMap.get(StringUtils.fromString("maximumIntervalInSeconds"));
        if (maxInterval instanceof Long maxIntervalVal) {
            if (maxIntervalVal <= 0) {
                throw new IllegalArgumentException(
                        "maximumIntervalInSeconds must be a positive integer, got " + maxIntervalVal);
            }
            retryBuilder.setMaximumInterval(java.time.Duration.ofSeconds(maxIntervalVal));
        }

        Object maxAttempts = retryMap.get(StringUtils.fromString("maximumAttempts"));
        if (maxAttempts instanceof Long maxAttemptsLong) {
            if (maxAttemptsLong < 0) {
                throw new IllegalArgumentException(
                        "maximumAttempts must be a non-negative integer, got " + maxAttemptsLong);
            }
            retryBuilder.setMaximumAttempts(Math.toIntExact(maxAttemptsLong));
        }

        return retryBuilder.build();
    }

    /**
     * Convert Java types to Ballerina types.
     *
     * @param javaValue the Java value to convert
     * @return the Ballerina equivalent
     */
    static Object convertJavaToBallerinaType(Object javaValue) {
        return TypesUtil.convertJavaToBallerinaType(javaValue);
    }

    /**
     * Gets error details from a Ballerina error as a serializable map.
     *
     * @param error the Ballerina error
     * @return a map representation of the error
     */
    static Map<String, Object> getErrorMap(BError error) {
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put(TypesUtil.ERROR_MARKER, true);
        errorMap.put(TypesUtil.ERROR_MESSAGE, error.getMessage());
        Object details = error.getDetails();
        if (details != null) {
            errorMap.put("details", convertBallerinaToJavaType(details));
        }
        return errorMap;
    }

    /**
     * Create a new instance of a service object based on a template service object.
     * This creates a per-workflow-instance copy to avoid state sharing.
     * Used for backward compatibility with service-based workflows.
     *
     * @param templateService The template service object to clone
     * @return A new instance of the same service type
     */
    private static BObject createServiceInstance(BObject templateService) {
        if (templateService == null) {
            return null;
        }

        try {
            // Get the type of the service object
            io.ballerina.runtime.api.types.ObjectType serviceType = templateService.getType();

            // Create a new instance of the same type
            // ValueCreator.createObjectValue() creates a fresh instance

            return ValueCreator.createObjectValue(
                    serviceType.getPackage(),
                    serviceType.getName()
                                                 );
        } catch (Exception e) {
            // If we can't create a new instance, fall back to template
            // This maintains backwards compatibility but may cause state sharing issues
            return templateService;
        }
    }

    /**
     * Dynamic workflow implementation that routes to Ballerina service.
     * This is used as a template for creating workflow implementations.
     */
    public static class BallerinaWorkflowAdapter implements DynamicWorkflow {

        // Per-workflow-instance service object (created fresh for each workflow execution including replays)
        // This ensures isolation between workflow instances and proper state management
        private BObject serviceObject;
        private String workflowType;

        // Per-workflow signal wrapper for managing signal futures
        // This handles signal recording and replay scenarios
        private final SignalAwaitWrapper signalWrapper = new SignalAwaitWrapper();

        // Workflow logger for deterministic logging
        private static final Logger LOGGER = Workflow.getLogger(BallerinaWorkflowAdapter.class);

        /**
         * Gets the signal wrapper for this workflow instance.
         * Used to get signal futures that can be passed to workflow functions.
         *
         * @return the SignalAwaitWrapper for this workflow
         */
        public SignalAwaitWrapper getSignalWrapper() {
            return signalWrapper;
        }

        /**
         * No-arg constructor required by Temporal for dynamic workflows.
         */
        public BallerinaWorkflowAdapter() {
            // Register a dynamic signal handler that handles all signals
            Workflow.registerListener(
                    (io.temporal.workflow.DynamicSignalHandler) (signalName, encodedArgs) -> {
                        LOGGER.debug("[JWorkflowAdapter] Signal received: {}", signalName);

                        // Extract signal data from encodedArgs
                        Map<String, Object> signalData = new HashMap<>();
                        try {
                            // Try to get the first argument as a Map
                            @SuppressWarnings("unchecked")
                            Map<String, String> argMap = encodedArgs.get(0, Map.class);
                            if (argMap != null) {
                                signalData.putAll(argMap);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[JWorkflowAdapter] Could not extract signal data as Map: {}",
                                    e.getMessage());
                        }

                        // Try to invoke remote method handler for this signal
                        Object signalResult = null;
                        boolean remoteMethodInvoked = false;

                        if (this.serviceObject != null) {
                            try {
                                LOGGER.debug("[JWorkflowAdapter] Attempting to invoke remote method: {}", signalName);

                                // Convert signal data to Ballerina map
                                Object ballerinaSignalData = convertJavaToBallerinaType(signalData);
                                Object[] methodArgs = new Object[]{ballerinaSignalData};

                                // Invoke the remote method (if it exists)
                                signalResult = ballerinaRuntime.callMethod(
                                        this.serviceObject,
                                        signalName,
                                        new StrandMetadata(true, Collections.emptyMap()),
                                        methodArgs
                                );

                                // Check if method returned an error - log but don't fail
                                if (signalResult instanceof BError err) {
                                    LOGGER.warn("[JWorkflowAdapter] Signal handler method '{}' returned error: {}",
                                            signalName, err.getMessage());
                                    // Still use the error as the signal result
                                }

                                remoteMethodInvoked = true;
                                LOGGER.debug("[JWorkflowAdapter] Remote method '{}' invoked successfully", signalName);

                            } catch (Exception e) {
                                // Method might not exist - that's okay, just log at debug level
                                LOGGER.debug("[JWorkflowAdapter] No remote method '{}' found or invocation failed: {}",
                                        signalName, e.getMessage());
                                // Fall back to default behavior - record signal data only
                            }
                        }

                        // Record the signal in the wrapper so futures can be completed
                        // If remote method was invoked, record its result; otherwise record the signal data
                        Object resultToRecord = remoteMethodInvoked ? signalResult : signalData;
                        signalWrapper.recordSignal(signalName, resultToRecord);
                        LOGGER.debug("[JWorkflowAdapter] Signal {} recorded in wrapper", signalName);
                    }
            );
            LOGGER.debug("[JWorkflowAdapter] Dynamic signal handler registered");

            // Register a dynamic query handler that routes to service methods
            Workflow.registerListener(
                    (io.temporal.workflow.DynamicQueryHandler) (queryName, encodedArgs) -> {
                        LOGGER.debug("[JWorkflowAdapter] Query received: {}", queryName);

                        try {
                            // Use the workflow's current ServiceObject instance
                            // Don't create a new instance - queries read from the active workflow state
                            if (this.serviceObject == null) {
                                String errorMsg = "Query called before workflow execution started";
                                LOGGER.error("[JWorkflowAdapter] {}", errorMsg);
                                throw new IllegalStateException(errorMsg);
                            }

                            // For now, we support only no-argument queries
                            Object[] queryArgs = new Object[0];

                            LOGGER.debug("[JWorkflowAdapter] Invoking query method '{}' on existing service instance",
                                    queryName);

                            // Invoke the query method on the EXISTING service object
                            Object result = ballerinaRuntime.callMethod(
                                    this.serviceObject,
                                    queryName,
                                    new StrandMetadata(true, Collections.emptyMap()),
                                    queryArgs
                            );

                            // Check if query returned an error - this should fail the query
                            if (result instanceof BError err) {
                                String errorMsg = err.getMessage();
                                LOGGER.error("[JWorkflowAdapter] Query method returned error: {}", errorMsg);
                                throw new IllegalStateException("Query failed: " + errorMsg);
                            }

                            // Convert Ballerina result to Java type for Temporal
                            Object javaResult = convertBallerinaToJavaType(result);

                            LOGGER.debug("[JWorkflowAdapter] Query {} completed successfully, result type: {}",
                                    queryName, (javaResult != null ? javaResult.getClass().getSimpleName() : "null"));

                            return javaResult;

                        } catch (Exception e) {
                            LOGGER.error("[JWorkflowAdapter] Query {} failed with exception: {}",
                                    queryName, e.getMessage());
                            throw new RuntimeException("Query execution failed: " + e.getMessage(), e);
                        }
                    }
            );
            LOGGER.debug("[JWorkflowAdapter] Dynamic query handler registered");
        }

        @Override
        public Object execute(EncodedValues args) {
            try {
                // Get workflow type from Temporal's Workflow.getInfo()
                io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
                this.workflowType = info.getWorkflowType();

                boolean isReplaying = Workflow.isReplaying();

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Executing workflow: {}", workflowType);
                }

                // First check for a registered process function
                BFunctionPointer processFunction = PROCESS_REGISTRY.get(workflowType);

                // Fall back to service registry for backward compatibility
                BObject templateService = SERVICE_REGISTRY.get(workflowType);

                if (processFunction == null && templateService == null) {
                    String errorMsg = String.format("Workflow '%s' is not registered. " +
                            "Please call registerWorkflow() for this workflow.", workflowType);
                    LOGGER.error("[JWorkflowAdapter] {}", errorMsg);

                    io.temporal.failure.ApplicationFailure failure =
                            io.temporal.failure.ApplicationFailure.newFailure(
                                    errorMsg,
                                    "error"
                            );
                    failure.setNonRetryable(true);
                    failure.setStackTrace(new StackTraceElement[0]);
                    throw failure;
                }

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Found registered workflow: {}", workflowType);
                }

                // Extract workflow arguments from EncodedValues
                Object[] workflowArgs = extractWorkflowArguments(args);

                // Check if the process function expects a Context parameter
                boolean hasContext = EventExtractor.hasContextParameter(processFunction);

                // Check if the process function expects an events record parameter
                RecordType eventsRecordType = processFunction != null ?
                        EventExtractor.getEventsRecordType(processFunction) : null;
                boolean hasEvents = eventsRecordType != null;

                // Build arguments array with Context and Events as needed
                List<Object> argsList = new ArrayList<>();
                
                // Add Context as first argument if needed
                if (hasContext) {
                    BObject contextObj = createWorkflowContext();
                    argsList.add(contextObj);
                }
                
                // Add workflow input arguments (from createInstance call)
                Collections.addAll(argsList, workflowArgs);
                
                // Add events record as last argument if needed
                if (hasEvents) {
                    // Create events record with TemporalFutureValue for each signal
                    // Get scheduler from runtime if available (for proper Strand creation)
                    io.ballerina.runtime.internal.scheduling.Scheduler scheduler = null;
                    if (ballerinaRuntime instanceof io.ballerina.runtime.internal.BalRuntime balRuntime) {
                        scheduler = balRuntime.scheduler;
                    }
                    BMap<BString, Object> eventsRecord = EventFutureCreator.createEventsRecord(
                            eventsRecordType, signalWrapper, scheduler);
                    argsList.add(eventsRecord);
                    
                    if (!isReplaying) {
                        LOGGER.debug("[JWorkflowAdapter] Injected events record with {} signals for workflow {}",
                                eventsRecordType.getFields().size(), workflowType);
                    }
                }
                
                Object[] ballerinaArgs = argsList.toArray();

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Invoking workflow {} with {} args (hasContext={}, hasEvents={})",
                            workflowType, ballerinaArgs.length, hasContext, hasEvents);
                }

                Object result;

                // Use process function if available (new singleton pattern)
                if (processFunction != null) {
                    // Call the function directly
                    FPValue fpValue = (FPValue) processFunction;
                    fpValue.metadata = new StrandMetadata(true, fpValue.metadata.properties());
                    result = processFunction.call(ballerinaRuntime, ballerinaArgs);
                } else {
                    // Fall back to service object (backward compatibility)
                    this.serviceObject = createServiceInstance(templateService);
                    // Call the execute method on the service object
                    result = ballerinaRuntime.callMethod(
                            serviceObject,
                            "execute",
                            new StrandMetadata(true, Collections.emptyMap()),
                            ballerinaArgs
                    );
                }

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Workflow {} completed with result type: {}",
                            workflowType, (result != null ? result.getClass().getSimpleName() : "null"));
                }

                // Check if workflow returned an error - this should fail the workflow execution
                if (result instanceof BError err) {
                    if (!isReplaying) {
                        LOGGER.error("[JWorkflowAdapter] Workflow {} returned error: {}", workflowType,
                                err.getMessage());
                    }

                    // Convert the full BError chain into an ApplicationFailure chain
                    // so the Temporal UI shows a structured cause/details hierarchy.
                    io.temporal.failure.ApplicationFailure failure = berrorToApplicationFailure(err);
                    failure.setNonRetryable(true);

                    throw failure;
                }

                // Convert Ballerina result to Java type for Temporal serialization
                Object javaResult = convertBallerinaToJavaType(result);

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Workflow completed successfully, result: {}", javaResult);
                }

                return javaResult;

            } catch (io.temporal.failure.TemporalFailure e) {
                // Re-throw Temporal failures as-is (ApplicationFailure, etc.)
                throw e;
            } catch (Exception e) {
                // Check if this is a DestroyWorkflowThreadError (expected during shutdown)
                boolean isDestroyError = isDestroyWorkflowThreadError(e);

                if (isDestroyError) {
                    // This is expected during service shutdown - workflow thread is being destroyed
                    // Just log at debug level and re-throw to let Temporal handle cleanup
                    LOGGER.debug("[JWorkflowAdapter] Workflow {} thread destroyed during shutdown (expected)",
                            workflowType);
                    throw e;
                }

                // Wrap unexpected exceptions in ApplicationFailure to avoid workflow task retry loop
                LOGGER.error("[JWorkflowAdapter] Workflow {} encountered an unexpected error",
                        workflowType, e);

                String errorMsg = String.format("Workflow '%s' encountered an unexpected error: %s",
                        workflowType, e.getMessage());

                io.temporal.failure.ApplicationFailure failure =
                        io.temporal.failure.ApplicationFailure.newFailure(
                                errorMsg,
                                "error"
                        );
                failure.setNonRetryable(true);
                failure.setStackTrace(new StackTraceElement[0]);

                throw failure;
            }
        }

        /**
         * Checks if an exception is caused by DestroyWorkflowThreadError.
         *
         * @param e the exception to check
         * @return true if it's a destroy workflow thread error
         */
        private boolean isDestroyWorkflowThreadError(Exception e) {
            // Check the exception message first (most reliable for wrapped errors)
            String message = e.getMessage();
            if (message != null && message.contains("io.temporal.internal.sync.DestroyWorkflowThreadError")) {
                return true;
            }

            // Check exception class name
            String className = e.getClass().getName();
            if (className.contains("DestroyWorkflowThreadError")) {
                return true;
            }

            // Check cause chain
            Throwable cause = e.getCause();
            while (cause != null) {
                String causeName = cause.getClass().getName();
                if (causeName.contains("DestroyWorkflowThreadError")) {
                    return true;
                }
                String causeMsg = cause.getMessage();
                if (causeMsg != null && causeMsg.contains("DestroyWorkflowThreadError")) {
                    return true;
                }
                cause = cause.getCause();
            }

            return false;
        }

        /**
         * Extract workflow arguments from EncodedValues.
         *
         * @param args the encoded values from Temporal
         * @return array of workflow arguments
         */
        private Object[] extractWorkflowArguments(EncodedValues args) {
            List<Object> argsList = new ArrayList<>();
            // EncodedValues doesn't have a size() method, so try to get up to 10 args
            for (int i = 0; i < 10; i++) {
                try {
                    Object arg = args.get(i, Object.class);
                    if (arg != null) {
                        // Convert Java types to Ballerina types
                        argsList.add(convertJavaToBallerinaType(arg));
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    // No more arguments
                    break;
                }
            }

            return argsList.toArray();
        }

        /**
         * Create a Ballerina Context object for the workflow.
         *
         * @return the Context BObject
         */
        private BObject createWorkflowContext() {
            // Ensure workflow module is initialized
            if (workflowModule == null) {
                String errorMsg = "Ballerina workflow module is not properly initialized. " +
                        "This is an internal configuration error.";
                LOGGER.error("[JWorkflowAdapter] {}", errorMsg);

                io.temporal.failure.ApplicationFailure failure =
                        io.temporal.failure.ApplicationFailure.newFailure(
                                errorMsg,
                                "error"
                        );
                failure.setNonRetryable(true);
                failure.setStackTrace(new StackTraceElement[0]);
                throw failure;
            }

            // Create a proper ContextInfo object from WorkflowContextNative
            // This is what the native methods expect as the context handle
            io.temporal.workflow.WorkflowInfo temporalInfo = Workflow.getInfo();
            Object contextInfo = WorkflowContextNative.createContext(
                    temporalInfo.getWorkflowId(),
                    temporalInfo.getWorkflowType()
            );

            // Wrap in HandleValue for Ballerina
            Object nativeContextHandle = ValueCreator.createHandleValue(contextInfo);

            // Create the Context object using ValueCreator with the proper module
            // Context has init(handle nativeContext) constructor

            return ValueCreator.createObjectValue(
                    workflowModule,
                    "Context",
                    nativeContextHandle
                                                 );
        }
    }

    /**
     * Converts a Ballerina {@link BError} (with optional cause chain) into an
     * {@link io.temporal.failure.ApplicationFailure} chain suitable for the
     * Temporal Failure proto.
     * <p>
     * Each BError produces an {@code ApplicationFailure} with:
     * <ul>
     *   <li>{@code message} – the BError message</li>
     *   <li>{@code type} – the supplied {@code typeName}</li>
     *   <li>{@code details} – the BError detail record (omitted when empty)</li>
     *   <li>{@code cause} – iteratively converted from
     *       {@link BError#getCause()}</li>
     * </ul>
     * Stack traces are suppressed so the Temporal UI stays Ballerina-centric.
     * <p>
     * Uses an iterative approach to avoid stack overflow on deeply nested
     * BError cause chains.
     *
     * @param err      the Ballerina error to convert
     * @param typeName the {@code type} string for the ApplicationFailure
     */
    @SuppressWarnings("unchecked")
    static io.temporal.failure.ApplicationFailure berrorToApplicationFailure(BError err,
                                                                            String typeName) {
        // Hard limit to prevent unbounded traversal of cause chains.
        final int maxDepth = 64;

        // 1. Walk the BError cause chain and collect each node's data.
        List<BError> chain = new ArrayList<>();
        BError current = err;
        while (current != null && chain.size() < maxDepth) {
            chain.add(current);
            Throwable cause = current.getCause();
            current = (cause instanceof BError) ? (BError) cause : null;
        }

        // 2. Build ApplicationFailure instances bottom-up (innermost cause first).
        io.temporal.failure.ApplicationFailure inner = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            BError node = chain.get(i);

            // Convert detail record – only include when non-empty so the Temporal
            // UI does not show a hollow {"payloads":[{}]} entry.
            BMap<?, ?> detailMap = (BMap<?, ?>) node.getDetails();
            boolean hasDetails = detailMap != null && !detailMap.isEmpty();
            Object details = hasDetails ? TypesUtil.convertBallerinaToJavaType(detailMap) : null;

            io.temporal.failure.ApplicationFailure failure;
            if (inner != null) {
                failure = hasDetails
                        ? io.temporal.failure.ApplicationFailure.newFailureWithCause(
                                node.getMessage(), typeName, inner, details)
                        : io.temporal.failure.ApplicationFailure.newFailureWithCause(
                                node.getMessage(), typeName, inner);
            } else {
                failure = hasDetails
                        ? io.temporal.failure.ApplicationFailure.newFailure(
                                node.getMessage(), typeName, details)
                        : io.temporal.failure.ApplicationFailure.newFailure(
                                node.getMessage(), typeName);
            }
            failure.setStackTrace(new StackTraceElement[0]);
            inner = failure;
        }

        return inner;
    }

    /**
     * Convenience overload that uses an empty type string.
     */
    static io.temporal.failure.ApplicationFailure berrorToApplicationFailure(BError err) {
        return berrorToApplicationFailure(err, "");
    }

    /**
     * Dynamic activity implementation that routes activity calls to registered Ballerina functions.
     * Uses Temporal's DynamicActivity interface for true dynamic routing without predefined method
     * signatures.
     * <p>
     * Supports a call configuration map appended as the last argument by {@code callActivity}.
     * The config map contains a {@code __callConfig__} marker and a {@code failOnError} flag.
     * When {@code failOnError} is {@code true} (default), a BError result causes the activity
     * to throw an {@link io.temporal.failure.ApplicationFailure}, triggering Temporal retries.
     * When {@code false}, the error is serialized and returned as a normal completion value.
     */
    public static class BallerinaActivityAdapter implements DynamicActivity {

        private static final String CALL_CONFIG_MARKER = "__callConfig__";
        private static final String FAIL_ON_ERROR_KEY = "failOnError";

        // Built-in implicit activity names
        public static final String BUILTIN_RUN = "workflow:run";
        public static final String BUILTIN_SEND_DATA = "workflow:sendData";
        public static final String BUILTIN_GET_RESULT = "workflow:getResult";
        public static final String BUILTIN_GET_INFO = "workflow:getInfo";

        @Override
        @SuppressWarnings("unchecked")
        public Object execute(EncodedValues args) {
            // Get activity name from Temporal's Activity.getExecutionContext()
            io.temporal.activity.ActivityExecutionContext activityContext =
                    io.temporal.activity.Activity.getExecutionContext();
            String activityName = activityContext.getInfo().getActivityType();

            // Handle built-in implicit activities
            if (BUILTIN_RUN.equals(activityName)) {
                return executeBuiltInRun(args);
            }
            if (BUILTIN_SEND_DATA.equals(activityName)) {
                return executeBuiltInSendData(args);
            }
            if (BUILTIN_GET_RESULT.equals(activityName)) {
                return executeBuiltInGetResult(args);
            }
            if (BUILTIN_GET_INFO.equals(activityName)) {
                return executeBuiltInGetInfo(args);
            }

            // Look up the registered Ballerina function for this activity
            BFunctionPointer activityFunction = ACTIVITY_REGISTRY.get(activityName);
            if (activityFunction == null) {
                String errorMsg = "Activity not registered: " + activityName +
                        ". Available activities: " + ACTIVITY_REGISTRY.keySet();
                throw new RuntimeException(errorMsg);
            }

            // Decode arguments from Temporal.
            // callActivity sends [namedArgsMap, callConfigMap].
            // The first argument is a Map<String,Object> of named activity args,
            // the second is the call configuration map.
            @SuppressWarnings("unchecked")
            Map<String, Object> namedArgs = args.get(0, Map.class);

            // Extract call configuration from the second argument
            boolean failOnError = true; // default behavior
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> callConfigMap = args.get(1, Map.class);
                if (callConfigMap != null
                        && Boolean.TRUE.equals(callConfigMap.get(CALL_CONFIG_MARKER))) {
                    Object failOnErrorVal = callConfigMap.get(FAIL_ON_ERROR_KEY);
                    if (failOnErrorVal instanceof Boolean) {
                        failOnError = (Boolean) failOnErrorVal;
                    }
                }
            } catch (Exception e) {
                // No call config available
            }

            // Reconstruct positional args by matching named map keys to the
            // function's parameter names. This ensures that omitted optional
            // parameters don't cause misalignment (e.g. when only url and auth
            // are provided but method/headers/payload are skipped).
            FunctionType funcType = (FunctionType) activityFunction.getType();
            Parameter[] allParams = funcType.getParameters();

            // Filter out typedesc parameters — they are not serialized in Temporal
            // workflow history. Capture the param so we can inject a BTypedesc<anydata>
            // as the last positional argument when calling the activity function.
            List<Parameter> dataParams = new ArrayList<>();
            Parameter typedescParam = null;
            for (Parameter p : allParams) {
                if (p.type.getTag() == TypeTags.TYPEDESC_TAG) {
                    typedescParam = p; // capture for later injection
                } else {
                    dataParams.add(p);
                }
            }

            // Find the last parameter that is present in the map so we can
            // omit trailing absent params (FPValue.call fills defaults for those).
            int lastProvidedIndex = -1;
            for (int i = 0; i < dataParams.size(); i++) {
                if (namedArgs.containsKey(dataParams.get(i).name)) {
                    lastProvidedIndex = i;
                }
            }

            // Build positional Ballerina args up to the last provided param
            List<Object> orderedArgs = new ArrayList<>();
            for (int i = 0; i <= lastProvidedIndex; i++) {
                String paramName = dataParams.get(i).name;
                if (namedArgs.containsKey(paramName)) {
                    orderedArgs.add(convertJavaToBallerinaType(namedArgs.get(paramName)));
                } else {
                    // Intermediate parameter missing from the named args map.
                    // Only optional/defaultable parameters may be absent; required parameters
                    // must always be supplied by the caller.
                    Parameter param = dataParams.get(i);
                    if (!param.isDefault) {
                        throw new RuntimeException(
                                "Required activity parameter '" + paramName
                                        + "' is missing from the activity arguments map");
                    }
                    orderedArgs.add(null); // optional/defaultable param absent → Ballerina default
                }
            }

            Object[] ballerinaArgs = orderedArgs.toArray();

            // If the activity declares a typedesc parameter, inject BTypedesc<anydata>
            // as the last positional arg. WorkflowContextNative.callActivity() applies
            // cloneWithType on the result to produce the actual target type requested
            // by the workflow caller.
            if (typedescParam != null) {
                Object[] argsWithTypedesc = new Object[ballerinaArgs.length + 1];
                System.arraycopy(ballerinaArgs, 0, argsWithTypedesc, 0, ballerinaArgs.length);
                argsWithTypedesc[ballerinaArgs.length] =
                        ValueCreator.createTypedescValue(PredefinedTypes.TYPE_ANYDATA);
                ballerinaArgs = argsWithTypedesc;
            }

            // Execute the Ballerina activity function
            FPValue fpValue = (FPValue) activityFunction;
            fpValue.metadata = new StrandMetadata(true, fpValue.metadata.properties());
            Object result = activityFunction.call(ballerinaRuntime, ballerinaArgs);

            // Handle BError results based on failOnError configuration
            if (result instanceof BError bError) {
                if (failOnError) {
                    // Convert the BError directly into an ApplicationFailure so the
                    // Temporal UI shows the original Ballerina error message, details,
                    // and cause chain without any extra wrapping.
                    throw berrorToApplicationFailure(bError, "ActivityFailed");
                }
                // failOnError is false - serialize error as normal completion value
            }

            // Convert result back to Java types for Temporal
            return convertBallerinaToJavaType(result);
        }

        /**
         * Built-in implicit activity: starts a new workflow instance.
         * <p>
         * Args layout: processName (String), input (Object, may be null).
         */
        private Object executeBuiltInRun(EncodedValues args) {
            String processName = args.get(0, String.class);
            Object input = args.get(1, Object.class);
            return WorkflowRuntime.getInstance().createInstance(processName, input);
        }

        /**
         * Built-in implicit activity: sends data (signal) to a running workflow.
         * <p>
         * Args layout: workflowId (String), dataName (String), data (Object).
         */
        private Object executeBuiltInSendData(EncodedValues args) {
            String workflowId = args.get(0, String.class);
            String dataName = args.get(1, String.class);
            Object data = args.get(2, Object.class);
            WorkflowRuntime.getInstance().sendSignalToWorkflow(workflowId, dataName, data);
            return null;
        }

        /**
         * Built-in implicit activity: gets a workflow execution result.
         * <p>
         * Args layout: workflowId (String), timeoutSeconds (int).
         * Returns a Map with workflowId, status, result, errorMessage.
         */
        @SuppressWarnings("unchecked")
        private Object executeBuiltInGetResult(EncodedValues args) {
            String workflowId = args.get(0, String.class);
            int timeoutSeconds = args.get(1, Integer.class);

            io.temporal.client.WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                throw new RuntimeException("Workflow client not initialized");
            }

            WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);

            Object result = null;
            String status;
            String errorMessage = null;

            try {
                result = stub.getResult(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS,
                        Object.class);
                status = "COMPLETED";
            } catch (io.temporal.client.WorkflowFailedException e) {
                status = "FAILED";
                errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            } catch (java.util.concurrent.TimeoutException e) {
                status = "RUNNING";
            } catch (Exception e) {
                status = "FAILED";
                errorMessage = e.getMessage();
            }

            java.util.Map<String, Object> info = new java.util.HashMap<>();
            info.put("workflowId", workflowId);
            info.put("status", status);
            info.put("result", result);
            info.put("errorMessage", errorMessage);
            return info;
        }

        private Object executeBuiltInGetInfo(EncodedValues args) {
            String workflowId = args.get(0, String.class);

            io.temporal.client.WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                throw new RuntimeException("Workflow client not initialized");
            }

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request =
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .build())
                            .build();

            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse response =
                    client.getWorkflowServiceStubs().blockingStub()
                            .withDeadlineAfter(GET_INFO_DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .describeWorkflowExecution(request);

            io.temporal.api.workflow.v1.WorkflowExecutionInfo execInfo =
                    response.getWorkflowExecutionInfo();
            String workflowType = execInfo.getType().getName();
            io.temporal.api.enums.v1.WorkflowExecutionStatus status = execInfo.getStatus();
            String statusStr = switch (status) {
                case WORKFLOW_EXECUTION_STATUS_RUNNING -> "RUNNING";
                case WORKFLOW_EXECUTION_STATUS_COMPLETED -> "COMPLETED";
                case WORKFLOW_EXECUTION_STATUS_FAILED -> "FAILED";
                case WORKFLOW_EXECUTION_STATUS_CANCELED -> "CANCELED";
                case WORKFLOW_EXECUTION_STATUS_TERMINATED -> "TERMINATED";
                case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> "CONTINUED_AS_NEW";
                case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> "TIMED_OUT";
                default -> "UNKNOWN";
            };

            java.util.Map<String, Object> info = new java.util.HashMap<>();
            info.put("workflowId", workflowId);
            info.put("workflowType", workflowType);
            info.put("status", statusStr);
            return info;
        }
    }
}
