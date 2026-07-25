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
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.Optional;

/**
 * Validation task for the child-workflow composition methods on {@code workflow:Context}:
 * {@code ctx->runChildWorkflow(childWorkflow, input)} and
 * {@code ctx->callWorkflow(childWorkflow, input)}.
 * <p>
 * Validates:
 * <ul>
 *   <li>The first argument is a function with the @Workflow annotation ({@code WORKFLOW_139})</li>
 *   <li>The {@code input} argument type matches the child workflow function's declared input
 *       parameter type ({@code WORKFLOW_140})</li>
 *   <li>No input argument is passed when the child workflow function declares no input
 *       parameter ({@code WORKFLOW_141})</li>
 * </ul>
 *
 * @since 0.9.0
 */
public class ChildWorkflowCallValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final String CHILD_WORKFLOW_PARAM_NAME = "childWorkflow";
    private static final String INPUT_PARAM_NAME = "input";

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof RemoteMethodCallActionNode callNode)) {
            return;
        }
        String methodName = callNode.methodName().name().text();
        if (!WorkflowConstants.RUN_CHILD_WORKFLOW_METHOD.equals(methodName)
                && !WorkflowConstants.CALL_WORKFLOW_METHOD.equals(methodName)) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();
        Optional<TypeSymbol> receiverType = semanticModel.typeOf(callNode.expression());
        if (receiverType.isEmpty() || !WorkflowPluginUtils.isContextType(receiverType.get())) {
            return;
        }
        validateChildWorkflowCall(callNode, methodName, context);
    }

    /**
     * Validates a {@code ctx->runChildWorkflow(childWorkflow, input)} or
     * {@code ctx->callWorkflow(childWorkflow, input)} call.
     */
    private void validateChildWorkflowCall(RemoteMethodCallActionNode callNode, String methodName,
                                           SyntaxNodeAnalysisContext context) {
        SemanticModel semanticModel = context.semanticModel();
        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        if (arguments.isEmpty()) {
            return;
        }

        ExpressionNode childFuncExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 0, CHILD_WORKFLOW_PARAM_NAME);
        if (childFuncExpr == null) {
            return;
        }

        Optional<FunctionSymbol> workflowFuncOpt =
                WorkflowPluginUtils.getWorkflowFunctionSymbol(childFuncExpr, semanticModel);
        if (workflowFuncOpt.isEmpty()) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_139, childFuncExpr.location(), methodName);
            return;
        }
        FunctionSymbol workflowFunc = workflowFuncOpt.get();
        String workflowName = workflowFunc.getName().orElse("");

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
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_141, inputExpr.location(),
                        workflowName, methodName);
            }
            return;
        }
        TypeSymbol declaredInputType = inputParamOpt.get().typeDescriptor();

        // Constructor expressions (mapping/list/table) are typed by their contextually
        // expected type (`anydata` here), so their static type cannot be compared with
        // subtypeOf without false positives. Validate the shape only; member-level
        // conversion is handled by the runtime.
        if (inputExpr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR
                || inputExpr.kind() == SyntaxKind.LIST_CONSTRUCTOR
                || inputExpr.kind() == SyntaxKind.TABLE_CONSTRUCTOR) {
            if (!WorkflowPluginUtils.canAcceptConstructorExpression(declaredInputType, inputExpr.kind())) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_140, inputExpr.location(),
                        methodName, workflowName, declaredInputType.signature(),
                        WorkflowPluginUtils.describeConstructorExpression(inputExpr.kind()));
            }
            return;
        }

        // Explicit nil is only valid when the declared input type is nilable; the
        // subtype check below covers that (nil is a subtype of any nilable type).
        if (!inputType.subtypeOf(declaredInputType)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_140, inputExpr.location(),
                    methodName, workflowName, declaredInputType.signature(), inputType.signature());
        }
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic,
                                  Location location, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }
}
