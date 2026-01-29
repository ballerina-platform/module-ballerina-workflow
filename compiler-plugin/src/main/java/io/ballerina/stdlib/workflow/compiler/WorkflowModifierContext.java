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

package io.ballerina.stdlib.workflow.compiler;

import java.util.HashMap;
import java.util.Map;

/**
 * Context class that holds information about workflow modifications for a document.
 * <p>
 * Stores information about {@code @Process} annotated functions and their associated
 * {@code @Activity} function calls that need to be transformed.
 *
 * @since 0.1.0
 */
public class WorkflowModifierContext {

    private final Map<String, ProcessFunctionInfo> processInfoMap;

    public WorkflowModifierContext() {
        this.processInfoMap = new HashMap<>();
    }

    /**
     * Adds a process function information to the context.
     *
     * @param processInfo the process function information
     */
    public void addProcessInfo(ProcessFunctionInfo processInfo) {
        this.processInfoMap.put(processInfo.getFunctionName(), processInfo);
    }

    /**
     * Gets all process function information stored in this context.
     *
     * @return map of function name to ProcessFunctionInfo
     */
    public Map<String, ProcessFunctionInfo> getProcessInfoMap() {
        return processInfoMap;
    }
}
