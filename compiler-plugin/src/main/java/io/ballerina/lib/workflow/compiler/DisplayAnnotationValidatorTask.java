// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
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
                validate(context, fnDef.metadata().orElse(null), fnDef.functionName().text(), fnSymbol);
            }
        } else if (context.node() instanceof ModuleVariableDeclarationNode varDecl) {
            Optional<Symbol> symbol = context.semanticModel().symbol(varDecl);
            if (symbol.isPresent() && symbol.get() instanceof VariableSymbol varSymbol
                    && isDurableAgent(varSymbol)) {
                validate(context, varDecl.metadata().orElse(null),
                        varSymbol.getName().orElse(varDecl.typedBindingPattern().bindingPattern().toSourceCode()
                                .trim()), varSymbol);
            }
        }
    }

    private void validate(SyntaxNodeAnalysisContext context, MetadataNode metadata, String declarationName,
                          Symbol declaration) {
        if (metadata == null || !WorkflowPluginUtils.hasBlankDisplayLabel(declaration)) {
            return;
        }
        // The value is judged from the semantic model, so an escape or a constant reference counts as
        // what it evaluates to; the syntax only supplies the location.
        SemanticModel semanticModel = context.semanticModel();
        for (AnnotationNode annotation : metadata.annotations()) {
            Optional<Symbol> symbol = semanticModel.symbol(annotation);
            if (symbol.isEmpty() || !(symbol.get() instanceof AnnotationSymbol annotationSymbol)
                    || !WorkflowPluginUtils.isDisplayAnnotation(annotationSymbol)) {
                continue;
            }
            Location location = annotation.location();
            if (annotation.annotValue().isPresent()) {
                for (MappingFieldNode field : annotation.annotValue().get().fields()) {
                    if (field instanceof SpecificFieldNode specific && LABEL_FIELD.equals(keyOf(specific))) {
                        location = specific.location();
                    }
                }
            }
            report(context, location, declarationName);
            return;
        }
    }

    private static boolean isDurableAgent(VariableSymbol varSymbol) {
        return DurableAgentDeclAnalysisTask.isDurableAgentSymbol(varSymbol);
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
