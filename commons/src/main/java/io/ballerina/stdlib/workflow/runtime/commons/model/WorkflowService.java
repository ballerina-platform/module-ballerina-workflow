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

import io.ballerina.runtime.api.values.BObject;

import java.util.Map;

/**
 * Represents a workflow service with its execute, signal, and query methods.
 *
 * @param serviceObject the Ballerina service object
 * @param executeMethod the execute method of the workflow
 * @param signalMethods map of signal methods by name
 * @param queryMethods map of query methods by name
 * @since 0.1.0
 */
public record WorkflowService(BObject serviceObject, ExecuteMethod executeMethod,
                              Map<String, SignalMethod> signalMethods, Map<String, QueryMethod> queryMethods) {
}
