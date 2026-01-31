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

package io.ballerina.stdlib.workflow.runtime.nativeimpl;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.MapType;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.workflow.ModuleUtils;
import io.ballerina.stdlib.workflow.runtime.WorkflowRuntime;
import io.ballerina.stdlib.workflow.utils.TypesUtil;
import io.ballerina.stdlib.workflow.worker.WorkflowWorkerNative;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;


/**
 * Native implementation for workflow module functions.
 * <p>
 * This class provides the native implementations for the external functions
 * defined in the Ballerina workflow module:
 * <ul>
 *   <li>callActivity - Execute an activity within a workflow</li>
 *   <li>startProcess - Start a new workflow process</li>
 *   <li>sendEvent - Send an event to a running workflow</li>
 *   <li>registerProcess - Register a process function with the runtime</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class WorkflowNative {

    private WorkflowNative() {
        // Private constructor to prevent instantiation
    }

    /**
     * Native implementation for callActivity function.
     * <p>
     * Executes an activity function within the workflow context.
     * Activities are non-deterministic operations that should only be executed
     * once during workflow execution (not during replay).
     *
     * @param env the Ballerina runtime environment
     * @param activityFunction the activity function to execute
     * @param args the arguments to pass to the activity
     * @return the result of the activity execution or an error
     */
    public static Object callActivity(Environment env, BFunctionPointer activityFunction, Object[] args) {
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    // Get the activity name from the function pointer
                    String activityName = activityFunction.getType().getName();

                    // Convert arguments to Java types for Temporal
                    Object[] javaArgs = new Object[args == null ? 0 : args.length];
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            javaArgs[i] = TypesUtil.convertBallerinaToJavaType(args[i]);
                        }
                    }

                    // Execute the activity through the workflow runtime
                    Object result = WorkflowRuntime.getInstance().executeActivity(activityName, javaArgs);

                    // Convert result back to Ballerina type
                    Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
                    balFuture.complete(ballerinaResult);

                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(
                            StringUtils.fromString("Activity execution failed: " + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Native implementation for startProcess function.
     * <p>
     * Starts a new workflow process with the given input.
     * The input must contain an "id" field for workflow correlation.
     * Returns the workflow ID that can be used to track and interact with the workflow.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to execute
     * @param input the input data for the process (map with "id" field)
     * @return the workflow ID as a string, or an error
     */
    public static Object startProcess(Environment env, BFunctionPointer processFunction, BMap<BString, Object> input) {
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    // Get the process name from the function pointer
                    String processName = processFunction.getType().getName();

                    // Convert input to Java type
                    Object javaInput = TypesUtil.convertBallerinaToJavaType(input);

                    // Start the process through the workflow runtime
                    String workflowId = WorkflowRuntime.getInstance().startProcess(processName, javaInput);

                    balFuture.complete(StringUtils.fromString(workflowId));

                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(
                            StringUtils.fromString("Failed to start process: " + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Native implementation for sendEvent function.
     * <p>
     * Sends an event (signal) to a running workflow process.
     * The event data must contain an "id" field to identify the target workflow.
     * Events can be used to communicate with running workflows and trigger state changes.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to send the event to
     * @param eventData the event data to send (map with "id" field)
     * @return true if the event was sent successfully, or an error
     */
    public static Object sendEvent(Environment env, BFunctionPointer processFunction, BMap<BString, Object> eventData) {
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    // Get the process name from the function pointer
                    String processName = processFunction.getType().getName();

                    // Convert event data to Java type
                    Object javaEventData = TypesUtil.convertBallerinaToJavaType(eventData);

                    // Send the event through the workflow runtime
                    boolean result = WorkflowRuntime.getInstance().sendEvent(processName, javaEventData);

                    balFuture.complete(result);

                } catch (Exception e) {
                    balFuture.complete(ErrorCreator.createError(
                            StringUtils.fromString("Failed to send event: " + e.getMessage())));
                }
            });

            return getResult(balFuture);
        });
    }

    /**
     * Native implementation for getRegisteredWorkflows function.
     * <p>
     * Returns information about all registered workflow processes and their activities.
     * This is useful for testing and introspection.
     *
     * @return a map of process names to their information including activities and events
     */
    public static Object getRegisteredWorkflows() {
        try {
            // Get registries from WorkflowWorkerNative (the singleton worker)
            Map<String, BFunctionPointer> processRegistry = WorkflowWorkerNative.getProcessRegistry();
            Map<String, BFunctionPointer> activityRegistry = WorkflowWorkerNative.getActivityRegistry();
            Map<String, List<String>> eventRegistry = WorkflowWorkerNative.getEventRegistry();

            // Get the ProcessRegistration record type from the workflow module
            RecordType processRegType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getModule(), "ProcessRegistration").getType();

            // Create a typed map for map<ProcessRegistration>
            MapType mapType = TypeCreator.createMapType(processRegType);
            BMap<BString, Object> resultMap = ValueCreator.createMapValue(mapType);

            for (Map.Entry<String, BFunctionPointer> entry : processRegistry.entrySet()) {
                String processName = entry.getKey();

                // Create a ProcessRegistration record
                BMap<BString, Object> processRecord = ValueCreator.createRecordValue(
                        ModuleUtils.getModule(), "ProcessRegistration");
                processRecord.put(StringUtils.fromString("name"), StringUtils.fromString(processName));

                // Find activities for this process (activities are registered as "processName.activityName")
                List<String> processActivities = new ArrayList<>();
                for (String activityName : activityRegistry.keySet()) {
                    if (activityName.startsWith(processName + ".")) {
                        // Extract just the activity name part
                        String shortName = activityName.substring(processName.length() + 1);
                        processActivities.add(shortName);
                    }
                }

                BString[] activityArray = processActivities.stream()
                        .map(StringUtils::fromString)
                        .toArray(BString[]::new);
                BArray activitiesBalArray = ValueCreator.createArrayValue(activityArray);
                processRecord.put(StringUtils.fromString("activities"), activitiesBalArray);

                // Get events for this process from the event registry
                List<String> processEvents = eventRegistry.getOrDefault(processName, new ArrayList<>());
                BString[] eventArray = processEvents.stream()
                        .map(StringUtils::fromString)
                        .toArray(BString[]::new);
                BArray eventsBalArray = ValueCreator.createArrayValue(eventArray);
                processRecord.put(StringUtils.fromString("events"), eventsBalArray);

                resultMap.put(StringUtils.fromString(processName), processRecord);
            }

            return resultMap;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get registered workflows: " + e.getMessage()));
        }
    }

    /**
     * Native implementation for clearRegistry function.
     * <p>
     * Clears all registered processes, activities, and events.
     * This is primarily used for testing.
     *
     * @return true if clearing was successful
     */
    public static Object clearRegistry() {
        try {
            // Clear the WorkflowWorkerNative registries (the source of truth for workflow execution)
            WorkflowWorkerNative.clearRegistries();
            return true;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to clear registry: " + e.getMessage()));
        }
    }

    /**
     * Gets the result from a CompletableFuture, handling exceptions appropriately.
     *
     * @param balFuture the CompletableFuture to get the result from
     * @return the result or throws an error
     */
    private static Object getResult(CompletableFuture<Object> balFuture) {
        try {
            return balFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ErrorCreator.createError(e);
        } catch (Throwable throwable) {
            throw ErrorCreator.createError(throwable);
        }
    }
}
