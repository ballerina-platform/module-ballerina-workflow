// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package io.ballerina.lib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.Optional;

/**
 * Validates the language's {@code @display} annotation where the workflow descriptor reads it: on
 * {@code @Workflow} and {@code @Activity} functions and on {@code workflow:DurableAgent} variables.
 * The label is a console's name for the declaration, so a blank one is an error
 * ({@code WORKFLOW_165}); the language already guarantees it is a compile-time constant.
 *
 * @since 0.11.0
 */
public class DisplayAnnotationValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final String LABEL_FIELD = "label";

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (context.node() instanceof FunctionDefinitionNode fnDef) {
            Optional<Symbol> symbol = context.semanticModel().symbol(fnDef);
            if (symbol.isPresent() && symbol.get() instanceof FunctionSymbol fnSymbol
                    && (WorkflowPluginUtils.hasWorkflowAnnotation(fnSymbol, WorkflowConstants.PROCESS_ANNOTATION)
                    || WorkflowPluginUtils.hasWorkflowAnnotation(fnSymbol, WorkflowConstants.ACTIVITY_ANNOTATION))) {
                validate(context, fnDef.metadata().orElse(null), fnDef.functionName().text());
            }
        } else if (context.node() instanceof ModuleVariableDeclarationNode varDecl) {
            Optional<Symbol> symbol = context.semanticModel().symbol(varDecl);
            if (symbol.isPresent() && symbol.get() instanceof VariableSymbol varSymbol
                    && isDurableAgent(varSymbol)) {
                validate(context, varDecl.metadata().orElse(null),
                        varSymbol.getName().orElse(varDecl.typedBindingPattern().bindingPattern().toSourceCode()
                                .trim()));
            }
        }
    }

    private void validate(SyntaxNodeAnalysisContext context, MetadataNode metadata, String declarationName) {
        if (metadata == null) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();
        for (AnnotationNode annotation : metadata.annotations()) {
            Optional<Symbol> symbol = semanticModel.symbol(annotation);
            if (symbol.isEmpty() || !(symbol.get() instanceof AnnotationSymbol annotationSymbol)
                    || !WorkflowPluginUtils.isDisplayAnnotation(annotationSymbol)
                    || annotation.annotValue().isEmpty()) {
                continue;
            }
            for (MappingFieldNode field : annotation.annotValue().get().fields()) {
                if (field instanceof SpecificFieldNode specific && specific.valueExpr().isPresent()
                        && LABEL_FIELD.equals(keyOf(specific)) && isBlankLiteral(specific.valueExpr().get())) {
                    report(context, specific.valueExpr().get().location(), declarationName);
                }
            }
        }
    }

    private static boolean isDurableAgent(VariableSymbol varSymbol) {
        return varSymbol.typeDescriptor() instanceof TypeReferenceTypeSymbol ref
                && ref.getName().map(WorkflowConstants.DURABLE_AGENT_TYPE::equals).orElse(false)
                && ref.getModule().map(WorkflowPluginUtils::isWorkflowModule).orElse(false);
    }

    // The annotation is source-only, so a non-constant label is already a compiler error; the
    // only shape left to check is a literal or interpolation-free template with nothing in it.
    private static boolean isBlankLiteral(Node expression) {
        if (expression instanceof BasicLiteralNode literal && literal.kind() == SyntaxKind.STRING_LITERAL) {
            String raw = literal.literalToken().text();
            return raw.length() >= 2 && raw.substring(1, raw.length() - 1).isBlank();
        }
        if (expression.kind() == SyntaxKind.STRING_TEMPLATE_EXPRESSION) {
            String text = expression.toSourceCode().strip();
            int open = text.indexOf('`');
            int close = text.lastIndexOf('`');
            return open >= 0 && close > open && text.substring(open + 1, close).isBlank();
        }
        return false;
    }

    private static String keyOf(SpecificFieldNode field) {
        return field.fieldName() instanceof Token token ? token.text().strip() : null;
    }

    private static void report(SyntaxNodeAnalysisContext context, Location location, String declarationName) {
        WorkflowDiagnostic diagnostic = WorkflowDiagnostic.WORKFLOW_165;
        DiagnosticInfo info = new DiagnosticInfo(diagnostic.getCode(), diagnostic.getMessage(declarationName),
                diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info, location));
    }
}
