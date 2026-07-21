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
            "@Workflow function must declare 'workflow:Context' as its first parameter",
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
                    + "Use 'wait f1', 'wait f1|f2', or 'ctx->await(futures, minCount)' instead",
            DiagnosticSeverity.ERROR),
    WORKFLOW_116("WORKFLOW_116",
            "Futures passed to 'ctx->await' must come from the workflow function's events record "
                    + "parameter. External futures bypass the workflow event system",
            DiagnosticSeverity.ERROR),
    WORKFLOW_117("WORKFLOW_117",
            "Return type mismatch at position %d in 'ctx->await': "
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
            DiagnosticSeverity.ERROR),
    WORKFLOW_121("WORKFLOW_121",
            "Type mismatch in 'ctx->await': expected '%s' but the future carries type '%s'. "
                    + "The return type must match the future's inner type",
            DiagnosticSeverity.ERROR),
    WORKFLOW_122("WORKFLOW_122",
            "Return type of 'ctx->await' with %d futures must be a tuple '[T1, T2, ...]', "
                    + "not scalar type '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_123("WORKFLOW_123",
            "Tuple member at position %d must be nilable (e.g., '%s?') when minCount (%d) "
                    + "is less than the number of futures (%d). "
                    + "Not all futures are guaranteed to complete",
            DiagnosticSeverity.ERROR),
    WORKFLOW_124("WORKFLOW_124",
            "@Activity function parameter '%s' is a 'client object'. "
                    + "Activity arguments of client-object types must be passed as a simple "
                    + "reference to a module-level 'final' or 'configurable' client variable, not '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_125("WORKFLOW_125",
            "Activity argument '%s' references '%s' which is not a module-level "
                    + "'final' (or 'configurable') 'client object' variable. "
                    + "Activity client-object arguments must be globally registered connections",
            DiagnosticSeverity.ERROR),
    WORKFLOW_126("WORKFLOW_126",
            "Activity argument '%s' has type '%s' which is neither a subtype of 'anydata' "
                    + "nor a 'client object'. Only 'anydata' and 'client object' arguments are "
                    + "supported",
            DiagnosticSeverity.ERROR),
    WORKFLOW_127("WORKFLOW_127",
            "Activity simple name collision: '%s' resolves to both '%s' and '%s'. "
                    + "Use unique activity function names or avoid ambiguous qualified references",
            DiagnosticSeverity.ERROR),
    WORKFLOW_128("WORKFLOW_128",
            "Invalid human task name '%s'. Task names must not contain '.' or '|'. "
                    + "The runtime qualifies the name as '<workflowFunction>.<taskName>'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_129("WORKFLOW_129",
            "@Workflow function's events record field '%s' has type 'future<%s>', but '%s' is not a "
                    + "subtype of 'anydata'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_130("WORKFLOW_130",
            "The first argument of 'workflow:%s' must be a function with the @Workflow annotation",
            DiagnosticSeverity.ERROR),
    WORKFLOW_131("WORKFLOW_131",
            "Input type mismatch in 'workflow:run': workflow function '%s' expects input of type "
                    + "'%s', but found '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_132("WORKFLOW_132",
            "Workflow function '%s' does not declare an input parameter, but an input argument "
                    + "was provided to 'workflow:run'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_133("WORKFLOW_133",
            "Workflow function '%s' does not declare an events record parameter, so "
                    + "'workflow:sendData' cannot deliver data to it",
            DiagnosticSeverity.ERROR),
    WORKFLOW_134("WORKFLOW_134",
            "Unknown event name '%s' in 'workflow:sendData': workflow function '%s' has no such "
                    + "field in its events record. Available event names: %s",
            DiagnosticSeverity.ERROR),
    WORKFLOW_135("WORKFLOW_135",
            "Data type mismatch in 'workflow:sendData': event '%s' of workflow function '%s' "
                    + "expects '%s', but found '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_136("WORKFLOW_136",
            "Direct calls to @Workflow functions are not allowed. "
                    + "Use 'workflow:run(workflowFunc, input)' to start a workflow",
            DiagnosticSeverity.ERROR),
    WORKFLOW_137("WORKFLOW_137",
            "The activity function '%s' returns '%s', which is incompatible with the "
                    + "contextually expected type '%s' of this 'callActivity' call",
            DiagnosticSeverity.ERROR),
    WORKFLOW_138("WORKFLOW_138",
            "'workflow:%s' cannot be called inside a workflow function. Use 'ctx->%s' instead, "
                    + "which starts a true child workflow whose lifecycle is tied to this workflow",
            DiagnosticSeverity.ERROR),
    WORKFLOW_139("WORKFLOW_139",
            "The first argument of '%s' must be a function with the @Workflow annotation",
            DiagnosticSeverity.ERROR),
    WORKFLOW_140("WORKFLOW_140",
            "Input type mismatch in '%s': workflow function '%s' expects input of type "
                    + "'%s', but found '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_141("WORKFLOW_141",
            "Workflow function '%s' does not declare an input parameter, but an input argument "
                    + "was provided to '%s'",
            DiagnosticSeverity.ERROR),
    WORKFLOW_148("WORKFLOW_148",
            "Direct AI calls are not allowed inside a workflow: LLM responses are non-deterministic and "
                    + "break workflow replay. Wrap the call in an @workflow:Activity function and invoke it "
                    + "via 'ctx->callActivity' (or register it on the agent via 'registerActivity')",
            DiagnosticSeverity.ERROR),
    WORKFLOW_149("WORKFLOW_149",
            "A 'workflow:DurableAgent' must be declared as a module-level 'final' variable: the compiler "
                    + "generates its Temporal registration at module init from the declaration",
            DiagnosticSeverity.ERROR),
    WORKFLOW_150("WORKFLOW_150",
            "Duplicate capability name '%s' in durable agent '%s': events, tools, activities, human "
                    + "tasks, and peers share one flat namespace",
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
