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
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
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
        SemanticModel semanticModel = context.semanticModel();
        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        if (arguments.isEmpty()) {
            return;
        }

        // Resolve the processFunction argument (first positional or named).
        ExpressionNode processFuncExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 0, PROCESS_FUNCTION_PARAM_NAME);
        if (processFuncExpr == null) {
            return;
        }

        Optional<FunctionSymbol> workflowFuncOpt =
                WorkflowPluginUtils.getWorkflowFunctionSymbol(processFuncExpr, semanticModel);
        if (workflowFuncOpt.isEmpty()) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_130, processFuncExpr.location(),
                    WorkflowConstants.RUN_FUNCTION);
            return;
        }
        FunctionSymbol workflowFunc = workflowFuncOpt.get();
        String workflowName = workflowFunc.getName().orElse("");

        // Resolve the input argument (second positional or named `input`).
        ExpressionNode inputExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 1, INPUT_PARAM_NAME);
        if (inputExpr == null) {
            return;
        }

        Optional<TypeSymbol> inputTypeOpt = semanticModel.typeOf(inputExpr);
        if (inputTypeOpt.isEmpty()) {
            return;
        }
        TypeSymbol inputType = inputTypeOpt.get();
        boolean isNilInput = WorkflowPluginUtils.resolveTypeReference(inputType).typeKind() == TypeDescKind.NIL;

        Optional<ParameterSymbol> inputParamOpt = WorkflowPluginUtils.getInputParameter(workflowFunc);
        if (inputParamOpt.isEmpty()) {
            // Explicit nil means "no input" and is fine for a no-input workflow;
            // any other value has nowhere to go.
            if (!isNilInput) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_132, inputExpr.location(),
                        workflowName);
            }
            return;
        }
        TypeSymbol declaredInputType = inputParamOpt.get().typeDescriptor();

        // Constructor expressions (mapping/list/table) are typed by their contextually
        // expected type (`anydata` here), so their static type cannot be compared with
        // subtypeOf without false positives. Validate the shape only: the declared input
        // type must be able to accept a value of the constructor's kind. Member-level
        // conversion is handled by the runtime.
        if (inputExpr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR
                || inputExpr.kind() == SyntaxKind.LIST_CONSTRUCTOR
                || inputExpr.kind() == SyntaxKind.TABLE_CONSTRUCTOR) {
            if (!WorkflowPluginUtils.canAcceptConstructorExpression(declaredInputType, inputExpr.kind())) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_131, inputExpr.location(),
                        workflowName, declaredInputType.signature(),
                        WorkflowPluginUtils.describeConstructorExpression(inputExpr.kind()));
            }
            return;
        }

        // Explicit nil is only valid when the declared input type is nilable; the
        // subtype check below covers that (nil is a subtype of any nilable type).
        if (!inputType.subtypeOf(declaredInputType)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_131, inputExpr.location(),
                    workflowName, declaredInputType.signature(), inputType.signature());
        }
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic,
                                  Location location, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }
}
