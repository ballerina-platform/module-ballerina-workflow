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

package io.ballerina.lib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.AssignmentStatementNode;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.CheckExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.CompilationAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates {@code DurableAgent.sendData} call sites against the target agent's declared
 * data-event channels — the agent-side counterpart of {@link SendDataValidatorTask}.
 *
 * <p>Two rules, both limited to what is statically decidable (the agent variable is a direct
 * module-level reference and the channel name is a string literal):
 * <ul>
 *   <li>{@code WORKFLOW_152} — the channel must be declared in the agent's {@code events}.</li>
 *   <li>{@code WORKFLOW_153} — a one-way channel (no {@code response} type) produces no readable
 *       turn result, so the correlation token returned by {@code sendData} must be discarded
 *       ({@code _ = check agent.sendData(...)}); keeping it to read a data result is an error.</li>
 * </ul>
 *
 * <p>Runs as a whole-compilation task so the agent declarations are always visible regardless of
 * document analysis order.
 */
public class DurableAgentDataCallValidatorTask implements AnalysisTask<CompilationAnalysisContext> {

    private static final String SEND_DATA_METHOD = "sendData";

    /**
     * A declared channel.
     *
     * @param name   the channel name
     * @param duplex whether a response type (request-response channel) is declared
     */
    private record ChannelDecl(String name, boolean duplex) { }

    @Override
    public void perform(CompilationAnalysisContext context) {
        for (Module module : context.currentPackage().modules()) {
            SemanticModel semanticModel = context.compilation().getSemanticModel(module.moduleId());
            Map<String, Map<String, ChannelDecl>> agentChannels = new HashMap<>();
            for (DocumentId documentId : module.documentIds()) {
                collectAgentChannels(module.document(documentId), semanticModel, agentChannels);
            }
            if (agentChannels.isEmpty()) {
                continue;
            }
            for (DocumentId documentId : module.documentIds()) {
                Document document = module.document(documentId);
                ((ModulePartNode) document.syntaxTree().rootNode())
                        .accept(new SendDataCallVisitor(context, semanticModel, agentChannels));
            }
        }
    }

