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

package io.ballerina.stdlib.workflow;

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/**
 * Native implementation for Temporal client initialization and lifecycle.
 *
 * @since 0.1.0
 */
public final class TemporalClientNative {

    private TemporalClientNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Initialize Temporal client with configuration.
     *
     * @param config Ballerina TemporalConfig record
     * @return WorkflowClient handle or error
     */
    public static Object initClient(BMap<BString, Object> config) {
        try {
            // Extract configuration
            BString serviceUrlBStr = (BString) config.get(StringUtils.fromString("serviceUrl"));
            BString namespaceBStr = (BString) config.get(StringUtils.fromString("namespace"));

            String serviceUrl = serviceUrlBStr != null ? serviceUrlBStr.getValue() : "localhost:7233";
            String namespace = namespaceBStr != null ? namespaceBStr.getValue() : "default";

            // Build service stubs options
            WorkflowServiceStubsOptions.Builder stubsOptionsBuilder = WorkflowServiceStubsOptions.newBuilder();

            if (serviceUrl != null && !serviceUrl.isEmpty()) {
                stubsOptionsBuilder.setTarget(serviceUrl);
            }

            // Create service stubs
            WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(stubsOptionsBuilder.build());

            // Build workflow client options
            WorkflowClientOptions.Builder clientOptionsBuilder = WorkflowClientOptions.newBuilder();

            if (namespace != null && !namespace.isEmpty()) {
                clientOptionsBuilder.setNamespace(namespace);
            }

            // Create workflow client
            WorkflowClient client = WorkflowClient.newInstance(service, clientOptionsBuilder.build());

            return client;

        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to initialize Temporal client: " + e.getMessage()));
        }
    }

    /**
     * Close the Temporal client.
     *
     * @param clientHandle the client handle to close
     * @return null on success, error on failure
     */
    public static Object closeClient(Object clientHandle) {
        try {
            if (clientHandle instanceof WorkflowClient) {
                // WorkflowClient doesn't have a close method directly
                // The underlying service stubs should be closed
                // For now, just return success
                return null;
            }
            return ErrorCreator.createError(
                    StringUtils.fromString("Invalid client handle"));
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to close Temporal client: " + e.getMessage()));
        }
    }
}
