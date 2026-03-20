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

package io.ballerina.lib.workflow.compiler;

/**
 * Constants used by the workflow compiler plugin.
 *
 * @since 0.1.0
 */
public final class WorkflowConstants {

    private WorkflowConstants() {
        // Private constructor to prevent instantiation
    }

    // Package information
    public static final String PACKAGE_ORG = "ballerina";
    public static final String PACKAGE_NAME = "workflow";

    // Submodule names and aliases
    public static final String INTERNAL_MODULE_NAME = "internal";
    public static final String INTERNAL_MODULE_ALIAS = "wfInternal";

    // Annotation names
    public static final String PROCESS_ANNOTATION = "Workflow";
    public static final String ACTIVITY_ANNOTATION = "Activity";

    // Function names
    public static final String CALL_ACTIVITY_FUNCTION = "callActivity";

    // Type names
    public static final String CONTEXT_TYPE = "Context";

    // User data keys
    public static final String MODIFIER_CONTEXT_MAP = "workflow.modifier.context.map";
    public static final String IS_ANALYSIS_COMPLETED = "workflow.analysis.completed";

    // Function names for validation
    public static final String SEND_DATA_FUNCTION = "sendData";
    public static final String RUN_FUNCTION = "run";
    public static final String WAIT_FOR_DATA_FUNCTION = "waitForData";
}
