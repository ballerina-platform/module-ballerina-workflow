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

import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ImportOrgNameNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeParser;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.compiler.syntax.tree.TreeModifier;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.plugins.ModifierTask;
import io.ballerina.projects.plugins.SourceModifierContext;
import io.ballerina.tools.text.TextDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Source modifier that transforms workflow process functions.
 * <p>
 * This modifier performs AST transformations:
 * 1. Replaces activity function calls with callActivity(funcPtr, args...)
 * 2. Adds registerProcess call at module level for each @Process function
 *
 * @since 0.1.0
 */
public class WorkflowSourceModifier implements ModifierTask<SourceModifierContext> {

    private final Map<DocumentId, WorkflowModifierContext> modifierContextMap;

    public WorkflowSourceModifier(Map<DocumentId, WorkflowModifierContext> modifierContextMap) {
        this.modifierContextMap = modifierContextMap;
    }

    @Override
    public void modify(SourceModifierContext context) {
        for (Map.Entry<DocumentId, WorkflowModifierContext> entry : this.modifierContextMap.entrySet()) {
            DocumentId documentId = entry.getKey();
            WorkflowModifierContext workflowContext = entry.getValue();

            if (workflowContext.getProcessInfoMap().isEmpty()) {
                continue;
            }

            Module module = context.currentPackage().module(documentId.moduleId());
            ModulePartNode rootNode = module.document(documentId).syntaxTree().rootNode();

            // Transform the document
            ModulePartNode updatedRootNode = transformDocument(rootNode, workflowContext);

            // Add import if needed
            updatedRootNode = addWorkflowImportIfMissing(updatedRootNode);

            // Update the syntax tree
            SyntaxTree syntaxTree = module.document(documentId).syntaxTree().modifyWith(updatedRootNode);
            TextDocument textDocument = syntaxTree.textDocument();

            if (module.documentIds().contains(documentId)) {
                context.modifySourceFile(textDocument, documentId);
            } else {
                context.modifyTestSourceFile(textDocument, documentId);
            }
        }
    }

    private ModulePartNode transformDocument(ModulePartNode rootNode, WorkflowModifierContext workflowContext) {
        // First, transform the function bodies (replace activity calls)
        WorkflowTreeModifier treeModifier = new WorkflowTreeModifier(workflowContext);
        ModulePartNode modifiedRoot = (ModulePartNode) rootNode.apply(treeModifier);

        // Then, add registerProcess calls at module level
        NodeList<ModuleMemberDeclarationNode> members = modifiedRoot.members();
        List<ModuleMemberDeclarationNode> newMembers = new ArrayList<>();

        for (ModuleMemberDeclarationNode member : members) {
            newMembers.add(member);
        }

        // Add registerProcess calls for each process function
        for (ProcessFunctionInfo processInfo : workflowContext.getProcessInfoMap().values()) {
            ModuleVariableDeclarationNode registerCall = createRegisterProcessCall(processInfo);
            newMembers.add(registerCall);
        }

        NodeList<ModuleMemberDeclarationNode> updatedMembers = NodeFactory.createNodeList(newMembers);
        return modifiedRoot.modify(modifiedRoot.imports(), updatedMembers, modifiedRoot.eofToken());
    }

    private ModuleVariableDeclarationNode createRegisterProcessCall(ProcessFunctionInfo processInfo) {
        StringBuilder mapLiteral = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> activity : processInfo.getActivityMap().entrySet()) {
            if (!first) {
                mapLiteral.append(", ");
            }
            mapLiteral.append("\"").append(activity.getKey()).append("\": ").append(activity.getValue());
            first = false;
        }
        mapLiteral.append("}");

        String activitiesArg = processInfo.getActivityMap().isEmpty() ? "()" : mapLiteral.toString();

        String registerStatement = String.format(
                "boolean _ = workflow:registerProcess(%s, \"%s\", %s);",
                processInfo.getFunctionName(),
                processInfo.getFunctionName(),
                activitiesArg
        );

