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
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.workflow.registry.ActivityRegistry;
import io.ballerina.stdlib.workflow.registry.ProcessRegistry;
import io.ballerina.stdlib.workflow.runtime.WorkflowRuntime;
import io.ballerina.stdlib.workflow.utils.TypesUtil;

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

                    // Register the activity if not already registered
                    ActivityRegistry.getInstance().register(activityName, activityFunction);

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
     * Returns the workflow ID that can be used to track and interact with the workflow.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to execute
     * @param input the input data for the process
     * @return the workflow ID as a string, or an error
     */
    public static Object startProcess(Environment env, BFunctionPointer processFunction, Object input) {
        return env.yieldAndRun(() -> {
            CompletableFuture<Object> balFuture = new CompletableFuture<>();

            WorkflowRuntime.getInstance().getExecutor().execute(() -> {
                try {
                    // Get the process name from the function pointer
                    String processName = processFunction.getType().getName();

                    // Register the process if not already registered
                    ProcessRegistry.getInstance().register(processName, processFunction);

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
     * Events can be used to communicate with running workflows and trigger state changes.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to send the event to
     * @param eventData the event data to send
     * @return true if the event was sent successfully, or an error
     */
    public static Object sendEvent(Environment env, BFunctionPointer processFunction, Object eventData) {
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
     * Native implementation for registerProcess function.
     * <p>
     * Registers a process function with the workflow runtime.
     * This makes the process available for execution when startProcess is called.
     *
     * @param env the Ballerina runtime environment
     * @param processFunction the process function to register
     * @param processName the name to register the process under
     * @return true if registration was successful, or an error
     */
    public static Object registerProcess(Environment env, BFunctionPointer processFunction, BString processName) {
        try {
            String name = processName.getValue();

            // Register the process in the registry
            boolean registered = ProcessRegistry.getInstance().register(name, processFunction);

            if (!registered) {
                return ErrorCreator.createError(
                        StringUtils.fromString("Process with name '" + name + "' is already registered"));
            }

            return true;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to register process: " + e.getMessage()));
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
