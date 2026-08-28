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
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.CaptureBindingPatternNode;
import io.ballerina.compiler.syntax.tree.CheckExpressionNode;
import io.ballerina.compiler.syntax.tree.ExplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.VariableDeclarationNode;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Analysis task for object-model durable agent declarations:
 * {@code final workflow:DurableAgent x = new ({...})}.
 * <p>
 * Enforces placement (module-level and {@code final} — {@code WORKFLOW_149}), a statically readable
 * declaration shape — a named variable initialized inline with either constructor form,
 * {@code new ({...})} or {@code new workflow:DurableAgent({...})} ({@code WORKFLOW_151}) — and
 * capability name uniqueness across events/tools/activities/human tasks/peers
 * ({@code WORKFLOW_150}), and extracts
 * the constructor config into a {@link DurableAgentDeclInfo} so {@link WorkflowSourceModifier}
 * can generate the module-init registration.
 *
 * @since 0.9.0
 */
public class DurableAgentDeclAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final String DURABLE_AGENT_CLASS = "DurableAgent";

    private final Map<String, Object> userData;

    public DurableAgentDeclAnalysisTask(Map<String, Object> userData) {
        this.userData = userData;
    }

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (context.node() instanceof VariableDeclarationNode localVarDecl) {
            // A DurableAgent declared inside a function body has no stable module-scoped
            // identity, so the module-init registration cannot be generated for it.
            if (isDurableAgentVariable(localVarDecl.typedBindingPattern().typeDescriptor(),
                    context.semanticModel())) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_149, localVarDecl.location());
            }
            return;
        }
        if (!(context.node() instanceof ModuleVariableDeclarationNode varDecl)) {
            return;
        }
        TypeDescriptorNode typeDesc = varDecl.typedBindingPattern().typeDescriptor();
        if (!isDurableAgentVariable(typeDesc, context.semanticModel())) {
            return;
        }

        boolean isFinal = varDecl.qualifiers().stream()
                .anyMatch(qualifier -> qualifier.kind() == SyntaxKind.FINAL_KEYWORD);
        if (!isFinal) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_149, varDecl.location());
            return;
        }

        if (!(varDecl.typedBindingPattern().bindingPattern()
                instanceof CaptureBindingPatternNode capturePattern)) {
            // A wildcard/destructuring binding has no stable name to register the agent under.
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_151, varDecl.location());
            return;
        }
        String agentName = capturePattern.variableName().text();

        Optional<MappingConstructorExpressionNode> configOpt =
                findConfigMapping(varDecl.initializer().orElse(null));
        if (configOpt.isEmpty()) {
            // The initializer is not an inline `new ({...})` (factory call, variable reference,
            // conditional, named constructor arguments, ...) — the config cannot be read at
            // compile time, so no module-init registration can be generated. Without this
            // error the agent would compile cleanly and fail at runtime on its first run().
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_151, varDecl.location());
            return;
        }

        DurableAgentDeclInfo declInfo = extractDeclInfo(agentName, configOpt.get(), context);
        storeDeclInfo(context.documentId(), declInfo);
    }

    /**
     * Returns {@code true} when the type descriptor resolves to the workflow module's
     * {@code DurableAgent} class.
     */
    private boolean isDurableAgentVariable(TypeDescriptorNode typeDesc, SemanticModel semanticModel) {
        // Only name references can denote the DurableAgent class (directly or via a type
        // alias); resolution is semantic so aliases don't silently escape the validation.
        if (!(typeDesc instanceof QualifiedNameReferenceNode)
                && typeDesc.kind() != SyntaxKind.SIMPLE_NAME_REFERENCE) {
            return false;
        }
        return isDurableAgentSymbol(semanticModel.symbol(typeDesc).orElse(null));
    }

    /**
     * Returns {@code true} when the given symbol (a type reference, the class itself, or a
     * variable) resolves — through any chain of type aliases — to the workflow module's
     * {@code DurableAgent} class. Shared with {@link DurableAgentDataCallValidatorTask}.
     *
     * @param symbol the resolved symbol, or {@code null}
     * @return whether the symbol denotes a {@code workflow:DurableAgent}
     */
    static boolean isDurableAgentSymbol(Symbol symbol) {
        if (symbol == null) {
            return false;
        }
        TypeSymbol typeSymbol = null;
        if (symbol.kind() == SymbolKind.TYPE && symbol instanceof TypeSymbol ts) {
            typeSymbol = ts;
        } else if (symbol.kind() == SymbolKind.CLASS && symbol instanceof ClassSymbol classSymbol) {
            typeSymbol = classSymbol;
        } else if (symbol instanceof VariableSymbol variableSymbol) {
            typeSymbol = variableSymbol.typeDescriptor();
        }
        while (typeSymbol instanceof TypeReferenceTypeSymbol typeRef) {
            typeSymbol = typeRef.typeDescriptor();
        }
        if (!(typeSymbol instanceof ClassSymbol classSymbol)) {
            return false;
        }
        if (!DURABLE_AGENT_CLASS.equals(classSymbol.getName().orElse(""))) {
            return false;
        }
        Optional<ModuleSymbol> module = classSymbol.getModule();
        return module.isPresent() && WorkflowPluginUtils.isWorkflowModule(module.get());
    }

    /**
     * Unwraps {@code check new ({...})} / {@code new ({...})} / the explicit
     * {@code new workflow:DurableAgent({...})} form down to the config mapping.
     */
    private Optional<MappingConstructorExpressionNode> findConfigMapping(ExpressionNode initializer) {
        ExpressionNode expr = initializer;
        if (expr instanceof CheckExpressionNode checkExpr) {
            expr = checkExpr.expression();
        }
        SeparatedNodeList<FunctionArgumentNode> arguments;
        if (expr instanceof ImplicitNewExpressionNode newExpr) {
            if (newExpr.parenthesizedArgList().isEmpty()) {
                return Optional.empty();
            }
            arguments = newExpr.parenthesizedArgList().get().arguments();
        } else if (expr instanceof ExplicitNewExpressionNode explicitNewExpr) {
            arguments = explicitNewExpr.parenthesizedArgList().arguments();
        } else {
            return Optional.empty();
        }
        for (FunctionArgumentNode arg : arguments) {
            if (arg instanceof PositionalArgumentNode positionalArg
                    && positionalArg.expression() instanceof MappingConstructorExpressionNode mapping) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the declaration info from the constructor config mapping and checks capability
     * name uniqueness across the flat namespace.
     */
    private DurableAgentDeclInfo extractDeclInfo(String agentName,
                                                 MappingConstructorExpressionNode config,
                                                 SyntaxNodeAnalysisContext context) {
        String modelSource = null;
        String systemPromptSource = null;
        String maxIterSource = null;
        String inputTypeSource = null;
        String resultTypeSource = null;
        List<String> typeRefPrefixes = new ArrayList<>();
        List<DurableAgentDeclInfo.ActivityDecl> activities = new ArrayList<>();
        List<DurableAgentDeclInfo.ToolRef> aiToolRefs = new ArrayList<>();
        List<DurableAgentDeclInfo.EventDecl> events = new ArrayList<>();
        List<DurableAgentDeclInfo.HumanTaskDecl> humanTasks = new ArrayList<>();
        List<DurableAgentDeclInfo.PeerDecl> peers = new ArrayList<>();
        // Async peers' callbackChannel references, checked against the declared channels after
        // the whole config is read (the peers field may precede the events field in source).
        List<CallbackChannelRef> callbackChannels = new ArrayList<>();

        Set<String> seenNames = new HashSet<>();

        for (MappingFieldNode field : config.fields()) {
            if (!(field instanceof SpecificFieldNode specificField)
                    || specificField.valueExpr().isEmpty()) {
                continue;
            }
            String fieldName = mappingKeyName(specificField);
            if (fieldName == null) {
                continue;
            }
            ExpressionNode value = specificField.valueExpr().get();
            switch (fieldName) {
                case "model" -> modelSource = value.toSourceCode().strip();
                case "systemPrompt" -> systemPromptSource = value.toSourceCode().strip();
                case "maxIter" -> maxIterSource = value.toSourceCode().strip();
                case "inputType" -> {
                    inputTypeSource = value.toSourceCode().strip();
                    collectQualifiedPrefixes(value, typeRefPrefixes);
                }
                case "resultType" -> {
                    resultTypeSource = value.toSourceCode().strip();
                    collectQualifiedPrefixes(value, typeRefPrefixes);
                }
                case "activities" ->
                        extractActivities(value, activities, seenNames, agentName, typeRefPrefixes, context);
                case "tools" -> extractTools(value, aiToolRefs, seenNames, agentName, context);
                case "events" -> extractEvents(value, events, seenNames, agentName, context);
                case "humanTasks" -> extractHumanTasks(value, humanTasks, seenNames, agentName, context);
                case "peers" -> extractPeers(value, peers, seenNames, agentName, callbackChannels, context);
                default -> {
                    // systemPrompt/model handled above; unknown fields are the type checker's concern
                }
            }
        }

        // An async peer's reply self-injects into its callbackChannel; a channel the agent does
        // not declare would swallow the reply silently, so the reference must resolve here.
        Set<String> channelNames = new HashSet<>();
        for (DurableAgentDeclInfo.EventDecl event : events) {
            channelNames.add(event.name());
        }
        for (CallbackChannelRef callback : callbackChannels) {
            if (!channelNames.contains(callback.channel())) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_152, callback.location(),
                        agentName, callback.channel());
            }
        }

        return new DurableAgentDeclInfo(agentName, modelSource, systemPromptSource,
                maxIterSource, inputTypeSource, resultTypeSource, typeRefPrefixes, activities, aiToolRefs,
                events, humanTasks, peers);
    }

    /**
     * An async peer's {@code callbackChannel} reference.
     *
     * @param channel  the referenced channel name
     * @param location where the reference was written, for the diagnostic
     */
    private record CallbackChannelRef(String channel, Location location) { }

    /**
     * The name a mapping field is keyed by: the identifier itself, or the unquoted string
     * literal. A computed key ({@code [expr]}) has no static name and returns null.
     */
    private static String mappingKeyName(SpecificFieldNode field) {
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

    private void extractActivities(ExpressionNode value, List<DurableAgentDeclInfo.ActivityDecl> activities,
                                   Set<String> seenNames, String agentName, List<String> typeRefPrefixes,
                                   SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (member.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                    || member.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                // Bare-function shorthand: the tool name is the function's simple name.
                String refSource = member.toSourceCode().strip();
                String toolName = simpleName(refSource);
                checkUnique(toolName, seenNames, agentName, member.location(), context);
                checkActivityParametersAreData(member, toolName, agentName, Set.of(), context);
                activities.add(new DurableAgentDeclInfo.ActivityDecl(toolName, refSource, null, null));
            } else if (member instanceof MappingConstructorExpressionNode declMapping) {
                extractActivityDecl(declMapping, activities, seenNames, agentName, typeRefPrefixes, context);
            }
        }
    }

    private void extractActivityDecl(MappingConstructorExpressionNode declMapping,
                                     List<DurableAgentDeclInfo.ActivityDecl> activities,
                                     Set<String> seenNames, String agentName, List<String> typeRefPrefixes,
                                     SyntaxNodeAnalysisContext context) {
        String functionRefSource = null;
        String explicitName = null;
        StringBuilder meta = new StringBuilder();
        Location nameLocation = declMapping.location();
        Node activityRefNode = null;
        Set<String> boundParameters = Set.of();
        String bindingsSource = null;
        for (MappingFieldNode declField : declMapping.fields()) {
            if (!(declField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                continue;
            }
            String key = mappingKeyName(sf);
            if (key == null) {
                continue;
            }
            ExpressionNode declValue = sf.valueExpr().get();
            switch (key) {
                case "activity" -> {
                    functionRefSource = declValue.toSourceCode().strip();
                    activityRefNode = declValue;
                }
                case "name" -> {
                    explicitName = stringLiteralValue(declValue);
                    nameLocation = declValue.location();
                }
                // bindings may hold client objects, which are not json — they stay out of the
                // metadata and are emitted as their own argument of the registration.
                case "bindings" -> {
                    // Emitted verbatim into the generated registration: bound client objects
                    // reference their module-level variables, which are in scope there, so the
                    // prefixes of any qualified reference inside must travel with it.
                    bindingsSource = declValue.toSourceCode().strip();
                    boundParameters = boundParameterNames(declValue);
                    collectQualifiedPrefixes(declValue, typeRefPrefixes);
                }
                default -> appendMetaField(meta, key, declValue.toSourceCode().strip());
            }
        }
        if (functionRefSource == null) {
            return;
        }
        String toolName = explicitName != null ? explicitName : simpleName(functionRefSource);
        checkUnique(toolName, seenNames, agentName, nameLocation, context);
        checkActivityParametersAreData(activityRefNode == null ? declMapping : activityRefNode,
                toolName, agentName, boundParameters, context);
        activities.add(new DurableAgentDeclInfo.ActivityDecl(toolName, functionRefSource,
                meta.isEmpty() ? null : "{" + meta + "}", bindingsSource));
    }

    // Collects the module prefixes of qualified references anywhere inside a type
    // expression (generics, unions, nested types) from the parsed node — unlike a textual
    // scan, this cannot mistake mapping keys or record fields for module prefixes.
    private void collectQualifiedPrefixes(Node node, List<String> out) {
        if (node instanceof io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode qualified) {
            String prefix = qualified.modulePrefix().text().strip();
            if (!prefix.isEmpty() && !out.contains(prefix)) {
                out.add(prefix);
            }
        }
        if (node instanceof io.ballerina.compiler.syntax.tree.NonTerminalNode nonTerminal) {
            for (Node child : nonTerminal.children()) {
                collectQualifiedPrefixes(child, out);
            }
        }
    }

    private void extractTools(ExpressionNode value, List<DurableAgentDeclInfo.ToolRef> aiToolRefs,
                              Set<String> seenNames, String agentName, SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (member.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                    || member.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                String refSource = member.toSourceCode().strip();
                checkUnique(simpleName(refSource), seenNames, agentName, member.location(), context);
                checkToolAuthUnsupported(member, agentName, context);
                aiToolRefs.add(new DurableAgentDeclInfo.ToolRef(refSource, null, null));
            } else if (member instanceof MappingConstructorExpressionNode toolDecl) {
                // A ToolDecl entry: {tool: <ref>, requiresApproval: ..., userRoles: ...}. The
                // gating fields pass through to the registration call as named arguments.
                String toolRef = null;
                String approvalSource = null;
                String rolesSource = null;
                Node toolRefNode = null;
                for (MappingFieldNode field : toolDecl.fields()) {
                    if (!(field instanceof SpecificFieldNode specificField)
                            || specificField.valueExpr().isEmpty()) {
                        continue;
                    }
                    String fieldName = mappingKeyName(specificField);
                    if (fieldName == null) {
                        continue;
                    }
                    String valueSource = specificField.valueExpr().get().toSourceCode().strip();
                    switch (fieldName) {
                        case "tool" -> {
                            toolRef = valueSource;
                            toolRefNode = specificField.valueExpr().get();
                        }
                        case "requiresApproval" -> approvalSource = valueSource;
                        case "userRoles" -> rolesSource = valueSource;
                        default -> {
                        }
                    }
                }
                if (toolRef == null) {
                    continue;
                }
                checkUnique(simpleName(toolRef), seenNames, agentName, member.location(), context);
                checkToolAuthUnsupported(toolRefNode, agentName, context);
                aiToolRefs.add(new DurableAgentDeclInfo.ToolRef(toolRef, approvalSource, rolesSource));
            }
            // ai:ToolConfig / toolkit constructor expressions carry their functions by value and
            // need no module-init registration; their names are not statically resolvable here.
        }
    }

    private void extractEvents(ExpressionNode value, List<DurableAgentDeclInfo.EventDecl> events,
                               Set<String> seenNames, String agentName,
                               SyntaxNodeAnalysisContext context) {
        // Primary form: a mapping keyed by channel name — the key is a compile-time constant
        // by construction, so no name validation is needed.
        if (value instanceof MappingConstructorExpressionNode channelsMapping) {
            for (MappingFieldNode channelField : channelsMapping.fields()) {
                if (!(channelField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    // A computed key ({[expr]: ...}) has no static name, which the registration
                    // needs; a spread has no keys this pass can see at all.
                    reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_156,
                            channelField.location(), "data-event channel");
                    continue;
                }
                String name = mappingKeyName(sf);
                if (name == null || !(sf.valueExpr().get() instanceof MappingConstructorExpressionNode config)) {
                    continue;
                }
                extractEventConfig(name, sf.fieldName().location(), config, events, seenNames,
                        agentName, context);
            }
            return;
        }
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_159, value.location(),
                "events", "events", "chat");
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode eventMapping)) {
                continue;
            }
            String name = null;
            MappingConstructorExpressionNode config = eventMapping;
            Location nameLocation = eventMapping.location();
            for (MappingFieldNode eventField : eventMapping.fields()) {
                if (!(eventField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                if ("name".equals(mappingKeyName(sf))) {
                    ExpressionNode fieldValue = sf.valueExpr().get();
                    name = stringLiteralValue(fieldValue);
                    nameLocation = fieldValue.location();
                    if (name == null) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_156,
                                fieldValue.location(), "data-event channel");
                    }
                }
            }
            if (name == null) {
                continue;
            }
            extractEventConfig(name, nameLocation, config, events, seenNames, agentName, context);
        }
    }

    /**
     * Reads one channel's config fields (request/response/cardinality) and records the
     * declaration — shared by the mapping form (name = the key) and the deprecated array form
     * (name = the {@code name} field).
     */
    private void extractEventConfig(String name, Location nameLocation,
                                    MappingConstructorExpressionNode config,
                                    List<DurableAgentDeclInfo.EventDecl> events,
                                    Set<String> seenNames, String agentName,
                                    SyntaxNodeAnalysisContext context) {
        String requestSource = null;
        String responseSource = null;
        String cardinality = "MULTI_EVENT";
        for (MappingFieldNode eventField : config.fields()) {
            if (!(eventField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                continue;
            }
            String key = mappingKeyName(sf);
            if (key == null) {
                continue;
            }
            ExpressionNode fieldValue = sf.valueExpr().get();
            switch (key) {
                case "request" -> requestSource = fieldValue.toSourceCode().strip();
                case "response" -> responseSource = fieldValue.toSourceCode().strip();
                case "cardinality" -> cardinality = simpleName(fieldValue.toSourceCode().strip());
                default -> {
                    // "name" in the array form is read by the caller; no other fields
                }
            }
        }
        if (requestSource == null) {
            return;
        }
        checkUnique(name, seenNames, agentName, nameLocation, context);
        events.add(new DurableAgentDeclInfo.EventDecl(name, requestSource, responseSource,
                cardinality));
    }

    private void extractHumanTasks(ExpressionNode value, List<DurableAgentDeclInfo.HumanTaskDecl> humanTasks,
                                   Set<String> seenNames, String agentName,
                                   SyntaxNodeAnalysisContext context) {
        // Primary form: a mapping keyed by task name — constant by construction.
        if (value instanceof MappingConstructorExpressionNode tasksMapping) {
            for (MappingFieldNode taskField : tasksMapping.fields()) {
                if (!(taskField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_156,
                            taskField.location(), "human task");
                    continue;
                }
                String name = mappingKeyName(sf);
                if (name == null || !(sf.valueExpr().get() instanceof MappingConstructorExpressionNode config)) {
                    continue;
                }
                extractHumanTaskConfig(name, sf.fieldName().location(), config, humanTasks,
                        seenNames, agentName, context);
            }
            return;
        }
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_159, value.location(),
                "humanTasks", "humanTasks", "signoff");
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode taskMapping)) {
                continue;
            }
            String name = null;
            Location nameLocation = taskMapping.location();
            for (MappingFieldNode taskField : taskMapping.fields()) {
                if (!(taskField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                if ("name".equals(mappingKeyName(sf))) {
                    ExpressionNode fieldValue = sf.valueExpr().get();
                    name = stringLiteralValue(fieldValue);
                    nameLocation = fieldValue.location();
                    if (name == null) {
                        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_156,
                                fieldValue.location(), "human task");
                    }
                }
            }
            if (name == null) {
                continue;
            }
            extractHumanTaskConfig(name, nameLocation, taskMapping, humanTasks, seenNames,
                    agentName, context);
        }
    }

    /**
     * Reads one human task's config fields and records the declaration — shared by the mapping
     * form (name = the key) and the deprecated array form (name = the {@code name} field, which
     * the caller has already read and which is skipped here).
     */
    private void extractHumanTaskConfig(String name, Location nameLocation,
                                        MappingConstructorExpressionNode config,
                                        List<DurableAgentDeclInfo.HumanTaskDecl> humanTasks,
                                        Set<String> seenNames, String agentName,
                                        SyntaxNodeAnalysisContext context) {
        String resultTypeSource = null;
        StringBuilder meta = new StringBuilder();
        for (MappingFieldNode taskField : config.fields()) {
            if (!(taskField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                continue;
            }
            String key = mappingKeyName(sf);
            if (key == null) {
                continue;
            }
            ExpressionNode fieldValue = sf.valueExpr().get();
            switch (key) {
                case "name" -> {
                    // Array form only: already read by the caller.
                }
                // The result typedesc travels separately (it is not json).
                case "resultType" -> resultTypeSource = fieldValue.toSourceCode().strip();
                default -> appendMetaField(meta, key, fieldValue.toSourceCode().strip());
            }
        }
        checkUnique(name, seenNames, agentName, nameLocation, context);
        humanTasks.add(new DurableAgentDeclInfo.HumanTaskDecl(name,
                meta.isEmpty() ? null : "{" + meta + "}", resultTypeSource));
    }

    private void extractPeers(ExpressionNode value, List<DurableAgentDeclInfo.PeerDecl> peers,
                              Set<String> seenNames, String agentName,
                              List<CallbackChannelRef> callbackChannels,
                              SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode peerMapping)) {
                continue;
            }
            String name = null;
            String targetAgent = null;
            StringBuilder meta = new StringBuilder();
            Location nameLocation = peerMapping.location();
            for (MappingFieldNode peerField : peerMapping.fields()) {
                if (!(peerField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                String key = mappingKeyName(sf);
                if (key == null) {
                    continue;
                }
                ExpressionNode fieldValue = sf.valueExpr().get();
                switch (key) {
                    case "name" -> {
                        name = stringLiteralValue(fieldValue);
                        nameLocation = fieldValue.location();
                    }
                    // The peer's identity is its module-level variable name — the same name
                    // its own declaration registers under.
                    case "agent" -> targetAgent = simpleName(fieldValue.toSourceCode().strip());
                    case "callbackChannel" -> {
                        String channel = stringLiteralValue(fieldValue);
                        if (channel != null) {
                            callbackChannels.add(new CallbackChannelRef(channel, fieldValue.location()));
                        }
                        appendMetaField(meta, key, fieldValue.toSourceCode().strip());
                    }
                    default -> appendMetaField(meta, key, fieldValue.toSourceCode().strip());
                }
            }
            if (name == null || targetAgent == null) {
                continue;
            }
            checkUnique(name, seenNames, agentName, nameLocation, context);
            peers.add(new DurableAgentDeclInfo.PeerDecl(name, targetAgent,
                    meta.isEmpty() ? null : "{" + meta + "}"));
        }
    }

    // @ai:AgentTool 'auth' (OAuth scopes / Agent-ID config) is enforced by the ai:Agent run
    // loop, which the durable agent does not use — reject the declaration instead of running
    // the tool unauthenticated. Full support is tracked in ballerina-library#8978. Annotation
    // attachment values are not compile-time constants, so after resolving the reference
    // through the semantic model, the check reads the annotation syntactically from the
    // function's definition (located by the symbol's position, so qualified references and
    // same-name collisions resolve correctly). Definitions outside the current package
    // cannot be inspected syntactically and are skipped.
    private void checkToolAuthUnsupported(Node toolRefNode, String agentName,
                                          SyntaxNodeAnalysisContext context) {
        if (toolRefNode == null) {
            return;
        }
        java.util.Optional<Symbol> symbol = context.semanticModel().symbol(toolRefNode);
        if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION
                || symbol.get().getLocation().isEmpty()) {
            return;
        }
        Location definition = symbol.get().getLocation().get();
        FunctionDefinitionNode functionDef = findFunctionDefinition(context, definition);
        if (functionDef == null || functionDef.metadata().isEmpty()) {
            return;
        }
        for (AnnotationNode annotation : functionDef.metadata().get().annotations()) {
            if (!isAiAgentToolAnnotation(context, annotation) || annotation.annotValue().isEmpty()) {
                continue;
            }
            for (MappingFieldNode field : annotation.annotValue().get().fields()) {
                if (field instanceof SpecificFieldNode specificField
                        && "auth".equals(mappingKeyName(specificField))) {
                    reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_155,
                            toolRefNode.location(), functionDef.functionName().text(), agentName);
                    return;
                }
            }
        }
    }

    // Locates the function definition node holding the given symbol location (the symbol's
    // position is its name token) across all modules of the current package.
    private FunctionDefinitionNode findFunctionDefinition(SyntaxNodeAnalysisContext context,
                                                          Location definition) {
        String fileName = definition.lineRange().fileName();
        int line = definition.lineRange().startLine().line();
        for (io.ballerina.projects.Module module : context.currentPackage().modules()) {
            for (DocumentId documentId : module.documentIds()) {
                Document document = module.document(documentId);
                if (!document.name().endsWith(fileName)) {
                    continue;
                }
                ModulePartNode root = document.syntaxTree().rootNode();
                for (Node member : root.members()) {
                    if (member instanceof FunctionDefinitionNode functionDef
                            && functionDef.functionName().lineRange().startLine().line() == line) {
                        return functionDef;
                    }
                }
            }
        }
        return null;
    }

    // Resolves the annotation reference and requires ballerina/ai's AgentTool — alias imports
    // resolve correctly, and unrelated annotations that happen to be named AgentTool (or to
    // carry an auth field) do not match.
    private boolean isAiAgentToolAnnotation(SyntaxNodeAnalysisContext context, AnnotationNode annotation) {
        java.util.Optional<Symbol> symbol = context.semanticModel().symbol(annotation);
        if (symbol.isEmpty() || !(symbol.get()
                instanceof io.ballerina.compiler.api.symbols.AnnotationSymbol annotationSymbol)) {
            return false;
        }
        return annotationSymbol.getName().map("AgentTool"::equals).orElse(false)
                && annotationSymbol.getModule()
                        .map(module -> "ai".equals(module.id().moduleName())
                                && "ballerina".equals(module.id().orgName()))
                        .orElse(false);
    }

    // An agent invokes an activity with arguments the model produces, so every parameter the
    // model has to fill must be data. Client objects and other non-data parameters are only
    // usable when fixed at registration through 'bindings' — reject the declaration instead of
    // generating an agent whose tool can never be called. A parameter named by 'bindings' is
    // supplied at registration and is therefore not the model's to produce.
    private void checkActivityParametersAreData(Node activityRefNode, String toolName, String agentName,
                                                Set<String> boundParameters,
                                                SyntaxNodeAnalysisContext context) {
        if (boundParameters == null) {
            // The bindings are not a mapping constructor, so which parameters they supply is
            // not knowable here; nothing can be shown to be unbound.
            return;
        }
        java.util.Optional<Symbol> symbol = context.semanticModel().symbol(activityRefNode);
        if (symbol.isEmpty() || !(symbol.get() instanceof FunctionSymbol functionSymbol)) {
            return;
        }
        FunctionTypeSymbol typeDescriptor = functionSymbol.typeDescriptor();
        for (ParameterSymbol parameter : typeDescriptor.params().orElse(List.of())) {
            if (reportNonDataParameter(parameter, activityRefNode, toolName, agentName,
                    boundParameters, context)) {
                return;
            }
        }
        // A rest parameter is filled by the model too, so the same rule applies to it.
        typeDescriptor.restParam().ifPresent(restParam -> reportNonDataParameter(
                restParam, activityRefNode, toolName, agentName, boundParameters, context));
    }

    // Reports WORKFLOW_157 for a parameter the model can neither produce nor have bound,
    // returning whether it did.
    private boolean reportNonDataParameter(ParameterSymbol parameter, Node activityRefNode, String toolName,
                                           String agentName, Set<String> boundParameters,
                                           SyntaxNodeAnalysisContext context) {
        TypeSymbol type = parameter.typeDescriptor();
        String parameterName = parameter.getName().orElse("?");
        if (boundParameters.contains(parameterName)
                || WorkflowPluginUtils.isSubtypeOfAnydata(type, context.semanticModel())) {
            return false;
        }
        reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_157, activityRefNode.location(),
                toolName, agentName, parameterName, type.signature(),
                activityRefNode.toSourceCode().strip(), parameterName);
        return true;
    }

    /**
     * The parameter names a {@code bindings} mapping supplies at registration.
     *
     * <p>Only a mapping constructor states its keys statically. When the bindings arrive as
     * anything else — a variable reference, say — no parameter can be shown to be unbound, so
     * every parameter counts as bound rather than reporting a conflict that may not exist.
     */
    private static Set<String> boundParameterNames(ExpressionNode bindings) {
        if (!(bindings instanceof MappingConstructorExpressionNode mapping)) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (MappingFieldNode field : mapping.fields()) {
            if (field instanceof SpecificFieldNode specificField) {
                String key = mappingKeyName(specificField);
                names.add(key.length() > 1 && key.startsWith("\"") && key.endsWith("\"")
                        ? key.substring(1, key.length() - 1) : key);
            }
        }
        return names;
    }

    private void checkUnique(String name, Set<String> seenNames, String agentName, Location location,
                             SyntaxNodeAnalysisContext context) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!seenNames.add(name)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_150, location, name, agentName);
        }
    }

    private static void appendMetaField(StringBuilder meta, String key, String valueSource) {
        if (!meta.isEmpty()) {
            meta.append(", ");
        }
        meta.append(key).append(": ").append(valueSource);
    }

    /**
     * Returns the value of a string literal expression, or null when the expression is not a
     * plain string literal (template or computed names are not statically resolvable).
     */
    private static String stringLiteralValue(ExpressionNode expression) {
        if (expression.kind() == SyntaxKind.STRING_LITERAL) {
            String text = expression.toSourceCode().strip();
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length() - 1);
            }
            return text;
        }
        // A string template without interpolations is also a compile-time constant.
        if (expression.kind() == SyntaxKind.STRING_TEMPLATE_EXPRESSION) {
            String text = expression.toSourceCode().strip();
            int open = text.indexOf('`');
            int close = text.lastIndexOf('`');
            if (open >= 0 && close > open) {
                String content = text.substring(open + 1, close);
                if (!content.contains("${")) {
                    return content;
                }
            }
        }
        return null;
    }

    private static String simpleName(String refSource) {
        int colonIndex = refSource.lastIndexOf(':');
        return colonIndex >= 0 ? refSource.substring(colonIndex + 1) : refSource;
    }

    @SuppressWarnings("unchecked")
    private void storeDeclInfo(DocumentId documentId, DurableAgentDeclInfo declInfo) {
        Map<DocumentId, WorkflowModifierContext> modifierContextMap =
                (Map<DocumentId, WorkflowModifierContext>) this.userData
                        .get(WorkflowConstants.MODIFIER_CONTEXT_MAP);
        if (modifierContextMap == null) {
            return;
        }
        WorkflowModifierContext modifierContext = modifierContextMap
                .computeIfAbsent(documentId, id -> new WorkflowModifierContext());
        modifierContext.addDurableAgentDecl(declInfo);
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic,
                                  Location location, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }
}
