/*
 *  Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.stdlib.workflow.runtime.nativeimpl;

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.workflow.runtime.commons.Utils;
import io.ballerina.stdlib.workflow.runtime.commons.model.AbstractRemoteMethod;
import io.ballerina.stdlib.workflow.runtime.commons.model.ExecuteMethod;
import io.ballerina.stdlib.workflow.runtime.commons.model.WorkflowService;
import io.ballerina.stdlib.workflow.runtime.commons.model.WorkflowServiceBuilder;

/**
 * Native implementation for getProviderExternal.
 *
 * @since 0.1.0
 */
public final class HelperFunction {

    public static final String WORKFLOW_METHODS = "WorkflowMethods";
    public static final BString EXECUTE = StringUtils.fromString("execute");
    public static final BString SIGNALS = StringUtils.fromString("signals");
    public static final BString QUERIES = StringUtils.fromString("queries");

    private HelperFunction() {
    }

    public static Object getServiceModel(BObject svc) {
        try {
            WorkflowService ws = WorkflowServiceBuilder.build(svc, ModuleUtils.getPackageIdentifier());

            // Create execute action
            ExecuteMethod executeMethod = ws.executeMethod();
            BMap<BString, Object> executeAction = createWorkflowAction(executeMethod);

            // Create signals map
            BMap<BString, Object> signalsMap = ValueCreator.createMapValue(
                    TypeCreator.createMapType(executeAction.getType()));
            for (var entry : ws.signalMethods().entrySet()) {
                BMap action = createWorkflowAction(entry.getValue());
                signalsMap.put(StringUtils.fromString(entry.getKey()), action);
            }

            // Create queries map
            BMap<BString, Object> queriesMap = ValueCreator.createMapValue(
                    TypeCreator.createMapType(executeAction.getType()));
            for (var entry : ws.queryMethods().entrySet()) {
                BMap<BString, Object> action = createWorkflowAction(entry.getValue());
                queriesMap.put(StringUtils.fromString(entry.getKey()), action);
            }


            // Create the main record
            BMap<BString, Object> result = ValueCreator.createMapValue();
            result.put(EXECUTE, executeAction);
            result.put(SIGNALS, signalsMap);
            result.put(QUERIES, queriesMap);

            return ValueCreator.createRecordValue(ModuleUtils.getPackage(), WORKFLOW_METHODS, result);
        } catch (Exception e) {
            return Utils.createError("WorkflowModelError", "Error while creating workflow model: " + e.getMessage());
        }
    }

    private static BMap<BString, Object> createWorkflowAction(AbstractRemoteMethod method) {

        BMap<BString, Object> action = ValueCreator.createMapValue();
        action.put(StringUtils.fromString("name"), StringUtils.fromString(method.getName()));

        // Parameters
        BMap<BString, Object> paramMap = ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_TYPEDESC));
        for (var param : method.getParameters()) {
            paramMap.put(StringUtils.fromString(param.name), ValueCreator.createTypedescValue(param.type));
        }

        action.put(StringUtils.fromString("parameters"), paramMap);

        // Return type
        action.put(StringUtils.fromString("returnType"), ValueCreator.createTypedescValue(method.getReturnType()));
        return ValueCreator.createRecordValue(ModuleUtils.getPackage(), "WorkflowAction", action);
    }
}
