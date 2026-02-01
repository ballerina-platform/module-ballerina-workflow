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
    public static final String WORKFLOW_107 = "WORKFLOW_107";
    public static final String WORKFLOW_108 = "WORKFLOW_108";
    public static final String WORKFLOW_109 = "WORKFLOW_109";
    public static final String WORKFLOW_110 = "WORKFLOW_110";
    public static final String WORKFLOW_111 = "WORKFLOW_111";
    public static final String WORKFLOW_112 = "WORKFLOW_112";
    public static final String WORKFLOW_113 = "WORKFLOW_113";
    public static final String WORKFLOW_114 = "WORKFLOW_114";
    public static final String WORKFLOW_115 = "WORKFLOW_115";
    public static final String WORKFLOW_116 = "WORKFLOW_116";

    // Diagnostic messages
    public static final String PROCESS_INVALID_CONTEXT_PARAM =
            "@Process function's first parameter must be 'workflow:Context' if context is used";
    public static final String PROCESS_INVALID_INPUT_TYPE =
            "@Process function's input parameter must be a subtype of 'anydata'";
    public static final String PROCESS_INVALID_EVENTS_TYPE =
            "@Process function's events parameter must be a record type with only future<T> fields";
    public static final String ACTIVITY_INVALID_PARAM_TYPE =
            "@Activity function parameters must be subtypes of 'anydata'";
    public static final String ACTIVITY_INVALID_RETURN_TYPE =
            "@Activity function return type must be a subtype of 'anydata' or 'error'";
    public static final String PROCESS_INVALID_RETURN_TYPE =
            "@Process function return type must be a subtype of 'anydata' or 'error'";
    public static final String PROCESS_TOO_MANY_PARAMS =
            "@Process function can have at most 3 parameters: Context, input, and events";
    public static final String CALL_ACTIVITY_MISSING_ACTIVITY_ANNOTATION =
            "The first argument of 'callActivity' must be a function with @Activity annotation";
    public static final String DIRECT_ACTIVITY_CALL_NOT_ALLOWED =
            "Direct calls to @Activity functions are not allowed. "
                    + "Use 'ctx->callActivity(functionName, args...)' instead";
    public static final String CALL_ACTIVITY_MISSING_REQUIRED_PARAM =
            "Missing required parameter '%s' in callActivity. "
                    + "The activity function requires this parameter";
    public static final String CALL_ACTIVITY_EXTRA_PARAM =
            "Extra parameter '%s' in callActivity. "
                    + "The activity function does not have a parameter with this name";
    public static final String CALL_ACTIVITY_REST_PARAMS_NOT_SUPPORTED =
            "Activity functions with rest parameters are not supported with callActivity. "
                    + "Use explicit parameters instead";
    public static final String SEND_EVENT_AMBIGUOUS_SIGNAL =
            "The process '%s' has structurally equivalent signal types '%s' and '%s'. "
                    + "You must provide an explicit signalName parameter to disambiguate";
    
    // Correlation validation error messages
    public static final String PROCESS_INPUT_MUST_BE_RECORD =
            "@Process function input parameter must be a record type for correlation support";
    public static final String SIGNAL_MISSING_CORRELATION_KEY =
            "Signal type '%s' is missing readonly field '%s' required for correlation with process input";
    public static final String CORRELATION_KEY_TYPE_MISMATCH =
            "Readonly field '%s' type mismatch: input has '%s', signal '%s' has '%s'";
    public static final String CORRELATION_KEY_REQUIRED_FOR_EVENTS =
            "Process with events must have readonly fields in input for correlation. " +
            "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')";
    
    // Function names for validation
    public static final String SEND_EVENT_FUNCTION = "sendEvent";
}
