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

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Analysis task that detects @Process annotated functions and collects
 * information about @Activity function calls within them.
 *
 * @since 0.1.0
 */
public class ProcessFunctionAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private final Map<String, Object> userData;

    public ProcessFunctionAnalysisTask(Map<String, Object> userData) {
        this.userData = userData;
    }

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionDefinitionNode functionNode)) {
            return;
        }

        // Check if this function has @workflow:Process annotation
        if (!hasProcessAnnotation(functionNode, context.semanticModel())) {
            return;
        }

        String functionName = functionNode.functionName().text();

        // Collect all activity function calls within this process function
        Map<String, String> activityMap = collectActivityCalls(functionNode, context.semanticModel());

        // Store the process function information
        ProcessFunctionInfo processInfo = new ProcessFunctionInfo(functionName, activityMap);
        addToModifierContext(context.documentId(), processInfo);
    }

    private boolean hasProcessAnnotation(FunctionDefinitionNode functionNode, SemanticModel semanticModel) {
        return WorkflowPluginUtils.hasWorkflowAnnotation(functionNode, semanticModel, 
                WorkflowConstants.PROCESS_ANNOTATION);
    }

    private Map<String, String> collectActivityCalls(FunctionDefinitionNode functionNode,
                                                      SemanticModel semanticModel) {
        Map<String, String> activityMap = new HashMap<>();

        // Visit all function calls within the function body
        ActivityCallCollector collector = new ActivityCallCollector(semanticModel, activityMap);
        functionNode.functionBody().accept(collector);

        return activityMap;
    }

    @SuppressWarnings("unchecked")
    private void addToModifierContext(DocumentId documentId, ProcessFunctionInfo processInfo) {
        Map<DocumentId, WorkflowModifierContext> modifierContextMap =
                (Map<DocumentId, WorkflowModifierContext>) userData.get(WorkflowConstants.MODIFIER_CONTEXT_MAP);

        if (modifierContextMap == null) {
            return;
        }

        WorkflowModifierContext modifierContext;
        if (modifierContextMap.containsKey(documentId)) {
            modifierContext = modifierContextMap.get(documentId);
        } else {
            modifierContext = new WorkflowModifierContext();
            modifierContextMap.put(documentId, modifierContext);
        }

        modifierContext.addProcessInfo(processInfo);
    }

    /**
     * Node visitor that collects function calls to @Activity annotated functions.
     * Supports both direct activity calls and ctx->callActivity(activityFunc, args) pattern.
     */
    private static class ActivityCallCollector extends NodeVisitor {
        private final SemanticModel semanticModel;
        private final Map<String, String> activityMap;

        ActivityCallCollector(SemanticModel semanticModel, Map<String, String> activityMap) {
            this.semanticModel = semanticModel;
            this.activityMap = activityMap;
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            String functionName = getFunctionName(callNode);
            if (functionName != null && isActivityFunction(callNode)) {
                // Store both the function name and the function reference (which is the same)
                activityMap.put(functionName, functionName);
            }

            // Continue visiting child nodes
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Visit remote method call actions to detect ctx->callActivity(activityFunc, args) pattern.
         */
        @Override
        public void visit(RemoteMethodCallActionNode remoteCallNode) {
            // Check if this is a callActivity call
            String methodName = remoteCallNode.methodName().name().text();
            if (WorkflowConstants.CALL_ACTIVITY_FUNCTION.equals(methodName)) {
                // Extract the first argument which should be the activity function reference
                SeparatedNodeList<FunctionArgumentNode> arguments = remoteCallNode.arguments();
                if (!arguments.isEmpty()) {
                    FunctionArgumentNode firstArg = arguments.get(0);
                    if (firstArg instanceof PositionalArgumentNode posArg) {
                        Node expression = posArg.expression();
                        
                        // Get the function name from the expression
                        String activityFunctionName = extractFunctionName(expression);
                        if (activityFunctionName != null) {
                            activityMap.put(activityFunctionName, activityFunctionName);
                        }
                    }
                }
            }

            // Continue visiting child nodes
            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Extracts the function name from an expression node (typically a simple name reference).
         */
        private String extractFunctionName(Node expression) {
            if (expression.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                return expression.toString().trim();
            } else if (expression.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                return expression.toString().trim();
            }
            return null;
        }

        private String getFunctionName(FunctionCallExpressionNode callNode) {
            Node functionName = callNode.functionName();
            if (functionName.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                return functionName.toString().trim();
            } else if (functionName.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                // For module:function calls, preserve the full qualified name
                return functionName.toString().trim();
            }
            return null;
        }

        private boolean isActivityFunction(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
            if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
                return false;
            }
            FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
            return WorkflowPluginUtils.hasWorkflowAnnotation(functionSymbol,
                    WorkflowConstants.ACTIVITY_ANNOTATION);
        }
    }
}
