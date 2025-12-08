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

package io.ballerina.stdlib.workflow.compiler.analyzer;

import io.ballerina.compiler.api.TypeBuilder;
import io.ballerina.compiler.api.Types;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.LockStatementNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic;
import io.ballerina.tools.diagnostics.Location;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLocation;

import java.util.ArrayList;
import java.util.Optional;

import static io.ballerina.stdlib.workflow.compiler.Constants.EXECUTE;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.createDiagnosticLocation;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.getServiceDeclarationNode;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.hasQueryAnnotation;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.hasQueryOrSignalAnnotation;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.updateDiagnostic;

/**
 * Workflow service analysis task.
 *
 * @since 0.1.0
 */
public class WorkflowServiceAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {
    @Override
    public void perform(SyntaxNodeAnalysisContext ctx) {
        ServiceDeclarationNode serviceDeclarationNode = getServiceDeclarationNode(ctx);
        if (serviceDeclarationNode == null) {
            return;
        }

        boolean executeMethodFound = false;
        for (Node member : serviceDeclarationNode.members()) {
            if (member.kind() == SyntaxKind.RESOURCE_ACCESSOR_DEFINITION) {
                // Report error: resource methods are not allowed in workflow services
                reportResourceMethodNotAllowed(ctx, member);
                continue;
            }
            if (member.kind() != SyntaxKind.OBJECT_METHOD_DEFINITION) {
                continue;
            }

            FunctionDefinitionNode functionNode = (FunctionDefinitionNode) member;
            if (!isRemoteMethod(functionNode)) {
                continue;
            }

            if (functionNode.functionName().text().equals(EXECUTE)) {
                executeMethodFound = true;
                // Validate that execute function doesn't access mutable global variables
                validateNoMutableGlobalAccess(ctx, functionNode);
            } else {
                // non-execute remote methods should be annotated with @workflow:Query or @workflow:Signal
                validateAnnotations(ctx, functionNode);
            }
            // Remote methods should be isolated
            validateIsolatedQualifier(ctx, functionNode);
            // Validate parameters (must be subtype of anydata)
            validateParameters(ctx, functionNode);
            // Validate return type
            validateReturnType(ctx, functionNode);
        }

        if (!executeMethodFound) {
            updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(serviceDeclarationNode.location())),
                    WorkflowDiagnostic.WORKFLOW_101);
        }
    }

    private static void validateIsolatedQualifier(SyntaxNodeAnalysisContext ctx, FunctionDefinitionNode functionNode) {
        if (functionNode.qualifierList().stream().noneMatch(token -> token.kind() == SyntaxKind.ISOLATED_KEYWORD)) {
            updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(functionNode.location())),
                    WorkflowDiagnostic.WORKFLOW_104);
        }
    }

    private static void validateParameters(SyntaxNodeAnalysisContext ctx, FunctionDefinitionNode functionNode) {
        MethodSymbol methodSymbol = (MethodSymbol) ctx.semanticModel().symbol(functionNode).orElse(null);
        if (methodSymbol == null) {
            return;
        }

        methodSymbol.typeDescriptor().params().orElse(new ArrayList<>()).forEach(param -> {
            if (!param.typeDescriptor().subtypeOf(ctx.semanticModel().types().ANYDATA)) {
                updateDiagnostic(ctx, createDiagnosticLocation(param.getLocation()), WorkflowDiagnostic.WORKFLOW_102);
            }
        });
    }

    private static void validateAnnotations(SyntaxNodeAnalysisContext ctx, FunctionDefinitionNode functionNode) {
        Optional<MetadataNode> metadata = functionNode.metadata();
        if (metadata.isEmpty() || !hasQueryOrSignalAnnotation(metadata.get().annotations())) {
            updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(functionNode.location())),
                    WorkflowDiagnostic.WORKFLOW_103);
        }
    }

    private static void validateReturnType(SyntaxNodeAnalysisContext ctx, FunctionDefinitionNode functionNode) {
        MethodSymbol methodSymbol = (MethodSymbol) ctx.semanticModel().symbol(functionNode).orElse(null);
        if (methodSymbol == null) {
            return;
        }

        Optional<TypeSymbol> optionalRetTSym = methodSymbol.typeDescriptor().returnTypeDescriptor();
        if (optionalRetTSym.isEmpty()) {
            updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(functionNode.location())),
                    WorkflowDiagnostic.WORKFLOW_106);
            return;
        }

        TypeSymbol retTSym = optionalRetTSym.get();
        Types types = ctx.semanticModel().types();
        TypeBuilder builder = types.builder();
        Optional<MetadataNode> metadata = functionNode.metadata();
        // Todo: Use return type location after https://github.com/ballerina-platform/ballerina-lang/issues/44396
        if (metadata.isPresent() && hasQueryAnnotation(metadata.get().annotations())) {
            if (!(retTSym.subtypeOf(builder.UNION_TYPE.withMemberTypes(types.ANYDATA, types.ERROR).build()))) {
                updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(functionNode.location())),
                        WorkflowDiagnostic.WORKFLOW_105);
            }
        } else {
            if (!(retTSym.subtypeOf(builder.UNION_TYPE.withMemberTypes(types.ERROR, types.NIL).build()))) {
                updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(functionNode.location())),
                        WorkflowDiagnostic.WORKFLOW_106);
            }
        }
    }

    private static boolean isRemoteMethod(FunctionDefinitionNode functionNode) {
        return functionNode.qualifierList().stream().anyMatch(token -> token.kind() == SyntaxKind.REMOTE_KEYWORD);
    }

    private static void reportResourceMethodNotAllowed(SyntaxNodeAnalysisContext ctx, Node resourceNode) {
        Location location = resourceNode.location();
        BLangDiagnosticLocation diagnosticLocation = new BLangDiagnosticLocation(location.lineRange().fileName(),
                location.lineRange().startLine().line(), location.lineRange().endLine().line(),
                location.lineRange().startLine().offset(), location.lineRange().endLine().offset(), 0, 0);
        updateDiagnostic(ctx, diagnosticLocation, WorkflowDiagnostic.WORKFLOW_100);
    }

    private static void validateNoMutableGlobalAccess(SyntaxNodeAnalysisContext ctx,
                                                       FunctionDefinitionNode functionNode) {
        // Create a visitor that extends NodeTransformer to traverse the tree
        MutableGlobalAccessChecker checker = new MutableGlobalAccessChecker(ctx);
        functionNode.accept(checker);
    }

    /**
     * Checker to validate mutable global variable access in execute function.
     * Only checks variables accessed inside lock statements since isolated function
     * validation will handle access outside locks.
     */
    private static class MutableGlobalAccessChecker extends NodeVisitor {
        private final SyntaxNodeAnalysisContext ctx;
        private boolean insideLockStatement = false;

        MutableGlobalAccessChecker(SyntaxNodeAnalysisContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void visit(LockStatementNode lockStatementNode) {
            insideLockStatement = true;
            super.visit(lockStatementNode);
            insideLockStatement = false;
        }

        @Override
        public void visit(SimpleNameReferenceNode simpleNameReferenceNode) {
            // Only check if we're inside a lock statement
            if (insideLockStatement) {
                checkNameReference(simpleNameReferenceNode);
            }
        }

        void checkNameReference(Node nameReferenceNode) {
            Optional<Symbol> optSymbol = ctx.semanticModel().symbol(nameReferenceNode);
            if (optSymbol.isEmpty()) {
                return;
            }

            Symbol symbol = optSymbol.get();
            if (symbol.kind() != SymbolKind.VARIABLE) {
                return;
            }

            VariableSymbol variableSymbol = (VariableSymbol) symbol;
            // compiler allow mutable storage access via isolated variables, we need to disallow them here
            if (!variableSymbol.qualifiers().contains(Qualifier.ISOLATED)) {
                // If the variable is not isolated, compiler would have already reported an error
                return;
            }

            boolean isFinal = variableSymbol.qualifiers().contains(Qualifier.FINAL);
            TypeSymbol typeSymbol = variableSymbol.typeDescriptor();
            boolean isReadonlyType = typeSymbol.subtypeOf(ctx.semanticModel().types().READONLY);

            if (isFinal && isReadonlyType) {
                return;
            }

            updateDiagnostic(ctx, createDiagnosticLocation(Optional.of(nameReferenceNode.location())),
                    WorkflowDiagnostic.WORKFLOW_108);
        }
    }
}
