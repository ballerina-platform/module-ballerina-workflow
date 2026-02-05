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

package io.ballerina.stdlib.workflow.context;

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.stdlib.workflow.utils.TypesUtil;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native implementation for workflow context operations.
 * Provides workflow-specific operations like sleep, state queries, and activity execution.
 *
 * <p>ARCHITECTURE NOTES:
 * <ul>
 *   <li>Per-Instance ServiceObject: Each workflow execution gets its own ServiceObject instance
 *       (created in WorkflowWorkerNative.createServiceInstance()) to avoid state sharing between
 *       workflow instances, including during replay scenarios.</li>
 *   <li>Context objects are created per workflow execution and hold workflow-specific information.</li>
 *   <li>Activity execution is done via ctx.callActivity() remote method on the Context client.</li>
 *   <li>Signal handling is done via Ballerina's wait action with event futures.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class WorkflowContextNative {

    private WorkflowContextNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Execute an activity function within the workflow context.
     * <p>
     * This is the remote method implementation for ctx->callActivity().
     * Activities are non-deterministic operations that should only be executed
     * once during workflow execution (not during replay).
     * <p>
     * The method uses dependent typing - the return type is determined by the typedesc
     * parameter and the result is converted using cloneWithType.
     *
     * @param self the Context BObject (self reference from Ballerina)
     * @param activityFunction the activity function to execute
     * @param args the Parameters record containing arguments to pass to the activity
     * @param typedesc the expected return type descriptor for dependent typing
     * @return the result of the activity execution converted to the expected type, or an error
     */
    public static Object callActivity(BObject self, BFunctionPointer activityFunction, 
            BMap<BString, Object> args, BTypedesc typedesc) {
        try {
            // Get the activity name from the function pointer
            String simpleActivityName = activityFunction.getType().getName();
            
            // Get the current workflow type from Temporal context to build the full activity name
            // Activities are registered as "workflowType.activityName"
            String workflowType = Workflow.getInfo().getWorkflowType();
            String fullActivityName = workflowType + "." + simpleActivityName;

            // Convert Parameters record (BMap) to Java array for Temporal
            // Extract values from the record in order
            List<Object> argsList = new ArrayList<>();
            for (BString key : args.getKeys()) {
                Object value = args.get(key);
                argsList.add(TypesUtil.convertBallerinaToJavaType(value));
            }
            Object[] javaArgs = argsList.toArray();

            // Use Temporal's activity stub to execute the activity
            io.temporal.activity.ActivityOptions activityOptions = io.temporal.activity.ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(java.time.Duration.ofMinutes(5))
                    .build();

            io.temporal.workflow.ActivityStub activityStub =
                    Workflow.newUntypedActivityStub(activityOptions);

            // Execute the activity through Temporal's activity mechanism with the full name
            Object result = activityStub.execute(fullActivityName, Object.class, javaArgs);

            // Convert result back to Ballerina type
            Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(result);
            
            // Use cloneWithType to convert to the expected type from typedesc
            Type targetType = typedesc.getDescribingType();
            return TypesUtil.cloneWithType(ballerinaResult, targetType);

        } catch (io.temporal.failure.ActivityFailure e) {
            // Activity failed - extract the cause
            Throwable cause = e.getCause();
            String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
            return ErrorCreator.createError(
                    StringUtils.fromString("Activity execution failed: " + errorMsg));
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Activity execution failed: " + e.getMessage()));
        }
    }

    /**
     * Create a new context info object.
     * This is called when creating a new workflow context.
     *
     * @param workflowId the workflow ID
     * @param workflowType the workflow type name
     * @param correlationData initial correlation data
     * @return a ContextInfo object
     */
    public static Object createContext(String workflowId, String workflowType, Map<String, Object> correlationData) {
        return new ContextInfo(workflowId, workflowType, correlationData);
    }

    /**
     * Sleep for a specified duration in milliseconds.
     *
     * @param contextHandle Context handle
     * @param millis Duration in milliseconds
     * @return null on success, error on failure
     */
    public static Object sleepMillis(Object contextHandle, long millis) {
        try {
            Workflow.sleep(Duration.ofMillis(millis));
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow sleep failed: " + e.getMessage()));
        }
    }

    /**
     * Check if the workflow is currently replaying history.
     *
     * @param contextHandle Context handle
     * @return true if replaying, false otherwise
     */
    public static boolean isReplaying(Object contextHandle) {
        return Workflow.isReplaying();
    }

    /**
     * Get the workflow ID.
     *
     * @param contextHandle Context handle
     * @return the workflow ID as BString
     */
    public static Object getWorkflowId(Object contextHandle) {
        try {
            if (contextHandle instanceof ContextInfo) {
                return StringUtils.fromString(((ContextInfo) contextHandle).workflowId());
            }
            io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
            return StringUtils.fromString(info.getWorkflowId());
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow ID: " + e.getMessage()));
        }
    }

    /**
     * Get the workflow type.
     *
     * @param contextHandle Context handle
     * @return the workflow type as BString
     */
    public static Object getWorkflowType(Object contextHandle) {
        try {
            if (contextHandle instanceof ContextInfo) {
                return StringUtils.fromString(((ContextInfo) contextHandle).workflowType());
            }
            io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
            return StringUtils.fromString(info.getWorkflowType());
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow type: " + e.getMessage()));
        }
    }

    /**
     * Context information holder. Stores workflow-specific context information.
     *
     * @param workflowId      the workflow ID
     * @param workflowType    the workflow type
     * @param correlationData the correlation data
     */
        public record ContextInfo(String workflowId, String workflowType, Map<String, Object> correlationData) {
            /**
             * Creates a new ContextInfo.
             *
             * @param workflowId      the workflow ID
             * @param workflowType    the workflow type
             * @param correlationData the correlation data
             */
            public ContextInfo(String workflowId, String workflowType, Map<String, Object> correlationData) {
                this.workflowId = workflowId;
                this.workflowType = workflowType;
                this.correlationData = correlationData != null ? new HashMap<>(correlationData) : new HashMap<>();
            }

            /**
             * Gets the workflow ID.
             *
             * @return the workflow ID
             */
            @Override
            public String workflowId() {
                return workflowId;
            }

            /**
             * Gets the workflow type.
             *
             * @return the workflow type
             */
            @Override
            public String workflowType() {
                return workflowType;
            }

            /**
             * Gets the correlation data.
             *
             * @return the correlation data map
             */
            @Override
            public Map<String, Object> correlationData() {
                return correlationData;
            }

            /**
             * Adds correlation data.
             *
             * @param key   the key
             * @param value the value
             */
            public void addCorrelationData(String key, Object value) {
                correlationData.put(key, value);
            }

            /**
             * Gets a correlation value.
             *
             * @param key the key
             * @return the value, or null if not found
             */
            public Object getCorrelationValue(String key) {
                return correlationData.get(key);
            }
        }
}
