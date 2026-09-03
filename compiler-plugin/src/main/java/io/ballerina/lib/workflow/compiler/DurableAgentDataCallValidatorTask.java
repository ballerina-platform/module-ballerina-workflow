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
import io.ballerina.compiler.api.symbols.ArrayTypeSymbol;
import io.ballerina.compiler.api.symbols.IntersectionTypeSymbol;
import io.ballerina.compiler.api.symbols.MapTypeSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TupleTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
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
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.CompilationAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates {@code DurableAgent.sendData} call sites against the target agent's declared
 * data-event channels — the agent-side counterpart of {@link SendDataValidatorTask}.
 *
 * <p>All checks are keyed on the receiver object's declaration, which is the documented
 * contract: {@code sendData}'s instance ID is one this agent's {@code run} returned. An
 * instance that actually belongs to another agent is outside that contract — such a send
 * written directly on the wrong agent variable is flagged here, while one routed through a
 * parameter or local is skipped and validated at run time against the target instance's own
 * declaration.
 *
 * <p>Three rules, all limited to what is statically decidable (the agent variable is a direct
 * module-level reference and the channel name is a string literal):
 * <ul>
 *   <li>{@code WORKFLOW_152} — the channel must be declared in the agent's {@code events}.</li>
 *   <li>{@code WORKFLOW_153} — a one-way channel (no {@code response} type) produces no readable
 *       turn result, so the correlation token returned by {@code sendData} must be discarded
 *       ({@code _ = check agent.sendData(...)}); keeping it to read a data result is an error.</li>
 *   <li>{@code WORKFLOW_154} — the {@code input} payload of {@code run} must fit the agent's
 *       declared {@code inputType}: rejected outright for a query-only agent ({@code inputType:
 *       ()}), and otherwise matched against the declared type — structurally for an inline
 *       constructor, by subtyping for anything else.</li>
 * </ul>
 *
 * <p>Runs as a whole-compilation task so the agent declarations are always visible regardless of
 * document analysis order.
 */
public class DurableAgentDataCallValidatorTask implements AnalysisTask<CompilationAnalysisContext> {

    private static final String SEND_DATA_METHOD = "sendData";
    private static final String RUN_METHOD = "run";
    /** Matches the {@code org/module:version:} prefix a {@code TypeSymbol} signature carries. */
    private static final Pattern MODULE_QUALIFIER = Pattern.compile("[\\w.]+/[\\w.]+:[\\d.]+:");

    /**
     * A declared channel.
     *
     * @param name          the channel name
     * @param duplex        whether a response type (request-response channel) is declared
     * @param request       the declared request payload type symbol (best effort)
     * @param requestSource the declared request type's source text, for diagnostics
     */
    private record ChannelDecl(String name, boolean duplex, TypeSymbol request, String requestSource) { }

    /** How the agent's declared {@code inputType} constrains the {@code run} input payload. */
    private enum InputKind {
        /** {@code inputType: ()} — the agent takes only the query, no payload. */
        NONE,
        /** A JSON {@code inputType} — the payload must fit it ({@code json}, the default, fits all). */
        TYPED
    }

    /**
     * The statically collected declaration of one module-level agent.
     *
     * @param channels        declared event channels by name
     * @param inputKind       how the declared input type constrains run's payload
     * @param inputType       the declared payload type symbol (TYPED only, best effort)
     * @param inputTypeSource the declared input type's source text, for diagnostics
     */
    private record AgentSummary(Map<String, ChannelDecl> channels, InputKind inputKind,
                                TypeSymbol inputType, String inputTypeSource) { }

    @Override
    public void perform(CompilationAnalysisContext context) {
        for (Module module : context.currentPackage().modules()) {
            SemanticModel semanticModel = context.compilation().getSemanticModel(module.moduleId());
            Map<String, AgentSummary> agents = new HashMap<>();
            for (DocumentId documentId : module.documentIds()) {
                collectAgentDecl(module.document(documentId), semanticModel, agents);
            }
            if (agents.isEmpty()) {
                continue;
            }
            for (DocumentId documentId : module.documentIds()) {
                Document document = module.document(documentId);
                ((ModulePartNode) document.syntaxTree().rootNode())
                        .accept(new AgentCallVisitor(context, semanticModel, agents));
            }
        }
    }

