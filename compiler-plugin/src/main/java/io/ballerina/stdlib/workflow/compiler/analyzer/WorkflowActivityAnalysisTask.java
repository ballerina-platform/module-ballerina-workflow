package io.ballerina.stdlib.workflow.compiler.analyzer;

import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic;

import java.util.ArrayList;
import java.util.Optional;

import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.createDiagnosticLocation;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.isActivityFunction;
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
        FunctionSymbol functionSymbol = (FunctionSymbol) funcSymbol;
        functionSymbol.typeDescriptor().params().orElse(new ArrayList<>()).forEach(param -> {
            if (!param.typeDescriptor().subtypeOf(ctx.semanticModel().types().ANYDATA)) {
                updateDiagnostic(ctx, createDiagnosticLocation(param.getLocation()), WorkflowDiagnostic.WORKFLOW_107);
            }
        });
    }
}
