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

import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.values.BObject;

/**
 * Abstract base class for remote method implementations in workflow services.
 *
 * @since 0.1.0
 */
public abstract class AbstractRemoteMethod implements RemoteMethod {


    private final String methodName;
    private final FunctionType remoteMethodType;

    /**
     * Constructs an AbstractRemoteMethod.
     *
     * @param methodName the name of the method
     * @param remoteMethodType the function type of the remote method
     * @param object the Ballerina service object
     * @since 0.1.0
     */
    AbstractRemoteMethod(String methodName, FunctionType remoteMethodType, BObject object) {
        this.methodName = methodName;
        this.remoteMethodType = remoteMethodType;
    }

    @Override
    public String getName() {
        return this.methodName;
    }

    @Override
    public Type getReturnType() {
        return remoteMethodType.getReturnType();
    }

    @Override
    public Parameter[] getParameters() {
        return remoteMethodType.getParameters();
    }

}
