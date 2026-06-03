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

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.ModuleUtils;
import io.ballerina.lib.workflow.runtime.WorkflowRuntime;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

/**
 * Native implementations for the {@code workflow.management} submodule.
 * <p>
 * Provides inspection and lifecycle-control operations:
 * <ul>
 *   <li>{@link #getWorkflowInfo} – describe a single execution</li>
 *   <li>{@link #listWorkflowDefinitions} – list registered workflow types</li>
 *   <li>{@link #suspendWorkflow} – send {@code __wf_suspend} signal</li>
 *   <li>{@link #resumeWorkflow} – send {@code __wf_resume} signal</li>
 * </ul>
 *
 * @since 0.4.0
 */
public final class ManagementNative {

    private ManagementNative() {
        // Utility class — prevent instantiation
    }

    /**
     * Returns current execution info for a workflow without waiting for completion.
     * Delegates to {@link WorkflowNative#getWorkflowInfo(BString)}.
     *
     * @param workflowId the workflow ID
     * @return a Ballerina {@code WorkflowExecutionInfo} record or an error
     */
    public static Object getWorkflowInfo(BString workflowId) {
        return WorkflowNative.getWorkflowInfo(workflowId);
    }

    /**
     * Lists registered workflow types, for use in the workflow launcher UI.
     * Returns one entry per registered workflow function. The {@code inputSchema} field is
     * {@code null} until the compiler plugin generates JSON Schema at build time.
     *
     * @return a Ballerina {@code WorkflowDefinition[]} or an error
     */
    public static Object listWorkflowDefinitions() {
        try {
            RecordType defType = (RecordType) ValueCreator.createRecordValue(
                    ModuleUtils.getManagementModule(), "WorkflowDefinition").getType();
            BArray result = ValueCreator.createArrayValue(TypeCreator.createArrayType(defType));

            for (String workflowType : WorkflowWorkerNative.getProcessRegistry().keySet()) {
                BMap<BString, Object> def = ValueCreator.createRecordValue(
                        ModuleUtils.getManagementModule(), "WorkflowDefinition");
                def.put(StringUtils.fromString("workflowType"),
                        StringUtils.fromString(workflowType));
                def.put(StringUtils.fromString("inputSchema"), null);
                result.append(def);
            }

            return result;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to list workflow definitions: " + e.getMessage()));
        }
    }

    /**
     * Requests a running workflow to suspend by sending a {@code __wf_suspend} signal.
     *
     * @param workflowId the workflow ID to suspend
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object suspendWorkflow(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(
                    workflowId.getValue(), "__wf_suspend", null);
            if (!delivered) {
            return ErrorCreator.createError(
                StringUtils.fromString("Failed to suspend workflow: workflow not found: "
                    + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to suspend workflow: " + e.getMessage()));
        }
    }

    /**
     * Resumes a previously suspended workflow by sending a {@code __wf_resume} signal.
     *
     * @param workflowId the workflow ID to resume
     * @return {@code null} on success, or a Ballerina error
     */
    public static Object resumeWorkflow(BString workflowId) {
        try {
            boolean delivered = WorkflowRuntime.getInstance().sendSignalToWorkflow(
                    workflowId.getValue(), "__wf_resume", null);
            if (!delivered) {
            return ErrorCreator.createError(
                StringUtils.fromString("Failed to resume workflow: workflow not found: "
                    + workflowId.getValue()));
            }
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to resume workflow: " + e.getMessage()));
        }
    }
}
