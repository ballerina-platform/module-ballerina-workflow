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

import io.ballerina.tools.diagnostics.DiagnosticSeverity;

/**
 * Enum representing workflow compiler plugin diagnostic codes.
 * Each enum constant encapsulates the diagnostic code, message, and severity.
 *
 * @since 0.1.0
 */
public enum WorkflowDiagnostic {

    WORKFLOW_100("WORKFLOW_100",
            "@Workflow function's first parameter must be 'workflow:Context' if context is used",
            DiagnosticSeverity.ERROR),
    WORKFLOW_101("WORKFLOW_101",
            "@Workflow function's input parameter must be a subtype of 'anydata'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_102("WORKFLOW_102",
            "@Workflow function's events parameter must be a record type with only future<T> fields",
            DiagnosticSeverity.ERROR),
    WORKFLOW_103("WORKFLOW_103",
            "@Activity function parameters must be subtypes of 'anydata'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_104("WORKFLOW_104",
            "@Activity function return type must be a subtype of 'anydata' or 'error'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_105("WORKFLOW_105",
            "@Workflow function return type must be a subtype of 'anydata' or 'error'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_106("WORKFLOW_106",
            "@Workflow function can have at most 3 parameters: Context, input, and events",
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
                    + "Signal types in the events record should have distinct structures",
            DiagnosticSeverity.WARNING),
    WORKFLOW_113("WORKFLOW_113",
            "Using 'time:utcNow()' inside a @Workflow function is non-deterministic. "
                    + "Use 'ctx.currentTime()' instead",
            DiagnosticSeverity.WARNING),
    WORKFLOW_114("WORKFLOW_114",
            "@Activity function has an unsupported typedesc parameter. "
                    + "Only dependently-typed functions with inferred default '<>' are allowed "
                    + "(e.g., typedesc<anydata> t = <>)",
            DiagnosticSeverity.ERROR),
    WORKFLOW_115("WORKFLOW_115",
            "Multiple wait 'wait { ... }' is not supported for workflow event futures. "
                    + "Use 'wait f1', 'wait f1|f2', or 'workflow:waitForData(futures, minCount)' instead",
            DiagnosticSeverity.ERROR),
    WORKFLOW_116("WORKFLOW_116",
            "Futures passed to 'workflow:waitForData' must come from the workflow function's events record "
                    + "parameter. External futures bypass the workflow event system",
            DiagnosticSeverity.ERROR),
    WORKFLOW_117("WORKFLOW_117",
            "Return type mismatch at position %d in 'workflow:waitForData': "
                    + "expected '%s' but the future at that position carries type '%s'. "
                    + "Tuple element types must match the future types in the same order",
            DiagnosticSeverity.ERROR),
    WORKFLOW_118("WORKFLOW_118",
            "Named worker declarations are not allowed inside @Workflow functions. "
                    + "Workers run on separate strands and bypass the workflow scheduler, breaking determinism",
            DiagnosticSeverity.ERROR),
    WORKFLOW_119("WORKFLOW_119",
            "'fork' statements are not allowed inside @Workflow functions. "
                    + "Use sequences of 'ctx->callActivity()' calls for parallel work instead",
            DiagnosticSeverity.ERROR),
    WORKFLOW_120("WORKFLOW_120",
            "'start' action is not allowed inside @Workflow functions. "
                    + "Use 'ctx->callActivity()' for asynchronous work instead",
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
