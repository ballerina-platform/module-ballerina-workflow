package io.ballerina.stdlib.workflow.compiler.analyzer;

import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic;

import java.util.ArrayList;
import java.util.Optional;

import static io.ballerina.stdlib.workflow.compiler.Constants.ACTIVITY;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.createDiagnosticLocation;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.updateDiagnostic;

/**
 * Analyzes workflow activities.
 * This task is responsible for validating and processing workflow activity function signatures
 *
 * @since 0.1.0
 */
public class WorkflowActivityAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {
    @Override
    public void perform(SyntaxNodeAnalysisContext ctx) {
        if (ctx.node().kind() != SyntaxKind.FUNCTION_DEFINITION) {
            return;
        }

        FunctionDefinitionNode functionDefinitionNode = (FunctionDefinitionNode) ctx.node();
        if (!isActivityFunction(functionDefinitionNode)) {
            return;
        }

        Optional<Symbol> optFuncSym = ctx.semanticModel().symbol(functionDefinitionNode);
        if (optFuncSym.isEmpty()) {
            return;
        }

        Symbol funcSymbol = optFuncSym.get();
        if (funcSymbol.kind() != SymbolKind.FUNCTION) {
            return;
        }

        FunctionSymbol functionSymbol = (FunctionSymbol) funcSymbol;
        functionSymbol.typeDescriptor().params().orElse(new ArrayList<>()).forEach(param -> {
            if (!param.typeDescriptor().subtypeOf(ctx.semanticModel().types().ANYDATA)) {
                updateDiagnostic(ctx, createDiagnosticLocation(param.getLocation()), WorkflowDiagnostic.WORKFLOW_107);
            }
        });
    }

    private boolean isActivityFunction(FunctionDefinitionNode functionNode) {
        if (functionNode.metadata().isEmpty()) {
            return true;
        }
        return functionNode.metadata().get().annotations().stream().anyMatch(annotation -> {
            Node annotReference = annotation.annotReference();
            if (annotReference.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                return false;
            }
            QualifiedNameReferenceNode annotationNode = (QualifiedNameReferenceNode) annotReference;
            return annotationNode.modulePrefix().text().equals(WORKFLOW) &&
                    (annotationNode.identifier().text().equals(ACTIVITY));
        });
    }
}
