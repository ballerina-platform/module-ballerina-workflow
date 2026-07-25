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

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.Optional;

/**
 * Validation task for {@code workflow:run} calls and direct @Workflow function calls.
 * <p>
 * Validates:
 * <ul>
 *   <li>The first argument of {@code workflow:run} is a function with the @Workflow annotation</li>
 *   <li>The {@code input} argument type matches the workflow function's declared input
 *       parameter type (any {@code anydata} subtype, including {@code string}, records, etc.)</li>
 *   <li>No input argument is passed when the workflow function declares no input parameter</li>
 *   <li>@Workflow functions are never invoked directly as normal functions — workflows must be
 *       started via {@code workflow:run}</li>
 * </ul>
 *
 * @since 0.6.0
 */
public class RunCallValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final String INPUT_PARAM_NAME = "input";
    private static final String PROCESS_FUNCTION_PARAM_NAME = "processFunction";

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionCallExpressionNode callNode)) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();

        // Disallow direct calls to @Workflow functions from anywhere.
        // Workflows can only be started through workflow:run.
        if (isDirectWorkflowFunctionCall(callNode, semanticModel)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_136,
                    callNode.functionName().location());
            return;
        }

        if (!WorkflowFunctionCallUtils.isWorkflowModuleFunctionCall(callNode, semanticModel,
                WorkflowConstants.RUN_FUNCTION)) {
            return;
        }

        // workflow:run is a client verb; inside a workflow body a child must be started
        // with ctx->runChildWorkflow so it becomes a true Temporal child workflow.
        if (WorkflowPluginUtils.isInsideWorkflowFunction(callNode, semanticModel)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_138, callNode.location(),
                    WorkflowConstants.RUN_FUNCTION, WorkflowConstants.RUN_CHILD_WORKFLOW_METHOD);
            return;
        }
        validateRunCall(callNode, context);
    }

    /**
     * Returns {@code true} when the call directly invokes a function carrying the @Workflow annotation.
     * For example, {@code orderProcess("input")}.
     */
    private boolean isDirectWorkflowFunctionCall(FunctionCallExpressionNode callNode,
                                                 SemanticModel semanticModel) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return false;
        }
        FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
        return WorkflowPluginUtils.hasWorkflowAnnotation(functionSymbol,
                WorkflowConstants.PROCESS_ANNOTATION);
    }

    /**
     * Validates a {@code workflow:run(processFunction, input)} call.
     */
    private void validateRunCall(FunctionCallExpressionNode callNode, SyntaxNodeAnalysisContext context) {
        WorkflowPluginUtils.validateWorkflowCallInput(callNode.arguments(), PROCESS_FUNCTION_PARAM_NAME,
                INPUT_PARAM_NAME, context.semanticModel(), new WorkflowPluginUtils.WorkflowCallInputListener() {
                    @Override
                    public void onNonWorkflowTarget(Location location) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_130, location,
                                WorkflowConstants.RUN_FUNCTION);
                    }

                    @Override
                    public void onUnexpectedInput(Location location, String workflowName) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_132, location, workflowName);
                    }

                    @Override
                    public void onInputTypeMismatch(Location location, String workflowName,
                                                    String declaredTypeSignature, String actualDescription) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_131, location,
                                workflowName, declaredTypeSignature, actualDescription);
                    }
                });
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic,
                                  Location location, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }
}
