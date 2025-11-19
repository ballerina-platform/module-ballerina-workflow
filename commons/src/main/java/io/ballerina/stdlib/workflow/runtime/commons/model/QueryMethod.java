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
import io.ballerina.runtime.api.values.BObject;

/**
 * Represents a query method in a workflow service.
 * Query methods are used to retrieve information from a workflow without modifying its state.
 *
 * @since 0.1.0
 */
public class QueryMethod extends AbstractRemoteMethod {

    /**
     * Constructs a QueryMethod instance.
     *
     * @param methodName the name of the query method
     * @param remoteMethodType the remote method type
     * @param object the Ballerina service object
     * @since 0.1.0
     */
    QueryMethod(String methodName, RemoteMethodType remoteMethodType, BObject object) {
        super(methodName, remoteMethodType, object);
    }
}
