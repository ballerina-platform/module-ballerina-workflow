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

import io.ballerina.tools.diagnostics.DiagnosticSeverity;

/**
 * Enum representing workflow compiler plugin diagnostic codes.
 * Each enum constant encapsulates the diagnostic code, message, and severity.
 *
 * @since 0.1.0
 */
public enum WorkflowDiagnostic {

    WORKFLOW_100("WORKFLOW_100",
            "@Process function's first parameter must be 'workflow:Context' if context is used",
            DiagnosticSeverity.ERROR),
    WORKFLOW_101("WORKFLOW_101",
            "@Process function's input parameter must be a subtype of 'anydata'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_102("WORKFLOW_102",
            "@Process function's events parameter must be a record type with only future<T> fields",
            DiagnosticSeverity.ERROR),
    WORKFLOW_103("WORKFLOW_103",
            "@Activity function parameters must be subtypes of 'anydata'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_104("WORKFLOW_104",
            "@Activity function return type must be a subtype of 'anydata' or 'error'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_105("WORKFLOW_105",
            "@Process function return type must be a subtype of 'anydata' or 'error'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_106("WORKFLOW_106",
            "@Process function can have at most 3 parameters: Context, input, and events",
            DiagnosticSeverity.ERROR),
    WORKFLOW_107("WORKFLOW_107",
            "The first argument of 'callActivity' must be a function with @Activity annotation",
            DiagnosticSeverity.ERROR),
    WORKFLOW_108("WORKFLOW_108",
            "Direct calls to @Activity functions are not allowed. "
                    + "Use 'ctx->callActivity(functionName, args...)' instead",
            DiagnosticSeverity.ERROR),
    WORKFLOW_109("WORKFLOW_109",
            "Missing required parameter '%s' in callActivity. "
                    + "The activity function requires this parameter",
            DiagnosticSeverity.ERROR),
    WORKFLOW_110("WORKFLOW_110",
            "Extra parameter '%s' in callActivity. "
                    + "The activity function does not have a parameter with this name",
            DiagnosticSeverity.ERROR),
    WORKFLOW_111("WORKFLOW_111",
            "Activity functions with rest parameters are not supported with callActivity. "
                    + "Use explicit parameters instead",
            DiagnosticSeverity.ERROR),
    WORKFLOW_112("WORKFLOW_112",
            "The process '%s' has structurally equivalent signal types '%s' and '%s'. "
                    + "You must provide an explicit signalName parameter to disambiguate",
            DiagnosticSeverity.ERROR),
    WORKFLOW_114("WORKFLOW_114",
            "Signal type '%s' is missing readonly field '%s' required for correlation with process input",
            DiagnosticSeverity.ERROR),
    WORKFLOW_115("WORKFLOW_115",
            "Readonly field '%s' type mismatch: input has '%s', signal '%s' has '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_116("WORKFLOW_116",
            "Process with events must have readonly fields in input for correlation. "
                    + "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')",
            DiagnosticSeverity.ERROR);

    private final String code;
    private final String message;
    private final DiagnosticSeverity severity;

    WorkflowDiagnostic(String code, String message, DiagnosticSeverity severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    /**
     * Get the diagnostic code.
     *
     * @return the diagnostic code string
     */
    public String getCode() {
        return code;
    }

    /**
     * Get the diagnostic message.
     *
     * @return the diagnostic message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Get the message formatted with the given arguments.
     *
     * @param args the arguments to format the message with
     * @return the formatted message
     */
    public String getMessage(Object... args) {
        return String.format(message, args);
    }

    /**
     * Get the diagnostic severity.
     *
     * @return the diagnostic severity
     */
    public DiagnosticSeverity getSeverity() {
        return severity;
    }
}
