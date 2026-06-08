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
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Analysis task that detects @Workflow annotated functions and collects
 * information about @Activity function calls and callHumanTask call sites within them.
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

        // Check if this function has @workflow:Workflow annotation
        if (!hasProcessAnnotation(functionNode, context.semanticModel())) {
            return;
        }

        String functionName = functionNode.functionName().text();

        // Collect activity calls and human task names within this workflow function
        Map<String, String> activityMap = new LinkedHashMap<>();
        Set<String> humanTaskNames = new LinkedHashSet<>();
        ActivityCallCollector collector = new ActivityCallCollector(context, activityMap, humanTaskNames);
        functionNode.functionBody().accept(collector);

        ProcessFunctionInfo processInfo = new ProcessFunctionInfo(functionName, activityMap, humanTaskNames);
        addToModifierContext(context.documentId(), processInfo);
    }

    private boolean hasProcessAnnotation(FunctionDefinitionNode functionNode, SemanticModel semanticModel) {
        return WorkflowPluginUtils.hasWorkflowAnnotation(functionNode, semanticModel,
                WorkflowConstants.PROCESS_ANNOTATION);
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
     * Node visitor that collects activity calls and human task names within a workflow function.
     * <ul>
     *   <li>Calls to {@code @Activity}-annotated functions (direct or via {@code ctx->callActivity})</li>
     *   <li>Task names passed to {@code ctx->callHumanTask} as the {@code taskName} field of a
     *       literal {@code HumanTaskConfig} mapping expression</li>
     * </ul>
     */
    private static class ActivityCallCollector extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;
        private final Map<String, String> activityMap;
        private final Set<String> humanTaskNames;

        ActivityCallCollector(SyntaxNodeAnalysisContext context, Map<String, String> activityMap,
                              Set<String> humanTaskNames) {
            this.context = context;
            this.semanticModel = context.semanticModel();
            this.activityMap = activityMap;
            this.humanTaskNames = humanTaskNames;
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            String functionName = getFunctionName(callNode);
            if (functionName != null && isActivityFunction(callNode)) {
                activityMap.put(functionName, functionName);
            }

            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Visits remote method call actions to detect activity and human task call patterns.
         * <ul>
         *   <li>{@code ctx->callActivity(activityFunc, args)} — registers the activity</li>
         *   <li>{@code ctx->callHumanTask(T, { taskName: "..." })} — registers the task name</li>
         * </ul>
         */
        @Override
        public void visit(RemoteMethodCallActionNode remoteCallNode) {
            String methodName = remoteCallNode.methodName().name().text();

            if (WorkflowConstants.CALL_ACTIVITY_FUNCTION.equals(methodName)) {
                SeparatedNodeList<FunctionArgumentNode> arguments = remoteCallNode.arguments();
                if (!arguments.isEmpty()) {
                    FunctionArgumentNode firstArg = arguments.get(0);
                    if (firstArg instanceof PositionalArgumentNode posArg) {
                        Node expression = posArg.expression();
                        String fullRef = extractFunctionName(expression);
                        if (fullRef != null) {
                            String simpleName = stripModulePrefix(fullRef);
                            String existing = activityMap.get(simpleName);
                            if (existing != null && !existing.equals(fullRef)) {
                                DiagnosticInfo info = new DiagnosticInfo(
                                        WorkflowDiagnostic.WORKFLOW_127.getCode(),
                                        WorkflowDiagnostic.WORKFLOW_127.getMessage(
                                                simpleName, existing, fullRef),
                                        WorkflowDiagnostic.WORKFLOW_127.getSeverity());
                                context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                                        info, remoteCallNode.location()));
                            } else {
                                activityMap.put(simpleName, fullRef);
                            }
                        }
                    }
                }
            } else if (WorkflowConstants.CALL_HUMAN_TASK_METHOD.equals(methodName)) {
                String taskName = extractHumanTaskName(remoteCallNode.arguments());
                if (taskName != null) {
                    if (taskName.contains(".") || taskName.contains("|")) {
                        DiagnosticInfo info = new DiagnosticInfo(
                                WorkflowDiagnostic.WORKFLOW_128.getCode(),
                                WorkflowDiagnostic.WORKFLOW_128.getMessage(taskName),
                                WorkflowDiagnostic.WORKFLOW_128.getSeverity());
                        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                                info, remoteCallNode.location()));
                    } else {
                        humanTaskNames.add(taskName);
                    }
                }
            }

            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Extracts the literal {@code taskName} value from the arguments of a
         * {@code callHumanTask} remote call. Searches all positional and named arguments
         * for a {@link MappingConstructorExpressionNode} that contains a {@code taskName}
         * field with a string literal value.
         *
         * @return the task name string (without surrounding quotes), or {@code null} if it
         *         cannot be statically determined (e.g. passed as a variable)
         */
        private String extractHumanTaskName(SeparatedNodeList<FunctionArgumentNode> args) {
            for (int i = args.size() - 1; i >= 0; i--) {
                FunctionArgumentNode arg = args.get(i);
                ExpressionNode expression = null;

                if (arg instanceof PositionalArgumentNode posArg) {
                    expression = posArg.expression();
                } else if (arg instanceof NamedArgumentNode namedArg
                        && "config".equals(namedArg.argumentName().name().text())) {
                    expression = namedArg.expression();
                }

                if (expression instanceof MappingConstructorExpressionNode mappingNode) {
                    String taskName = extractTaskNameFromMapping(mappingNode);
                    if (taskName != null) {
                        return taskName;
                    }
                }
            }
            return null;
        }

        /**
         * Searches a {@link MappingConstructorExpressionNode} for a field named
         * {@code taskName} with a string literal value and returns the unquoted string.
         */
        private String extractTaskNameFromMapping(MappingConstructorExpressionNode mappingNode) {
            for (MappingFieldNode field : mappingNode.fields()) {
                if (!(field instanceof SpecificFieldNode specificField)) {
                    continue;
                }
                String fieldName = specificField.fieldName().toString().trim();
                if (!"taskName".equals(fieldName) || specificField.valueExpr().isEmpty()) {
                    continue;
                }
                ExpressionNode valueExpr = specificField.valueExpr().get();
                if (valueExpr instanceof BasicLiteralNode literal
                        && literal.kind() == SyntaxKind.STRING_LITERAL) {
                    String raw = literal.literalToken().text();
                    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                        return raw.substring(1, raw.length() - 1);
                    }
                }
            }
            return null;
        }

        private String extractFunctionName(Node expression) {
            if (expression.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                return expression.toString().trim();
            } else if (expression.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                return expression.toString().trim();
            }
            return null;
        }

        private static String stripModulePrefix(String ref) {
            int colon = ref.indexOf(':');
            return colon < 0 ? ref : ref.substring(colon + 1).trim();
        }

        private String getFunctionName(FunctionCallExpressionNode callNode) {
            Node functionName = callNode.functionName();
            if (functionName.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                return functionName.toString().trim();
            } else if (functionName.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
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
