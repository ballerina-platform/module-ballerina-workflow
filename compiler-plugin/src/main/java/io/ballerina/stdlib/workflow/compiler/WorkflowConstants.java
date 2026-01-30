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

package io.ballerina.stdlib.workflow.compiler;

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

    // Annotation names
    public static final String PROCESS_ANNOTATION = "Process";
    public static final String ACTIVITY_ANNOTATION = "Activity";

    // Function names
    public static final String CALL_ACTIVITY_FUNCTION = "callActivity";
    public static final String REGISTER_PROCESS_FUNCTION = "registerProcess";

    // Type names
    public static final String CONTEXT_TYPE = "Context";

    // User data keys
    public static final String MODIFIER_CONTEXT_MAP = "workflow.modifier.context.map";
    public static final String IS_ANALYSIS_COMPLETED = "workflow.analysis.completed";

    // Diagnostic codes
    public static final String WORKFLOW_100 = "WORKFLOW_100";
    public static final String WORKFLOW_101 = "WORKFLOW_101";
    public static final String WORKFLOW_102 = "WORKFLOW_102";
    public static final String WORKFLOW_103 = "WORKFLOW_103";
    public static final String WORKFLOW_104 = "WORKFLOW_104";
    public static final String WORKFLOW_105 = "WORKFLOW_105";
    public static final String WORKFLOW_106 = "WORKFLOW_106";

    // Diagnostic messages
    public static final String PROCESS_INVALID_CONTEXT_PARAM =
            "@Process function's first parameter must be 'workflow:Context' if context is used";
    public static final String PROCESS_INVALID_INPUT_TYPE =
            "@Process function's input parameter must be a subtype of 'anydata'";
    public static final String PROCESS_INVALID_EVENTS_TYPE =
            "@Process function's events parameter must be a record type";
    public static final String ACTIVITY_INVALID_PARAM_TYPE =
            "@Activity function parameters must be subtypes of 'anydata'";
    public static final String ACTIVITY_INVALID_RETURN_TYPE =
            "@Activity function return type must be a subtype of 'anydata' or 'error'";
    public static final String PROCESS_INVALID_RETURN_TYPE =
            "@Process function return type must be a subtype of 'anydata' or 'error'";
    public static final String PROCESS_TOO_MANY_PARAMS =
            "@Process function can have at most 3 parameters: Context, input, and events";
}
