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

import java.util.Map;

/**
 * Exception thrown when attempting to start a workflow with correlation keys
 * that match an already running workflow.
 *
 * @since 0.1.0
 */
public class DuplicateWorkflowException extends RuntimeException {

    private final String existingWorkflowId;
    private final String processName;
    private final Map<String, Object> correlationKeys;

    /**
     * Creates a new DuplicateWorkflowException.
     *
     * @param message           the error message
     * @param existingWorkflowId the ID of the existing workflow
     * @param processName       the name of the process
     * @param correlationKeys   the correlation key values that caused the conflict
     */
    public DuplicateWorkflowException(String message, String existingWorkflowId, 
            String processName, Map<String, Object> correlationKeys) {
        super(message);
        this.existingWorkflowId = existingWorkflowId;
        this.processName = processName;
        this.correlationKeys = correlationKeys;
    }

    /**
     * Gets the ID of the existing workflow that caused the conflict.
     *
     * @return the existing workflow ID
     */
    public String getExistingWorkflowId() {
        return existingWorkflowId;
    }

    /**
     * Gets the name of the process.
     *
     * @return the process name
     */
    public String getProcessName() {
        return processName;
    }

    /**
     * Gets the correlation key values that caused the conflict.
     *
     * @return the correlation keys map
     */
    public Map<String, Object> getCorrelationKeys() {
        return correlationKeys;
    }
}
