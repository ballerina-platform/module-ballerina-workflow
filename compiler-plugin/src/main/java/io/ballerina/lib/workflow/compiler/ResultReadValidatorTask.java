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
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.BracedExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.compiler.syntax.tree.UnaryExpressionNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.Set;

/**
 * Validates the client-side result and status reads — {@code workflow:getResult}, {@code waitForResult},
 * {@code getStatus} and a durable agent's read methods: a module-function read inside a workflow body
 * ({@code WORKFLOW_167}), a negative literal wait bound ({@code WORKFLOW_168}), and the anti-pattern a
 * service falls into when it {@code check}s a read that answers {@code WorkflowInProgressError}
 * while the instance runs ({@code WORKFLOW_169}, a warning).
 *
 * @since 0.11.0
 */
public class ResultReadValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final Set<String> MODULE_READS = Set.of(WorkflowConstants.GET_RESULT_FUNCTION,
            WorkflowConstants.WAIT_FOR_RESULT_FUNCTION, WorkflowConstants.GET_STATUS_FUNCTION);
    private static final Set<String> NON_BLOCKING_READS = Set.of(WorkflowConstants.GET_RESULT_FUNCTION,
            WorkflowConstants.GET_DATA_RESULT_METHOD);
    private static final String CHILD_RESULT_ALTERNATIVE = "ctx->getChildWorkflowResult";
    private static final String CHILD_WAIT_ALTERNATIVE = "ctx->waitForChildWorkflow";
    // A child has no status read: its result read says whether it has finished.
    private static final String CHILD_STATUS_ALTERNATIVE = "ctx->getChildWorkflowResult, which answers "
            + "WorkflowInProgressError while the child runs (a child has no separate status read)";
    private static final int WAIT_TIMEOUT_POSITION = 1;

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        SemanticModel semanticModel = context.semanticModel();
        if (context.node() instanceof FunctionCallExpressionNode call) {
            String name = simpleNameOf(call.functionName());
            if (name == null || !MODULE_READS.contains(name)
                    || !WorkflowFunctionCallUtils.isWorkflowModuleFunctionCall(call, semanticModel, name)) {
                return;
            }
            if (WorkflowPluginUtils.isInsideWorkflowFunction(call, semanticModel)) {
                report(context, WorkflowDiagnostic.WORKFLOW_167, call.location(), name, alternativeOf(name));
                return;
            }
            if (WorkflowConstants.WAIT_FOR_RESULT_FUNCTION.equals(name)) {
                validateTimeout(context, call.arguments(), "workflow:" + name);
            }
            warnOnChecked(context, call, "workflow:" + name, NON_BLOCKING_READS.contains(name));
        } else if (context.node() instanceof MethodCallExpressionNode methodCall) {
            // By the receiver's type, so an agent reached through a field or a call counts too.
            ExpressionNode receiver = methodCall.expression();
            if (!semanticModel.typeOf(receiver).map(ResultReadValidatorTask::isDurableAgentType).orElse(false)) {
                return;
            }
            String name = methodCall.methodName().toSourceCode().strip();
            String qualified = receiver.toSourceCode().strip() + "." + name;
            if (WorkflowConstants.WAIT_FOR_RESULT_FUNCTION.equals(name)) {
                validateTimeout(context, methodCall.arguments(), qualified);
            }
            if (NON_BLOCKING_READS.contains(name)) {
                warnOnChecked(context, methodCall, qualified, true);
            }
        }
    }

    // A literal Duration with a negative field can never be a wait bound.
    private void validateTimeout(SyntaxNodeAnalysisContext context, SeparatedNodeList<FunctionArgumentNode> args,
                                 String callee) {
        ExpressionNode timeout = WorkflowFunctionCallUtils.getArgumentExpression(args, WAIT_TIMEOUT_POSITION,
                WorkflowConstants.ARG_TIMEOUT);
        if (!(timeout instanceof MappingConstructorExpressionNode mapping)) {
            return;
        }
        for (MappingFieldNode field : mapping.fields()) {
            if (field instanceof SpecificFieldNode specific && specific.valueExpr().isPresent()
                    && isNegativeLiteral(specific.valueExpr().get())
                    && specific.fieldName() instanceof Token key) {
                report(context, WorkflowDiagnostic.WORKFLOW_168, specific.location(), callee, key.text().strip());
            }
        }
    }

    private static boolean isNegativeLiteral(ExpressionNode expression) {
        ExpressionNode inner = expression;
        while (inner instanceof BracedExpressionNode braced) {
            inner = braced.expression();
        }
        return inner instanceof UnaryExpressionNode unary
                && unary.unaryOperator().kind() == SyntaxKind.MINUS_TOKEN
                && unary.expression() instanceof BasicLiteralNode;
    }

    private static String alternativeOf(String read) {
        return switch (read) {
            case WorkflowConstants.WAIT_FOR_RESULT_FUNCTION -> CHILD_WAIT_ALTERNATIVE;
            case WorkflowConstants.GET_STATUS_FUNCTION -> CHILD_STATUS_ALTERNATIVE;
            default -> CHILD_RESULT_ALTERNATIVE;
        };
    }

    private static boolean isDurableAgentType(TypeSymbol type) {
        return type instanceof TypeReferenceTypeSymbol ref
                && ref.getName().map(WorkflowConstants.DURABLE_AGENT_TYPE::equals).orElse(false)
                && ref.getModule().map(WorkflowPluginUtils::isWorkflowModule).orElse(false);
    }

    // `check` straight on a non-blocking read in a resource or remote function: a running instance
    // becomes the caller's failure. Only read sites that answer WorkflowInProgressError count.
    private void warnOnChecked(SyntaxNodeAnalysisContext context, Node call, String callee, boolean nonBlocking) {
        if (!nonBlocking) {
            return;
        }
        Node parent = call.parent();
        // `checkpanic` is a CHECK_EXPRESSION/ACTION with a different keyword, so both are covered.
        if (parent == null || (parent.kind() != SyntaxKind.CHECK_EXPRESSION
                && parent.kind() != SyntaxKind.CHECK_ACTION)) {
            return;
        }
        String kind = enclosingServiceFunctionKind(call);
        if (kind != null) {
            report(context, WorkflowDiagnostic.WORKFLOW_169, parent.location(), callee, kind);
        }
    }

    // "resource function" or "remote function" when the call sits in one, else null.
    private static String enclosingServiceFunctionKind(Node node) {
        Node current = node.parent();
        while (current != null) {
            if (current instanceof FunctionDefinitionNode function) {
                if (function.kind() == SyntaxKind.RESOURCE_ACCESSOR_DEFINITION) {
                    return "resource function";
                }
                for (Token qualifier : function.qualifierList()) {
                    if (qualifier.kind() == SyntaxKind.REMOTE_KEYWORD) {
                        return "remote function";
                    }
                }
                return null;
            }
            current = current.parent();
        }
        return null;
    }

    private static String simpleNameOf(NameReferenceNode name) {
        if (name instanceof QualifiedNameReferenceNode qualified) {
            return qualified.identifier().text();
        }
        return name instanceof SimpleNameReferenceNode simple ? simple.name().text() : null;
    }

    private void report(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic, Location location,
                        Object... args) {
        DiagnosticInfo info = new DiagnosticInfo(diagnostic.getCode(), diagnostic.getMessage(args),
                diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info, location));
    }
}
