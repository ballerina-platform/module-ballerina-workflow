/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.workflow.compiler.codemodifier.tasks;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.AbstractNodeFactory;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.Package;
import io.ballerina.projects.plugins.ModifierTask;
import io.ballerina.projects.plugins.SourceModifierContext;
import io.ballerina.stdlib.workflow.compiler.codemodifier.WorkflowTreeModifier;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.text.TextDocument;

import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createIdentifierToken;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createSeparatedNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createToken;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createImportDeclarationNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createImportOrgNameNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createImportPrefixNode;
import static io.ballerina.stdlib.workflow.compiler.Constants.BALLERINA;
import static io.ballerina.stdlib.workflow.compiler.Constants.INTERNAL;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW_INTERNAL;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.createIdentifierTokenWithWS;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.createTokenWithWS;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.getServiceDeclarationNode;

/**
 * {@code WorkflowModifierTask} injects the workflow internal import and modifies the service declaration nodes.
 *
 * @since 0.1.0
 */
public class WorkflowModifierTask implements ModifierTask<SourceModifierContext> {
    @Override
    public void modify(SourceModifierContext modifierContext) {
        boolean erroneousCompilation = modifierContext.compilation().diagnosticResult()
                .diagnostics().stream()
                .anyMatch(d -> DiagnosticSeverity.ERROR.equals(d.diagnosticInfo().severity()));
        if (erroneousCompilation) {
            return;
        }
        modifyServiceDeclarationNodes(modifierContext);
    }

    private void modifyServiceDeclarationNodes(SourceModifierContext modifierContext) {
        Package currentPackage = modifierContext.currentPackage();
        for (ModuleId moduleId : currentPackage.moduleIds()) {
            modifyServiceDeclarationsPerModule(modifierContext, moduleId, currentPackage);
        }
    }

    private void modifyServiceDeclarationsPerModule(SourceModifierContext modifierContext, ModuleId moduleId,
                                                    Package currentPackage) {
        Module currentModule = currentPackage.module(moduleId);
        for (DocumentId documentId : currentModule.documentIds()) {
            modifyServiceDeclarationsPerDocument(modifierContext, documentId, currentModule);
        }
        for (DocumentId documentId : currentModule.testDocumentIds()) {
            modifyServiceDeclarationsPerDocument(modifierContext, documentId, currentModule);
        }
    }

    private void modifyServiceDeclarationsPerDocument(SourceModifierContext modifierContext, DocumentId documentId,
                                                      Module currentModule) {
        Document currentDoc = currentModule.document(documentId);
        ModulePartNode rootNode = currentDoc.syntaxTree().rootNode();
        SemanticModel semanticModel = modifierContext.compilation().getSemanticModel(currentModule.moduleId());
        ModulePartNode newModulePart = performModifications(rootNode, semanticModel);
        SyntaxTree updatedSyntaxTree = currentDoc.syntaxTree().modifyWith(newModulePart);
        TextDocument textDocument = updatedSyntaxTree.textDocument();
        if (currentModule.documentIds().contains(documentId)) {
            modifierContext.modifySourceFile(textDocument, documentId);
        } else {
            modifierContext.modifyTestSourceFile(textDocument, documentId);
        }
    }

    private ModulePartNode performModifications(ModulePartNode rootNode,
                                                SemanticModel semanticModel) {
        NodeList<ModuleMemberDeclarationNode> oldMembers = rootNode.members();
        NodeList<ModuleMemberDeclarationNode> updatedMembers = AbstractNodeFactory.createEmptyNodeList();
        boolean workflowServiceFound = false;
        for (ModuleMemberDeclarationNode memberNode : oldMembers) {
            if (memberNode.kind().equals(SyntaxKind.SERVICE_DECLARATION) &&
                    getServiceDeclarationNode(memberNode, semanticModel) != null) {
                workflowServiceFound = true;
                updatedMembers = updatedMembers.add(updateServiceDeclarationNode((ServiceDeclarationNode) memberNode,
                        semanticModel));
            } else {
                updatedMembers = updatedMembers.add(memberNode);
            }
        }
        NodeList<ImportDeclarationNode> newImports = rootNode.imports();
        if (workflowServiceFound) {
            newImports = newImports.add(createWorkflowInternalImport());
        }

        return rootNode.modify(newImports, updatedMembers, rootNode.eofToken());
    }

    private ServiceDeclarationNode updateServiceDeclarationNode(ServiceDeclarationNode serviceDeclarationNode,
                                                                SemanticModel semanticModel) {
        WorkflowTreeModifier workflowTreeModifier = new WorkflowTreeModifier(semanticModel);
        return workflowTreeModifier.transform(serviceDeclarationNode);
    }

    private ImportDeclarationNode createWorkflowInternalImport() {
        return createImportDeclarationNode(
                createTokenWithWS(SyntaxKind.IMPORT_KEYWORD),
                createImportOrgNameNode(createIdentifierToken(BALLERINA), createToken(SyntaxKind.SLASH_TOKEN)),
                createSeparatedNodeList(createIdentifierToken(WORKFLOW), createIdentifierTokenWithWS(INTERNAL)),
                createImportPrefixNode(createTokenWithWS(SyntaxKind.AS_KEYWORD),
                        createIdentifierToken(WORKFLOW_INTERNAL)),
                createToken(SyntaxKind.SEMICOLON_TOKEN)
        );
    }
}
