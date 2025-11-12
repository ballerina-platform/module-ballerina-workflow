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

package io.ballerina.stdlib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ServiceDeclarationSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLocation;

import java.util.List;
import java.util.Optional;

import static io.ballerina.stdlib.workflow.compiler.Constants.BALLERINA;
import static io.ballerina.stdlib.workflow.compiler.Constants.QUERY;
import static io.ballerina.stdlib.workflow.compiler.Constants.SIGNAL;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW;

/**
 * Utility class providing workflow compiler plugin utility methods.
 *
 * @since 0.1.0
 */
public class WorkflowCompilerPluginUtil {
    public static ServiceDeclarationNode getServiceDeclarationNode(SyntaxNodeAnalysisContext context) {
        if (context.node().kind() != SyntaxKind.SERVICE_DECLARATION) {
            return null;
        }
        return getServiceDeclarationNode(context.node(), context.semanticModel());
    }

    public static ServiceDeclarationNode getServiceDeclarationNode(Node node, SemanticModel semanticModel) {
        if (node.kind() != SyntaxKind.SERVICE_DECLARATION) {
            return null;
        }

        Optional<Symbol> serviceSymOptional = semanticModel.symbol(node);
        if (serviceSymOptional.isPresent()) {
            List<TypeSymbol> listenerTypes = ((ServiceDeclarationSymbol) serviceSymOptional.get()).listenerTypes();
            if (listenerTypes.stream().noneMatch(WorkflowCompilerPluginUtil::isListenerBelongsToWorkflowModule)) {
                return null;
            }
        }
        return (ServiceDeclarationNode) node;
    }

    private static boolean isListenerBelongsToWorkflowModule(TypeSymbol listenerType) {
        if (listenerType.typeKind() == TypeDescKind.UNION) {
            return ((UnionTypeSymbol) listenerType).memberTypeDescriptors().stream()
                    .filter(listenerSymbol -> listenerSymbol.typeKind() == TypeDescKind.TYPE_REFERENCE)
                    .anyMatch(typeReferenceTypeSymbol ->
                            isWorkflowModule(typeReferenceTypeSymbol.getModule().orElse(null)));
        }

        if (listenerType.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            return isWorkflowModule(((TypeReferenceTypeSymbol) listenerType).typeDescriptor().getModule().orElse(null));
        }
        return false;
    }

    public static boolean isWorkflowModule(ModuleSymbol moduleSymbol) {
        return moduleSymbol != null && WORKFLOW.equals(moduleSymbol.getName().orElse(null)) &&
                BALLERINA.equals(moduleSymbol.id().orgName());
    }

    public static void updateDiagnostic(SyntaxNodeAnalysisContext ctx, Location location,
                                        WorkflowDiagnostic httpDiagnosticCodes) {
        DiagnosticInfo diagnosticInfo = getDiagnosticInfo(httpDiagnosticCodes);
        ctx.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }

    public static DiagnosticInfo getDiagnosticInfo(WorkflowDiagnostic diagnostic, Object... args) {
        return new DiagnosticInfo(diagnostic.getCode(), String.format(diagnostic.getMessage(), args),
                diagnostic.getSeverity());
    }

    public static BLangDiagnosticLocation createDiagnosticLocation(Optional<Location> loc) {
        if (loc.isEmpty()) {
            return new BLangDiagnosticLocation("null", 0, 0, 0, 0, 0, 0);
        }
        Location location = loc.get();
        return new BLangDiagnosticLocation(location.lineRange().fileName(),
                location.lineRange().startLine().line(), location.lineRange().endLine().line(),
                location.lineRange().startLine().offset(), location.lineRange().endLine().offset(), 0, 0);
    }

    public static boolean hasQueryOrSignalAnnotation(NodeList<AnnotationNode> annotations) {
        return annotations.stream().anyMatch(annotation -> {
            Node annotReference = annotation.annotReference();
            if (annotReference.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                return false;
            }
            QualifiedNameReferenceNode annotationNode = (QualifiedNameReferenceNode) annotReference;
            return annotationNode.modulePrefix().text().equals(WORKFLOW) &&
                    (annotationNode.identifier().text().equals(SIGNAL) ||
                            annotationNode.identifier().text().equals(QUERY));
        });
    }

    public static boolean hasQueryAnnotation(NodeList<AnnotationNode> annotations) {
        return annotations.stream().anyMatch(annotation -> {
            Node annotReference = annotation.annotReference();
            if (annotReference.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                return false;
            }
            QualifiedNameReferenceNode annotationNode = (QualifiedNameReferenceNode) annotReference;
            return annotationNode.modulePrefix().text().equals(WORKFLOW) &&
                    (annotationNode.identifier().text().equals(QUERY));
        });
    }
}
