/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import io.ballerina.lib.workflow.TaskKeys;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.util.List;

// What the runtime read from a task's memo while validating a decision; returned to Ballerina as the receipt.
record TaskMemo(String taskName, String parentWorkflowId, String rootWorkflowId, List<String> assignedRoles,
                Object taskInput) {

    // A refusal that still knows which run owns the task, so its span and audit entry can name that run.
    // The parent rides the error's detail; without it the decision would stand outside the run's trace.
    BError refusal(String message) {
        return refusal(parentWorkflowId, rootWorkflowId, message);
    }

    // The same refusal from the memo alone, for the checks that run before the rest of it is read.
    static BError refusal(String parentWorkflowId, String rootWorkflowId, String message) {
        if (parentWorkflowId == null) {
            return ErrorCreator.createError(StringUtils.fromString(message));
        }
        BMap<BString, Object> detail =
                ValueCreator.createMapValue(TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        detail.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentWorkflowId));
        if (rootWorkflowId != null) {
            detail.put(StringUtils.fromString("rootWorkflowId"), StringUtils.fromString(rootWorkflowId));
        }
        return ErrorCreator.createError(StringUtils.fromString(message), detail);
    }

    // The receipt map<anydata>: taskName, parentWorkflowId, rootWorkflowId, taskInput (when known) and
    // assignedRoles as string[].
    BMap<BString, Object> toReceipt() {
        BMap<BString, Object> receipt =
                ValueCreator.createMapValue(TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        if (taskName != null) {
            receipt.put(StringUtils.fromString(TaskKeys.TASK_NAME), StringUtils.fromString(taskName));
        }
        if (parentWorkflowId != null) {
            receipt.put(StringUtils.fromString(TaskKeys.PARENT_WORKFLOW_ID), StringUtils.fromString(parentWorkflowId));
        }
        if (rootWorkflowId != null) {
            receipt.put(StringUtils.fromString("rootWorkflowId"), StringUtils.fromString(rootWorkflowId));
        }
        if (taskInput != null) {
            receipt.put(StringUtils.fromString(TaskKeys.TASK_INPUT), TypesUtil.convertJavaToBallerinaType(taskInput));
        }
        BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
        for (String role : assignedRoles) {
            roles.append(StringUtils.fromString(role));
        }
        receipt.put(StringUtils.fromString("assignedRoles"), roles);
        return receipt;
    }
}