    /**
     * Collects the declared event channels of every module-level {@code workflow:DurableAgent}
     * declaration in the document: agent variable name → channel name → declaration.
     */
    private void collectAgentChannels(Document document, SemanticModel semanticModel,
                                      Map<String, Map<String, ChannelDecl>> out) {
        ModulePartNode root = (ModulePartNode) document.syntaxTree().rootNode();
        for (Node member : root.members()) {
            if (!(member instanceof ModuleVariableDeclarationNode varDecl)
                    || !(varDecl.typedBindingPattern().bindingPattern()
                            instanceof io.ballerina.compiler.syntax.tree.CaptureBindingPatternNode capture)) {
                continue;
            }
            // Semantic resolution (shared with the declaration analysis) so type aliases are
            // recognized and unrelated types whose names merely end with "DurableAgent" are not.
            if (!DurableAgentDeclAnalysisTask.isDurableAgentSymbol(
                    semanticModel.symbol(varDecl.typedBindingPattern().typeDescriptor()).orElse(null))) {
                continue;
            }
            MappingConstructorExpressionNode config = findConfigMapping(varDecl.initializer().orElse(null));
            if (config == null) {
                continue;
            }
            Map<String, ChannelDecl> channels = new HashMap<>();
            for (MappingFieldNode field : config.fields()) {
                if (!(field instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()
                        || !"events".equals(sf.fieldName().toSourceCode().strip())) {
                    continue;
                }
                if (!(sf.valueExpr().get() instanceof ListConstructorExpressionNode list)) {
                    continue;
                }
                for (Node event : list.expressions()) {
                    if (!(event instanceof MappingConstructorExpressionNode eventMapping)) {
                        continue;
                    }
                    String name = null;
                    boolean duplex = false;
                    for (MappingFieldNode eventField : eventMapping.fields()) {
                        if (!(eventField instanceof SpecificFieldNode ef) || ef.valueExpr().isEmpty()) {
                            continue;
                        }
                        String key = ef.fieldName().toSourceCode().strip();
                        if ("name".equals(key)) {
                            name = stringLiteralValue(ef.valueExpr().get());
                        } else if ("response".equals(key)) {
                            duplex = true;
                        }
                    }
                    if (name != null) {
                        channels.put(name, new ChannelDecl(name, duplex));
                    }
                }
            }
            out.put(capture.variableName().text(), channels);
        }
    }

    private MappingConstructorExpressionNode findConfigMapping(ExpressionNode initializer) {
        ExpressionNode expr = initializer;
        if (expr instanceof CheckExpressionNode checkExpr) {
            expr = checkExpr.expression();
        }
        io.ballerina.compiler.syntax.tree.SeparatedNodeList<FunctionArgumentNode> arguments;
        if (expr instanceof io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode newExpr) {
            if (newExpr.parenthesizedArgList().isEmpty()) {
                return null;
            }
            arguments = newExpr.parenthesizedArgList().get().arguments();
        } else if (expr instanceof io.ballerina.compiler.syntax.tree.ExplicitNewExpressionNode explicitNew) {
            arguments = explicitNew.parenthesizedArgList().arguments();
        } else {
            return null;
        }
        for (FunctionArgumentNode arg : arguments) {
            if (arg instanceof PositionalArgumentNode positional
                    && positional.expression() instanceof MappingConstructorExpressionNode mapping) {
                return mapping;
            }
        }
        return null;
    }

    private static String stringLiteralValue(ExpressionNode expr) {
        if (expr instanceof BasicLiteralNode literal
                && literal.kind() == SyntaxKind.STRING_LITERAL) {
            String text = literal.literalToken().text();
            return text.length() >= 2 ? text.substring(1, text.length() - 1) : null;
        }
        return null;
    }

    /** Visits sendData method calls on known agent variables and applies the two rules. */
    private static final class SendDataCallVisitor extends NodeVisitor {
        private final CompilationAnalysisContext context;
        private final SemanticModel semanticModel;
        private final Map<String, Map<String, ChannelDecl>> agentChannels;

        SendDataCallVisitor(CompilationAnalysisContext context, SemanticModel semanticModel,
                            Map<String, Map<String, ChannelDecl>> agentChannels) {
            this.context = context;
            this.semanticModel = semanticModel;
            this.agentChannels = agentChannels;
        }

        @Override
        public void visit(MethodCallExpressionNode methodCall) {
            super.visit(methodCall);
            if (!(methodCall.expression() instanceof SimpleNameReferenceNode receiver)
                    || !SEND_DATA_METHOD.equals(methodCall.methodName().toSourceCode().strip())) {
                return;
            }
            Map<String, ChannelDecl> channels = agentChannels.get(receiver.name().text());
            if (channels == null) {
                return; // Not a known module-level agent.
            }
            // The receiver must actually resolve to a workflow:DurableAgent — a local or
            // parameter that merely shares the module-level agent's name must not match.
            if (!DurableAgentDeclAnalysisTask.isDurableAgentSymbol(
                    semanticModel.symbol(receiver).orElse(null))) {
                return;
            }
            // sendData(instanceId, eventName, data): the channel is the second positional
            // argument, or an explicit `eventName = ...` named argument.
            String eventName = null;
            int positional = 0;
            for (FunctionArgumentNode arg : methodCall.arguments()) {
                if (arg instanceof PositionalArgumentNode positionalArg) {
                    positional++;
                    if (positional == 2) {
                        eventName = stringLiteralValue(positionalArg.expression());
                    }
                } else if (arg instanceof NamedArgumentNode namedArg
                        && "eventName".equals(namedArg.argumentName().name().text())) {
                    eventName = stringLiteralValue(namedArg.expression());
                }
            }
            if (eventName == null) {
                return; // Dynamic channel names are validated at runtime.
            }
            ChannelDecl channel = channels.get(eventName);
            if (channel == null) {
                report(WorkflowDiagnostic.WORKFLOW_152, methodCall.location(),
                        receiver.name().text(), eventName);
                return;
            }
            if (!channel.duplex() && !isTokenDiscarded(methodCall)) {
                report(WorkflowDiagnostic.WORKFLOW_153, methodCall.location(),
                        eventName, receiver.name().text());
            }
        }

        /** True when the sendData result is discarded with {@code _ = ...}. */
        private static boolean isTokenDiscarded(MethodCallExpressionNode methodCall) {
            Node node = methodCall.parent();
            while (node instanceof CheckExpressionNode) {
                node = node.parent();
            }
            return node instanceof AssignmentStatementNode assignment
                    && assignment.varRef().kind() == SyntaxKind.WILDCARD_BINDING_PATTERN;
        }

        private void report(WorkflowDiagnostic diagnostic, Location location, Object... args) {
            DiagnosticInfo info = new DiagnosticInfo(
                    diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info, location));
        }
    }
}
