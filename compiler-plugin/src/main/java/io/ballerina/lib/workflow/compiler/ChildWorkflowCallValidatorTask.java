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
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
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
        WorkflowPluginUtils.validateWorkflowCallInput(callNode.arguments(), CHILD_WORKFLOW_PARAM_NAME,
                INPUT_PARAM_NAME, context.semanticModel(), true,
                new WorkflowPluginUtils.WorkflowCallInputListener() {
                    @Override
                    public void onNonWorkflowTarget(Location location) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_139, location, methodName);
                    }

                    @Override
                    public void onUnexpectedInput(Location location, String workflowName) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_141, location,
                                workflowName, methodName);
                    }

                    @Override
                    public void onInputTypeMismatch(Location location, String workflowName,
                                                    String declaredTypeSignature, String actualDescription) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_140, location,
                                methodName, workflowName, declaredTypeSignature, actualDescription);
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
