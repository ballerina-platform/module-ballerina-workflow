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
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;

import java.util.HashMap;
import java.util.List;
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

    @SuppressWarnings("unchecked")
    public ProcessFunctionAnalysisTask(Map<String, Object> userData) {
        this.userData = userData;
    }

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionDefinitionNode)) {
            return;
        }

        FunctionDefinitionNode functionNode = (FunctionDefinitionNode) context.node();

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
        Optional<MetadataNode> metadataOpt = functionNode.metadata();
        if (metadataOpt.isEmpty()) {
            return false;
        }

        NodeList<AnnotationNode> annotations = metadataOpt.get().annotations();
        for (AnnotationNode annotation : annotations) {
            if (isWorkflowProcessAnnotation(annotation, semanticModel)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWorkflowProcessAnnotation(AnnotationNode annotation, SemanticModel semanticModel) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(annotation);
        if (symbolOpt.isEmpty()) {
            return false;
        }

        Symbol symbol = symbolOpt.get();
        if (symbol.kind() != SymbolKind.ANNOTATION) {
            return false;
        }

        AnnotationSymbol annotationSymbol = (AnnotationSymbol) symbol;
        Optional<String> nameOpt = annotationSymbol.getName();
        if (nameOpt.isEmpty() || !WorkflowConstants.PROCESS_ANNOTATION.equals(nameOpt.get())) {
            return false;
        }

        // Verify it's from the workflow module
        Optional<ModuleSymbol> moduleOpt = annotationSymbol.getModule();
        if (moduleOpt.isEmpty()) {
            return false;
        }

        ModuleSymbol module = moduleOpt.get();
        Optional<String> moduleNameOpt = module.getName();
        return moduleNameOpt.isPresent() && WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get());
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

        private String getFunctionName(FunctionCallExpressionNode callNode) {
            Node functionName = callNode.functionName();
            if (functionName.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                return functionName.toString().trim();
            } else if (functionName.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                // For module:function calls, extract just the function name
                String[] parts = functionName.toString().trim().split(":");
                if (parts.length == 2) {
                    return parts[1];
                }
            }
            return null;
        }

        private boolean isActivityFunction(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
            if (symbolOpt.isEmpty()) {
                return false;
            }

            Symbol symbol = symbolOpt.get();
            if (symbol.kind() != SymbolKind.FUNCTION) {
                return false;
            }

            FunctionSymbol functionSymbol = (FunctionSymbol) symbol;
            List<AnnotationSymbol> annotations = functionSymbol.annotations();

            for (AnnotationSymbol annotation : annotations) {
                Optional<String> nameOpt = annotation.getName();
                if (nameOpt.isEmpty()) {
                    continue;
                }

                if (WorkflowConstants.ACTIVITY_ANNOTATION.equals(nameOpt.get())) {
                    // Verify it's from the workflow module
                    Optional<ModuleSymbol> moduleOpt = annotation.getModule();
                    if (moduleOpt.isPresent()) {
                        ModuleSymbol module = moduleOpt.get();
                        Optional<String> moduleNameOpt = module.getName();
                        if (moduleNameOpt.isPresent() &&
                                WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }
    }
}
