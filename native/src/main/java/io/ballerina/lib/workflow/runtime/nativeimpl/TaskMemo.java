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

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.util.List;

// What the runtime read from a task's memo while validating a decision; returned to Ballerina as the receipt.
record TaskMemo(String taskName, String parentWorkflowId, List<String> assignedRoles, Object taskInput) {

    // The receipt map<anydata>: taskName, parentWorkflowId, taskInput (when known) and assignedRoles as string[].
    BMap<BString, Object> toReceipt() {
        BMap<BString, Object> receipt =
                ValueCreator.createMapValue(TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        if (taskName != null) {
            receipt.put(StringUtils.fromString("taskName"), StringUtils.fromString(taskName));
        }
        if (parentWorkflowId != null) {
            receipt.put(StringUtils.fromString("parentWorkflowId"), StringUtils.fromString(parentWorkflowId));
        }
        if (taskInput != null) {
            receipt.put(StringUtils.fromString("taskInput"), TypesUtil.convertJavaToBallerinaType(taskInput));
        }
        BArray roles = ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
        for (String role : assignedRoles) {
            roles.append(StringUtils.fromString(role));
        }
        receipt.put(StringUtils.fromString("assignedRoles"), roles);
        return receipt;
    }
}