        return (ModuleVariableDeclarationNode) NodeParser.parseModuleMemberDeclaration(registerStatement);
    }

    private ModulePartNode addWorkflowImportIfMissing(ModulePartNode rootNode) {
        boolean hasWorkflowImport = false;

        for (ImportDeclarationNode importNode : rootNode.imports()) {
            if (isWorkflowImportNode(importNode)) {
                hasWorkflowImport = true;
                break;
            }
        }

        if (!hasWorkflowImport) {
            ImportDeclarationNode workflowImport = createWorkflowImportNode();
            NodeList<ImportDeclarationNode> imports = rootNode.imports().add(workflowImport);
            return rootNode.modify().withImports(imports).apply();
        }

        return rootNode;
    }

    private boolean isWorkflowImportNode(ImportDeclarationNode importNode) {
        if (importNode.orgName().isEmpty()) {
            return false;
        }
        String orgName = importNode.orgName().get().orgName().text();
        if (!WorkflowConstants.PACKAGE_ORG.equals(orgName)) {
            return false;
        }
        SeparatedNodeList<IdentifierToken> moduleNames = importNode.moduleName();
        if (moduleNames.isEmpty()) {
            return false;
        }
        return WorkflowConstants.PACKAGE_NAME.equals(moduleNames.get(0).text());
    }

    private ImportDeclarationNode createWorkflowImportNode() {
        Token importKeyword = NodeFactory.createToken(SyntaxKind.IMPORT_KEYWORD,
                NodeFactory.createEmptyMinutiaeList(),
                NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")));

        Token orgNameToken = NodeFactory.createIdentifierToken(WorkflowConstants.PACKAGE_ORG);
        Token slashToken = NodeFactory.createToken(SyntaxKind.SLASH_TOKEN);
        ImportOrgNameNode importOrgNameToken = NodeFactory.createImportOrgNameNode(orgNameToken, slashToken);

        IdentifierToken moduleNameNode = NodeFactory.createIdentifierToken(WorkflowConstants.PACKAGE_NAME);
        SeparatedNodeList<IdentifierToken> moduleName = NodeFactory.createSeparatedNodeList(moduleNameNode);
        Token semicolonToken = NodeFactory.createToken(SyntaxKind.SEMICOLON_TOKEN);

        return NodeFactory.createImportDeclarationNode(importKeyword, importOrgNameToken, moduleName, null,
                semicolonToken);
    }

    /**
     * Tree modifier that transforms activity function calls within process functions.
     */
    private static class WorkflowTreeModifier extends TreeModifier {
        private final WorkflowModifierContext workflowContext;
        private boolean insideProcessFunction = false;
        private String currentProcessName = null;

        WorkflowTreeModifier(WorkflowModifierContext workflowContext) {
            this.workflowContext = workflowContext;
        }

        @Override
        public FunctionDefinitionNode transform(FunctionDefinitionNode functionNode) {
            String functionName = functionNode.functionName().text();

            // Check if this is a process function
            if (workflowContext.getProcessInfoMap().containsKey(functionName)) {
                insideProcessFunction = true;
                currentProcessName = functionName;

                // Transform the function body
                FunctionDefinitionNode transformed = (FunctionDefinitionNode) super.transform(functionNode);

                insideProcessFunction = false;
                currentProcessName = null;
                return transformed;
            }

            return functionNode;
        }

        @Override
        public FunctionCallExpressionNode transform(FunctionCallExpressionNode callNode) {
            // First, transform child nodes (arguments) to handle nested activity calls
            FunctionCallExpressionNode transformedNode = (FunctionCallExpressionNode) super.transform(callNode);

            if (!insideProcessFunction || currentProcessName == null) {
                return transformedNode;
            }

            ProcessFunctionInfo processInfo = workflowContext.getProcessInfoMap().get(currentProcessName);
            if (processInfo == null) {
                return transformedNode;
            }

            // Get the function name being called
            String calledFunctionName = getFunctionName(transformedNode);
            if (calledFunctionName == null) {
                return transformedNode;
            }

            // Check if this is an activity function call
            if (!processInfo.getActivityMap().containsKey(calledFunctionName)) {
                return transformedNode;
            }

            // Build the new callActivity call
            // Note: We use 'workflow:' prefix assuming standard import. If users use an alias,
            // they should also import without alias or the modifier adds an unaliased import.
            StringBuilder newCallBuilder = new StringBuilder("workflow:callActivity(");
            newCallBuilder.append(calledFunctionName);

            // Add transformed arguments (handles nested activity calls)
            SeparatedNodeList<io.ballerina.compiler.syntax.tree.FunctionArgumentNode> args =
                    transformedNode.arguments();
            for (int i = 0; i < args.size(); i++) {
                newCallBuilder.append(", ");
                newCallBuilder.append(args.get(i).toString());
            }
            newCallBuilder.append(")");

            // Parse and return the new call expression
            String newCallStr = newCallBuilder.toString();
            Node parsed = NodeParser.parseExpression(newCallStr);
            if (parsed instanceof FunctionCallExpressionNode) {
                return (FunctionCallExpressionNode) parsed;
            }

            return transformedNode;
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
    }
}