    /**
     * Collects every module-level {@code workflow:DurableAgent} declaration in the document:
     * agent variable name → its declared event channels and input type.
     */
    private void collectAgentDecl(Document document, SemanticModel semanticModel,
                                  Map<String, AgentSummary> out) {
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
            InputKind inputKind = InputKind.TYPED;
            TypeSymbol inputType = null;
            String inputTypeSource = "json";
            for (MappingFieldNode field : config.fields()) {
                if (!(field instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                if ("inputType".equals(mappingFieldKey(sf))) {
                    ExpressionNode inputTypeExpr = sf.valueExpr().get();
                    inputTypeSource = inputTypeExpr.toSourceCode().strip();
                    if ("()".equals(inputTypeSource)) {
                        inputKind = InputKind.NONE;
                    } else {
                        inputKind = InputKind.TYPED;
                        // Builtin names resolve as readily as a type definition does, so
                        // `inputType: string` is checked like any other declared payload type.
                        // `json` (the open default) resolves too, and constrains nothing: every
                        // value `run` can be handed is already a subtype of it.
                        inputType = resolveTypeSymbol(semanticModel, inputTypeExpr);
                    }
                    continue;
                }
                if (!"events".equals(mappingFieldKey(sf))) {
                    continue;
                }
                ExpressionNode eventsValue = sf.valueExpr().get();
                // Primary form: a mapping keyed by channel name.
                if (eventsValue instanceof MappingConstructorExpressionNode channelsMapping) {
                    for (MappingFieldNode channelField : channelsMapping.fields()) {
                        if (!(channelField instanceof SpecificFieldNode ef) || ef.valueExpr().isEmpty()
                                || !(ef.valueExpr().get()
                                        instanceof MappingConstructorExpressionNode channelConfig)) {
                            continue;
                        }
                        String name = mappingFieldKey(ef);
                        if (name != null) {
                            channels.put(name, channelDecl(name, channelConfig, semanticModel));
                        }
                    }
                    continue;
                }
                // Deprecated array form: the name is the entry's `name` field.
                if (!(eventsValue instanceof ListConstructorExpressionNode list)) {
                    continue;
                }
                for (Node event : list.expressions()) {
                    if (!(event instanceof MappingConstructorExpressionNode eventMapping)) {
                        continue;
                    }
                    String name = null;
                    for (MappingFieldNode eventField : eventMapping.fields()) {
                        if (eventField instanceof SpecificFieldNode ef && ef.valueExpr().isPresent()
                                && "name".equals(mappingFieldKey(ef))) {
                            name = stringLiteralValue(ef.valueExpr().get());
                        }
                    }
                    if (name != null) {
                        channels.put(name, channelDecl(name, eventMapping, semanticModel));
                    }
                }
            }
            out.put(capture.variableName().text(),
                    new AgentSummary(channels, inputKind, inputType, inputTypeSource));
        }
    }

    /**
     * Builds a channel declaration from its config mapping — the mapping-form value, or the
     * whole array-form entry (whose extra {@code name} field is simply not a config key).
     */
    private static ChannelDecl channelDecl(String name, MappingConstructorExpressionNode config,
                                           SemanticModel semanticModel) {
        boolean duplex = false;
        TypeSymbol request = null;
        String requestSource = null;
        for (MappingFieldNode field : config.fields()) {
            if (!(field instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                continue;
            }
            String key = mappingFieldKey(sf);
            if ("response".equals(key)) {
                duplex = true;
            } else if ("request".equals(key)) {
                requestSource = sf.valueExpr().get().toSourceCode().strip();
                request = resolveTypeSymbol(semanticModel, sf.valueExpr().get());
            }
        }
        return new ChannelDecl(name, duplex, request, requestSource);
    }

    /**
     * The name a mapping field is keyed by: the identifier itself, or the unquoted string
     * literal. A computed key has no static name and returns null.
     */
    private static String mappingFieldKey(SpecificFieldNode field) {
        Node keyNode = field.fieldName();
        // Token text, not toSourceCode(): the latter carries leading trivia, so a comment
        // line above the field would become part of the "name".
        if (keyNode instanceof BasicLiteralNode literal
                && literal.kind() == SyntaxKind.STRING_LITERAL) {
            String text = literal.literalToken().text();
            return text.length() >= 2 ? text.substring(1, text.length() - 1) : null;
        }
        if (keyNode instanceof Token token) {
            String text = token.text().strip();
            if (text.startsWith("'")) {
                text = text.substring(1);
            }
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    /**
     * Resolves the type denoted by a typedesc-valued config expression (a type name used in
     * value position), unwrapping type-definition symbols to their described type.
     */
    private static TypeSymbol resolveTypeSymbol(SemanticModel semanticModel, ExpressionNode expr) {
        Symbol symbol = semanticModel.symbol(expr).orElse(null);
        if (symbol instanceof TypeDefinitionSymbol typeDefinition) {
            return typeDefinition.typeDescriptor();
        }
        if (symbol instanceof TypeSymbol typeSymbol) {
            return typeSymbol;
        }
        return null;
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

    /** Visits run/sendData method calls on known agent variables and applies the rules. */
    private static final class AgentCallVisitor extends NodeVisitor {
        private final CompilationAnalysisContext context;
        private final SemanticModel semanticModel;
        private final Map<String, AgentSummary> agents;

        AgentCallVisitor(CompilationAnalysisContext context, SemanticModel semanticModel,
                         Map<String, AgentSummary> agents) {
            this.context = context;
            this.semanticModel = semanticModel;
            this.agents = agents;
        }

        @Override
        public void visit(MethodCallExpressionNode methodCall) {
            super.visit(methodCall);
            if (!(methodCall.expression() instanceof SimpleNameReferenceNode receiver)) {
                return;
            }
            String method = methodCall.methodName().toSourceCode().strip();
            boolean sendData = SEND_DATA_METHOD.equals(method);
            boolean run = RUN_METHOD.equals(method);
            if (!sendData && !run) {
                return;
            }
            AgentSummary agent = agents.get(receiver.name().text());
            if (agent == null) {
                return; // Not a known module-level agent.
            }
            // The receiver must actually resolve to a workflow:DurableAgent — a local or
            // parameter that merely shares the module-level agent's name must not match.
            if (!DurableAgentDeclAnalysisTask.isDurableAgentSymbol(
                    semanticModel.symbol(receiver).orElse(null))) {
                return;
            }
            if (run) {
                validateRunInput(methodCall, receiver.name().text(), agent);
                return;
            }
            Map<String, ChannelDecl> channels = agent.channels();
            // sendData(instanceId, eventName, data): the channel is the second positional
            // argument (or the `eventName =` named argument), the payload the third (or
            // `data =`).
            String eventName = null;
            ExpressionNode dataArg = null;
            int positional = 0;
            for (FunctionArgumentNode arg : methodCall.arguments()) {
                if (arg instanceof PositionalArgumentNode positionalArg) {
                    positional++;
                    if (positional == 2) {
                        eventName = stringLiteralValue(positionalArg.expression());
                    } else if (positional == 3) {
                        dataArg = positionalArg.expression();
                    }
                } else if (arg instanceof NamedArgumentNode namedArg) {
                    String argName = namedArg.argumentName().name().text();
                    if ("eventName".equals(argName)) {
                        eventName = stringLiteralValue(namedArg.expression());
                    } else if ("data".equals(argName)) {
                        dataArg = namedArg.expression();
                    }
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
            // The payload must fit the channel's declared request type — the sendData
            // counterpart of run's WORKFLOW_154 input check, reported as WORKFLOW_158.
            if (dataArg != null && channel.request() != null) {
                String channelName = eventName;
                String agentName = receiver.name().text();
                checkPayload(dataArg, channel.request(), channel.requestSource(), "",
                        (location, detail) -> report(WorkflowDiagnostic.WORKFLOW_158, location,
                                channelName, agentName, detail));
            }
        }

        /**
         * Validates the {@code input} argument of {@code run(query, input)} against the
         * agent's declared {@code inputType} (WORKFLOW_154). Omitting the argument (or
         * passing an explicit nil) always starts the run on the query alone.
         */
        private void validateRunInput(MethodCallExpressionNode methodCall, String agentName,
                                      AgentSummary agent) {
            // run(query, input): the payload is the second positional argument, or an
            // explicit `input = ...` named argument.
            ExpressionNode inputArg = null;
            int positional = 0;
            for (FunctionArgumentNode arg : methodCall.arguments()) {
                if (arg instanceof PositionalArgumentNode positionalArg) {
                    positional++;
                    if (positional == 2) {
                        inputArg = positionalArg.expression();
                    }
                } else if (arg instanceof NamedArgumentNode namedArg
                        && "input".equals(namedArg.argumentName().name().text())) {
                    inputArg = namedArg.expression();
                }
            }
            if (inputArg == null || inputArg.kind() == SyntaxKind.NIL_LITERAL) {
                return;
            }
            if (agent.inputKind() == InputKind.NONE) {
                report(WorkflowDiagnostic.WORKFLOW_154, inputArg.location(), agentName,
                        "the agent takes no input payload (its inputType is '()') — pass the text in "
                                + "'query', or declare an inputType to accept a payload");
                return;
            }
            TypeSymbol declared = agent.inputType();
            if (declared == null) {
                // A type the semantic model cannot resolve: nothing to check statically, so the
                // runtime conversion is the only gate.
                return;
            }
            checkPayload(inputArg, declared, agent.inputTypeSource(), "",
                    (location, detail) -> report(WorkflowDiagnostic.WORKFLOW_154, location,
                            agentName, detail));
        }

        /**
         * Checks one payload expression against its expected type, recursing through inline
         * constructors.
         *
         * <p>An inline constructor cannot be checked with {@code subtypeOf}: it is contextually
         * typed against {@code run}'s {@code json} parameter, so its inferred type is the
         * contextual one and a correct payload would be reported as a mismatch. Instead the
         * constructor is matched against the declared type structurally — field by field for a
         * mapping, member by member for a list — which is what actually catches the mistakes a
         * developer makes here (a misspelled or missing field). Any other expression carries its
         * own declared type and is compared directly.
         *
         * @param expr     the payload expression
         * @param expected the type the payload must fit
         * @param declared the declared payload type's source text, for the diagnostic
         * @param path     dotted path of this expression inside the payload ("" at the root)
         * @param reporter receives each mismatch as (location, detail); the caller binds it to
         *                 the surface's diagnostic — WORKFLOW_154 for run's input, WORKFLOW_158
         *                 for sendData's data
         */
        private void checkPayload(ExpressionNode expr, TypeSymbol expected,
                                  String declared, String path, PayloadReporter reporter) {
            TypeSymbol target = effectiveTarget(expected);
            if (expr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR) {
                if (!WorkflowPluginUtils.canAcceptConstructorExpression(expected, expr.kind())) {
                    reportShape(expr, declared, path,
                            WorkflowPluginUtils.describeConstructorExpression(expr.kind()), expected,
                            reporter);
                    return;
                }
                checkMapping((MappingConstructorExpressionNode) expr, target, declared, path, reporter);
                return;
            }
            if (expr.kind() == SyntaxKind.LIST_CONSTRUCTOR) {
                if (!WorkflowPluginUtils.canAcceptConstructorExpression(expected, expr.kind())) {
                    reportShape(expr, declared, path,
                            WorkflowPluginUtils.describeConstructorExpression(expr.kind()), expected,
                            reporter);
                    return;
                }
                checkList((ListConstructorExpressionNode) expr, target, declared, path, reporter);
                return;
            }
            TypeSymbol actual = semanticModel.typeOf(expr).orElse(null);
            if (actual == null || actual.subtypeOf(expected)) {
                return;
            }
            reporter.accept(expr.location(), path.isEmpty()
                    ? "the payload type '" + shortSignature(actual)
                            + "' is not a subtype of the declared type '" + declared + "'"
                    : "'" + path + "' expects '" + shortSignature(expected) + "', but the payload gives '"
                            + shortSignature(actual) + "'");
        }

        /** How payload mismatches reach the caller's diagnostic. */
        @FunctionalInterface
        private interface PayloadReporter {
            void accept(Location location, String detail);
        }

        /** Matches a mapping constructor's fields against a record or map target. */
        private void checkMapping(MappingConstructorExpressionNode mapping, TypeSymbol target,
                                  String declared, String path, PayloadReporter reporter) {
            if (target instanceof MapTypeSymbol mapType) {
                // Every value shares the map's constraint, so a key this pass cannot name is
                // still checkable — only its path is unknown.
                for (MappingFieldNode field : mapping.fields()) {
                    if (field instanceof SpecificFieldNode sf && sf.valueExpr().isPresent()) {
                        String name = fieldName(sf);
                        checkPayload(sf.valueExpr().get(), mapType.typeParam(), declared,
                                name == null ? path : join(path, name), reporter);
                    }
                }
                return;
            }
            if (!(target instanceof RecordTypeSymbol recordType)) {
                return; // A union or another shape the structural match cannot decide.
            }
            Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
            boolean openTarget = recordType.restTypeDescriptor().isPresent();
            Set<String> supplied = new LinkedHashSet<>();
            // A spread (`...other`) or a computed key contributes fields this pass cannot name, so
            // a required field it might be carrying can no longer be called missing. It says
            // nothing about the fields that ARE named, though — an unknown name stays unknown and
            // a mistyped value stays mistyped — so those keep being checked, wherever in the
            // constructor they sit relative to the spread.
            boolean namesUnknownFields = false;
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode sf)) {
                    namesUnknownFields = true;
                    continue;
                }
                String name = fieldName(sf);
                if (name == null) {
                    namesUnknownFields = true;
                    continue;
                }
                supplied.add(name);
                RecordFieldSymbol declaredField = fields.get(name);
                if (declaredField == null) {
                    if (!openTarget) {
                        reporter.accept(sf.location(),
                                "the declared type '" + declared + "' has no field '"
                                        + join(path, name) + "'");
                    }
                    continue;
                }
                // A shorthand field (`{qty}`) carries its value in a variable of the same name;
                // the name check above is all this pass can do for it.
                if (sf.valueExpr().isPresent()) {
                    checkPayload(sf.valueExpr().get(), declaredField.typeDescriptor(),
                            declared, join(path, name), reporter);
                }
            }
            if (namesUnknownFields) {
                return;
            }
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, RecordFieldSymbol> entry : fields.entrySet()) {
                RecordFieldSymbol field = entry.getValue();
                if (!field.isOptional() && !field.hasDefaultValue() && !supplied.contains(entry.getKey())) {
                    missing.add(join(path, entry.getKey()));
                }
            }
            if (!missing.isEmpty()) {
                reporter.accept(mapping.location(),
                        "the declared type '" + declared + "' requires "
                                + (missing.size() == 1 ? "the field " : "the fields ")
                                + quoteAll(missing) + ", which the payload does not set");
            }
        }

        /** Matches a list constructor's members against an array or tuple target. */
        private void checkList(ListConstructorExpressionNode list, TypeSymbol target,
                               String declared, String path, PayloadReporter reporter) {
            List<Node> members = new ArrayList<>();
            boolean spread = false;
            for (Node member : list.expressions()) {
                // A spread member (`...rest`) contributes an unknown number of values, so
                // neither the arity nor the per-position types can be decided any more.
                spread |= member.kind() == SyntaxKind.SPREAD_MEMBER;
                members.add(member);
            }
            if (spread) {
                return;
            }
            if (target instanceof ArrayTypeSymbol arrayType) {
                Integer fixedLength = arrayType.size().orElse(null);
                if (fixedLength != null && fixedLength >= 0 && fixedLength != members.size()) {
                    reporter.accept(list.location(),
                            position(path, declared) + " expects " + fixedLength + " members, but "
                                    + members.size() + " were given");
                    return;
                }
                for (int i = 0; i < members.size(); i++) {
                    if (members.get(i) instanceof ExpressionNode memberExpr) {
                        checkPayload(memberExpr, arrayType.memberTypeDescriptor(), declared,
                                path + "[" + i + "]", reporter);
                    }
                }
                return;
            }
            if (!(target instanceof TupleTypeSymbol tupleType)) {
                return;
            }
            List<TypeSymbol> memberTypes = tupleType.memberTypeDescriptors();
            boolean openTuple = tupleType.restTypeDescriptor().isPresent();
            if (members.size() < memberTypes.size() || (!openTuple && members.size() > memberTypes.size())) {
                reporter.accept(list.location(),
                        position(path, declared) + " expects " + memberTypes.size()
                                + (openTuple ? " or more members" : " members") + ", but "
                                + members.size() + " were given");
                return;
            }
            for (int i = 0; i < members.size(); i++) {
                TypeSymbol memberType = i < memberTypes.size()
                        ? memberTypes.get(i) : tupleType.restTypeDescriptor().orElse(null);
                if (memberType != null && members.get(i) instanceof ExpressionNode memberExpr) {
                    checkPayload(memberExpr, memberType, declared, path + "[" + i + "]", reporter);
                }
            }
        }

        private void reportShape(ExpressionNode expr, String declared, String path,
                                 String givenShape, TypeSymbol expected, PayloadReporter reporter) {
            reporter.accept(expr.location(), path.isEmpty()
                    ? "the declared type '" + declared + "' does not accept " + givenShape
                    : "'" + path + "' expects '" + shortSignature(expected) + "', which does not accept "
                            + givenShape);
        }

        /**
         * The type a payload value has to match, with the wrappers that do not change its shape
         * removed: a type reference, and the {@code readonly} half of an intersection. Without the
         * latter, {@code readonly & OrderInput} clears the shape gate and then reaches neither the
         * record nor the map branch, so its fields go unchecked.
         */
        private static TypeSymbol effectiveTarget(TypeSymbol type) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(type);
            if (resolved instanceof IntersectionTypeSymbol intersection) {
                for (TypeSymbol member : intersection.memberTypeDescriptors()) {
                    TypeSymbol effective = WorkflowPluginUtils.resolveTypeReference(member);
                    if (effective.typeKind() != TypeDescKind.READONLY) {
                        return effectiveTarget(effective);
                    }
                }
            }
            return resolved;
        }

        /** Names the payload position a diagnostic is about — the declared type at the root. */
        private static String position(String path, String declared) {
            return path.isEmpty() ? "the declared type '" + declared + "'" : "'" + path + "'";
        }

        /**
         * A type signature without its {@code org/module:version:} qualifiers, which a diagnostic
         * about the developer's own payload does not need and which would tie a test assertion to
         * the package version.
         */
        private static String shortSignature(TypeSymbol type) {
            return MODULE_QUALIFIER.matcher(type.signature()).replaceAll("");
        }

        private static String join(String path, String name) {
            return path.isEmpty() ? name : path + "." + name;
        }

        private static String quoteAll(List<String> names) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    builder.append(i == names.size() - 1 ? " and " : ", ");
                }
                builder.append('\'').append(names.get(i)).append('\'');
            }
            return builder.toString();
        }

        /** The field's name, unquoting the string-literal key form; null when it is computed. */
        private static String fieldName(SpecificFieldNode field) {
            return mappingFieldKey(field);
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
