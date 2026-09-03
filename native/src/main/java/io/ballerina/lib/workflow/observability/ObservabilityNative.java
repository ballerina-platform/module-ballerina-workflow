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

package io.ballerina.lib.workflow.observability;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.api.values.BString;
import io.temporal.workflow.Workflow;

/**
 * Native implementations backing the {@code workflow.observe} Ballerina submodule.
 *
 * @since 0.9.0
 */
public final class ObservabilityNative {

    private ObservabilityNative() {
    }

    /**
     * Checks whether the current thread is executing inside a workflow context.
     * <p>
     * Used to suppress span recording from workflow bodies: those are replayed
     * deterministically by the durable engine, so client-side spans emitted from
     * within them would be duplicated on every replay.
     *
     * @return {@code true} if inside a workflow execution, {@code false} otherwise
     */
    public static boolean isInsideWorkflowContext() {
        try {
            Workflow.getInfo();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Returns the workflow type name registered for a workflow function.
     *
     * @param processFunction the workflow function pointer
     * @return the workflow type name used by the durable engine
     */
    public static BString workflowTypeNameOf(BFunctionPointer processFunction) {
        String functionName = processFunction.getType().getName();
        return StringUtils.fromString(
                WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX + (functionName == null ? "" : functionName));
    }
}
