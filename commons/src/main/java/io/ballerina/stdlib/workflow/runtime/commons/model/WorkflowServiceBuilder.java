/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
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
package io.ballerina.stdlib.workflow.runtime.commons.model;

import io.ballerina.runtime.api.types.RemoteMethodType;
import io.ballerina.runtime.api.types.ServiceType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;

import java.util.HashMap;
import java.util.Map;

import static io.ballerina.stdlib.workflow.runtime.commons.Constants.COLON;
import static io.ballerina.stdlib.workflow.runtime.commons.Constants.QUERY_ANNOTATION;
import static io.ballerina.stdlib.workflow.runtime.commons.Constants.SIGNAL_ANNOTATION;

/**
 * Builder class for constructing WorkflowService instances from Ballerina service objects.
 *
 * @since 0.1.0
 */
public class WorkflowServiceBuilder {

    private WorkflowServiceBuilder() {
    }

    /**
     * Builds a WorkflowService instance from a Ballerina service object.
     *
     * @param svc the Ballerina service object
     * @param packageIdentifier the package identifier for annotation matching
     * @return the constructed WorkflowService instance
     * @throws IllegalArgumentException if the provided object is not a service type
     * @throws IllegalStateException if the service is missing the execute method or has unsupported methods
     * @since 0.1.0
     */
    public static WorkflowService build(BObject svc, String packageIdentifier) {
        ServiceType serviceType;
        if (svc.getOriginalType() instanceof ServiceType svcType) {
            serviceType = svcType;
        } else {
            throw new IllegalArgumentException("Provided svc is not a service type");
        }
        RemoteMethodType[] remoteMethods = serviceType.getRemoteMethods();
        ExecuteMethod executeMethod = null;
        Map<String, QueryMethod> queryMethods = new HashMap<>();
        Map<String, SignalMethod> signalMethods = new HashMap<>();
        for (RemoteMethodType remoteMethod : remoteMethods) {
            String methodName = remoteMethod.getName();
            if (methodName.equals("execute")) {
                executeMethod = new ExecuteMethod(remoteMethod.getName(), remoteMethod, svc);
                continue;
            }

            // Check for annotations to identify if it's a query or signal method
            final BString workflowQuery =
                    StringUtils.fromString(packageIdentifier + COLON + QUERY_ANNOTATION);
            final BString workflowSignal =
                    StringUtils.fromString(packageIdentifier + COLON + SIGNAL_ANNOTATION);

            BString[] annotations = remoteMethod.getAnnotations().getKeys();
            boolean isFound = false;
            for (BString annotationKey : annotations) {
                if (annotationKey.equals(workflowQuery)) {
                    QueryMethod queryMethod = new QueryMethod(remoteMethod.getName(), remoteMethod, svc);
                    queryMethods.put(remoteMethod.getName(), queryMethod);
                    isFound = true;
                    break;
                } else if (annotationKey.equals(workflowSignal)) {
                    SignalMethod signalMethod = new SignalMethod(remoteMethod.getName(), remoteMethod, svc);
                    signalMethods.put(remoteMethod.getName(), signalMethod);
                    isFound = true;
                    break;
                }
            }
            if (!isFound) {
                // Here we assume compiler plugin has validated the annotations.
                throw new IllegalStateException(
                        String.format("Not supported workflow method %s", remoteMethod.getName()));
            }
        }

        if (executeMethod == null) {
            throw new IllegalStateException("Not supported workflow service: missing execute method");
        }

        return new WorkflowService(svc, executeMethod, signalMethods, queryMethods);
    }
}
