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
import io.ballerina.runtime.api.utils.StringUtils;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Native implementation for workflow context operations.
 * Provides workflow-specific operations like sleep and state queries.
 *
 * <p>ARCHITECTURE NOTES:
 * <ul>
 *   <li>Per-Instance ServiceObject: Each workflow execution gets its own ServiceObject instance
 *       (created in WorkflowWorkerNative.createServiceInstance()) to avoid state sharing between
 *       workflow instances, including during replay scenarios.</li>
 *   <li>Context objects are created per workflow execution and hold workflow-specific information.</li>
 *   <li>Activity execution is done via module-level callActivity() function.</li>
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
                return StringUtils.fromString(((ContextInfo) contextHandle).getWorkflowId());
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
                return StringUtils.fromString(((ContextInfo) contextHandle).getWorkflowType());
            }
            io.temporal.workflow.WorkflowInfo info = Workflow.getInfo();
            return StringUtils.fromString(info.getWorkflowType());
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to get workflow type: " + e.getMessage()));
        }
    }

    /**
     * Context information holder.
     * Stores workflow-specific context information.
     */
    public static final class ContextInfo {
        private final String workflowId;
        private final String workflowType;
        private final Map<String, Object> correlationData;

        /**
         * Creates a new ContextInfo.
         *
         * @param workflowId the workflow ID
         * @param workflowType the workflow type
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
        public String getWorkflowId() {
            return workflowId;
        }

        /**
         * Gets the workflow type.
         *
         * @return the workflow type
         */
        public String getWorkflowType() {
            return workflowType;
        }

        /**
         * Gets the correlation data.
         *
         * @return the correlation data map
         */
        public Map<String, Object> getCorrelationData() {
            return correlationData;
        }

        /**
         * Adds correlation data.
         *
         * @param key the key
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
