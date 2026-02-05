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

import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ImportOrgNameNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
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
        for (Map.Entry<String, String> activity : processInfo.activityMap().entrySet()) {
            if (!first) {
                mapLiteral.append(", ");
            }
            mapLiteral.append("\"").append(activity.getKey()).append("\": ").append(activity.getValue());
            first = false;
        }
        mapLiteral.append("}");

        String activitiesArg = processInfo.activityMap().isEmpty() ? "()" : mapLiteral.toString();

        String registerStatement = String.format(
                "boolean _ = check workflow:registerProcess(%s, \"%s\", %s);",
                processInfo.functionName(),
                processInfo.functionName(),
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
     * Tree modifier that handles process functions.
     * Note: Direct activity call transformation has been removed.
     * Users must explicitly use ctx->callActivity(activityFunc, args...) pattern.
     */
    private static class WorkflowTreeModifier extends TreeModifier {
        private final WorkflowModifierContext workflowContext;

        WorkflowTreeModifier(WorkflowModifierContext workflowContext) {
            this.workflowContext = workflowContext;
        }

        @Override
        public FunctionDefinitionNode transform(FunctionDefinitionNode functionNode) {
            String functionName = functionNode.functionName().text();

            // Check if this is a process function - just apply standard transformation
            if (workflowContext.getProcessInfoMap().containsKey(functionName)) {
                return super.transform(functionNode);
            }

            return functionNode;
        }

        // Note: FunctionCallExpressionNode transformation removed.
        // Direct activity calls are now disallowed and validated by WorkflowValidatorTask.
        // Users must use ctx->callActivity(activityFunc, args...) pattern.
    }
}
