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
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.internal.values.FPValue;
import io.ballerina.stdlib.workflow.context.SignalAwaitWrapper;
import io.ballerina.stdlib.workflow.context.WorkflowContextNative;
import io.ballerina.stdlib.workflow.registry.EventInfo;
import io.ballerina.stdlib.workflow.utils.CorrelationExtractor;
import io.ballerina.stdlib.workflow.utils.EventExtractor;
import io.ballerina.stdlib.workflow.utils.EventFutureCreator;
import io.ballerina.stdlib.workflow.utils.SearchAttributeRegistry;
import io.ballerina.stdlib.workflow.utils.TypesUtil;
import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.converter.EncodedValues;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Flags for singleton state
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean dynamicWorkflowRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean dynamicActivityRegistered = new AtomicBoolean(false);

    // Store workflow module for creating Context objects
    private static Module workflowModule;

    // Store Runtime instance for creating Strands
    private static Runtime ballerinaRuntime;

    private WorkflowWorkerNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Module initialization - called by Ballerina runtime.
     * Captures the Module and Runtime from Environment for later use.
     *
     * @param env the Ballerina runtime environment
     */
    public static void init(Environment env) {
        workflowModule = env.getCurrentModule();
        ballerinaRuntime = env.getRuntime();
    }

    /**
     * Initialize the singleton workflow worker.
     * This is called during Ballerina module initialization with configuration from configurable variables.
     *
     * @param url Temporal server URL
     * @param namespace Temporal namespace
     * @param workerTaskQueue Task queue for the worker
     * @param maxConcurrentWorkflows Maximum concurrent workflow executions
     * @param maxConcurrentActivities Maximum concurrent activity executions
     * @return null on success, error on failure
     */
    public static Object initSingletonWorker(
            BString url,
            BString namespace,
            BString workerTaskQueue,
            long maxConcurrentWorkflows,
            long maxConcurrentActivities) {

        if (!initialized.compareAndSet(false, true)) {
            LOGGER.debug("Singleton worker already initialized");
            return null;
        }

        try {
            String serverUrl = url.getValue();
            String ns = namespace.getValue();
            taskQueue = workerTaskQueue.getValue();

            LOGGER.debug("Initializing singleton workflow worker - URL: {}, Namespace: {}, TaskQueue: {}",
                    serverUrl, ns, taskQueue);

            // Create service stubs (connection to Temporal server)
            WorkflowServiceStubsOptions stubsOptions = WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(serverUrl)
                    .build();
            serviceStubs = WorkflowServiceStubs.newServiceStubs(stubsOptions);

            // Create workflow client
            io.temporal.client.WorkflowClientOptions clientOptions =
                    io.temporal.client.WorkflowClientOptions.newBuilder()
                            .setNamespace(ns)
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

            // Initialize the SearchAttributeRegistry for correlation key support
            SearchAttributeRegistry.initialize(serviceStubs, ns, serverUrl);

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
     * Register a workflow process with the singleton worker.
     * Called from Ballerina code for each workflow process.
     *
     * @param env Environment for capturing runtime
     * @param processFunction The Ballerina process function pointer
     * @param processName The name of the process (workflow type)
     * @param activities Optional map of activity functions
     * @return true on success, error on failure
     */
    public static Object registerProcessWithWorker(
            Environment env,
            BFunctionPointer processFunction,
            BString processName,
            Object activities) {

        // Use init() method's already set values, or set them with synchronization
        synchronized (WorkflowWorkerNative.class) {
            if (ballerinaRuntime == null) {
                ballerinaRuntime = env.getRuntime();
            }
            if (workflowModule == null) {
                workflowModule = env.getCurrentModule();
            }
        }

        if (!initialized.get()) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow worker not initialized. Module initialization may have failed."));
        }

        if (singletonWorker == null) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Worker is null. Call initSingletonWorker first."));
        }

        try {
            String workflowType = processName.getValue();
            LOGGER.debug("Registering process: {}", workflowType);

            // Check for duplicate registration
            if (PROCESS_REGISTRY.containsKey(workflowType)) {
                return ErrorCreator.createError(
                        StringUtils.fromString("Process with name '" + workflowType + "' is already registered"));
            }

            // Store the process function in the process registry
            PROCESS_REGISTRY.put(workflowType, processFunction);

            // Register search attributes for correlation keys (readonly fields in input type)
            RecordType inputRecordType = EventExtractor.getInputRecordType(processFunction);
            if (inputRecordType != null) {
                SearchAttributeRegistry.registerFromRecordType(inputRecordType, workflowType);
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

            // Extract and register events from the process function signature
            List<EventInfo> events = EventExtractor.extractEvents(processFunction, workflowType);
            if (!events.isEmpty()) {
                List<String> eventNames = new ArrayList<>();
                for (EventInfo event : events) {
                    eventNames.add(event.fieldName());
                }
                EVENT_REGISTRY.put(workflowType, eventNames);
                LOGGER.debug("Registered {} events for process: {}", eventNames.size(), workflowType);
            }

            // Register dynamic workflow implementation ONCE
            if (dynamicWorkflowRegistered.compareAndSet(false, true)) {
                singletonWorker.registerWorkflowImplementationTypes(BallerinaWorkflowAdapter.class);
                LOGGER.debug("Registered dynamic workflow adapter");
            }

            // Register dynamic activity implementation ONCE
            if (dynamicActivityRegistered.compareAndSet(false, true)) {
                singletonWorker.registerActivitiesImplementations(new BallerinaActivityAdapter());
                LOGGER.debug("Registered dynamic activity adapter");
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to register process {}: {}", processName.getValue(), e.getMessage(), e);
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to register process: " + e.getMessage()));
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

            // Start the worker factory in a background thread
            Thread workerThread = new Thread(() -> {
                try {
                    workerFactory.start();
                } catch (Exception e) {
                    LOGGER.error("Worker factory failed: {}", e.getMessage(), e);
                }
            }, "temporal-worker-" + taskQueue);

            workerThread.setDaemon(false);
            workerThread.start();

            // Give it a moment to initialize
            Thread.sleep(100);

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

            if (workerFactory != null) {
                workerFactory.shutdown();
                workerFactory.awaitTermination(10, TimeUnit.SECONDS);
            }

            if (serviceStubs != null) {
                serviceStubs.shutdown();
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
                            "Please call registerProcess() for this workflow.", workflowType);
                    LOGGER.error("[JWorkflowAdapter] {}", errorMsg);

                    io.temporal.failure.ApplicationFailure failure =
                            io.temporal.failure.ApplicationFailure.newFailure(
                                    errorMsg,
                                    "BallerinaWorkflowNotRegistered"
                            );
                    failure.setNonRetryable(true);
                    throw failure;
                }

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Found registered workflow: {}", workflowType);
                }

                // Extract workflow arguments from EncodedValues
                Object[] workflowArgs = extractWorkflowArguments(args);

                // Upsert correlation keys as Search Attributes for workflow discovery
                // Search attributes are registered automatically during process registration
                if (processFunction != null && workflowArgs.length > 0) {
                    Object firstArg = workflowArgs[0];
                    if (firstArg instanceof BMap) {
                        @SuppressWarnings("unchecked")
                        BMap<BString, Object> inputMap = (BMap<BString, Object>) firstArg;
                        upsertCorrelationSearchAttributes(inputMap, isReplaying);
                    }
                }

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
                    String errorMsg = err.getMessage();
                    Object errorDetails = convertBallerinaToJavaType(err.getDetails());

                    if (!isReplaying) {
                        LOGGER.error("[JWorkflowAdapter] Workflow {} returned error: {}", workflowType, errorMsg);
                    }

                    // Build a Ballerina-friendly error message
                    String ballerinaErrorMsg = String.format("Workflow '%s' failed: %s", workflowType, errorMsg);

                    // Create ApplicationFailure
                    io.temporal.failure.ApplicationFailure failure =
                            io.temporal.failure.ApplicationFailure.newFailure(
                                    ballerinaErrorMsg,
                                    "BallerinaWorkflowError",
                                    errorDetails
                            );

                    // Mark as non-retryable
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
                e.printStackTrace(System.err);

                // Create detailed error message with exception type and message
                String detailedErrorMsg = String.format("Workflow '%s' encountered an error: %s - %s",
                        workflowType, e.getClass().getSimpleName(), e.getMessage());

                io.temporal.failure.ApplicationFailure failure =
                        io.temporal.failure.ApplicationFailure.newFailure(
                                detailedErrorMsg,
                                "BallerinaWorkflowExecutionError"
                        );
                failure.setNonRetryable(true);

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
         * Upsert correlation keys from workflow input as Temporal Search Attributes.
         * <p>
         * This enables workflow discovery by correlation keys using Temporal's visibility APIs.
         * Readonly fields from the input record become search attributes with "Correlation" prefix.
         *
         * @param inputMap the workflow input as a Ballerina map
         * @param isReplaying whether the workflow is replaying
         */
        private void upsertCorrelationSearchAttributes(BMap<BString, Object> inputMap, boolean isReplaying) {
            try {
                // Extract correlation keys from the input
                Map<String, Object> correlationKeys = CorrelationExtractor.extractCorrelationKeysFromMap(inputMap);

                if (correlationKeys.isEmpty()) {
                    return;
                }

                if (!isReplaying) {
                    LOGGER.debug("[JWorkflowAdapter] Upserting {} correlation search attributes for workflow {}",
                            correlationKeys.size(), workflowType);
                }

                // Build search attribute updates - each upsert is wrapped in try-catch
                // to handle cases where search attributes are not registered on the server
                for (Map.Entry<String, Object> entry : correlationKeys.entrySet()) {
                    String keyName = "Correlation" + CorrelationExtractor.capitalize(entry.getKey());
                    Object value = entry.getValue();

                    try {
                        // Upsert based on value type
                        if (value instanceof String) {
                            SearchAttributeKey<String> key = SearchAttributeKey.forKeyword(keyName);
                            Workflow.upsertTypedSearchAttributes(key.valueSet((String) value));
                        } else if (value instanceof Long) {
                            SearchAttributeKey<Long> key = SearchAttributeKey.forLong(keyName);
                            Workflow.upsertTypedSearchAttributes(key.valueSet((Long) value));
                        } else if (value instanceof Boolean) {
                            SearchAttributeKey<Boolean> key = SearchAttributeKey.forBoolean(keyName);
                            Workflow.upsertTypedSearchAttributes(key.valueSet((Boolean) value));
                        } else if (value instanceof Double) {
                            SearchAttributeKey<Double> key = SearchAttributeKey.forDouble(keyName);
                            Workflow.upsertTypedSearchAttributes(key.valueSet((Double) value));
                        } else {
                            // Default to keyword for other types
                            SearchAttributeKey<String> key = SearchAttributeKey.forKeyword(keyName);
                            Workflow.upsertTypedSearchAttributes(key.valueSet(String.valueOf(value)));
                        }
                    } catch (Exception e) {
                        // Search attribute not registered - log at debug level and continue
                        // This is expected when running against servers without pre-registered attributes
                        if (!isReplaying) {
                            LOGGER.debug("[JWorkflowAdapter] Search attribute {} not available: {}",
                                    keyName, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                // Log but don't fail the workflow - correlation is optional enhancement
                LOGGER.warn("[JWorkflowAdapter] Failed to upsert correlation search attributes: {}",
                        e.getMessage());
            }
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
                                "BallerinaModuleNotInitialized"
                        );
                failure.setNonRetryable(true);
                throw failure;
            }

            // Create a proper ContextInfo object from WorkflowContextNative
            // This is what the native methods expect as the context handle
            io.temporal.workflow.WorkflowInfo temporalInfo = Workflow.getInfo();
            Object contextInfo = WorkflowContextNative.createContext(
                    temporalInfo.getWorkflowId(),
                    temporalInfo.getWorkflowType(),
                    new HashMap<>() // correlation data
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
     * Dynamic activity implementation that routes activity calls to registered Ballerina functions.
     * Uses Temporal's DynamicActivity interface for true dynamic routing without predefined method
     * signatures.
     */
    public static class BallerinaActivityAdapter implements DynamicActivity {

        @Override
        public Object execute(EncodedValues args) {
            // Get activity name from Temporal's Activity.getExecutionContext()
            io.temporal.activity.ActivityExecutionContext activityContext =
                    io.temporal.activity.Activity.getExecutionContext();
            String activityName = activityContext.getInfo().getActivityType();

            // Look up the registered Ballerina function for this activity
            BFunctionPointer activityFunction = ACTIVITY_REGISTRY.get(activityName);
            if (activityFunction == null) {
                String errorMsg = "Activity not registered: " + activityName +
                        ". Available activities: " + ACTIVITY_REGISTRY.keySet();
                throw new RuntimeException(errorMsg);
            }

            // Decode arguments from Temporal - get each argument by index
            // EncodedValues doesn't have a size() method, so try to get up to 10 args
            List<Object> argsList = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                try {
                    Object arg = args.get(i, Object.class);
                    if (arg != null) {
                        argsList.add(arg);
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    // No more arguments
                    break;
                }
            }
            Object[] javaArgs = argsList.toArray();

            // Convert Java arguments to Ballerina types
            Object[] ballerinaArgs = new Object[javaArgs.length];
            for (int i = 0; i < javaArgs.length; i++) {
                ballerinaArgs[i] = convertJavaToBallerinaType(javaArgs[i]);
            }

            // Execute the Ballerina activity function
            FPValue fpValue = (FPValue) activityFunction;
            fpValue.metadata = new StrandMetadata(true, fpValue.metadata.properties());
            Object result = activityFunction.call(ballerinaRuntime, ballerinaArgs);

            // Convert result back to Java types for Temporal
            // BError will be converted to a serializable error representation

            return convertBallerinaToJavaType(result);
        }
    }
}
