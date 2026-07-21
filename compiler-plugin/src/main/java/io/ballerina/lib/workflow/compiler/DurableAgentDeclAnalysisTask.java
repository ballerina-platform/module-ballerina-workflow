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
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.CaptureBindingPatternNode;
import io.ballerina.compiler.syntax.tree.CheckExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.VariableDeclarationNode;
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
 * Enforces placement (module-level and {@code final} — {@code WORKFLOW_149}) and capability name
 * uniqueness across events/tools/activities/human tasks/peers ({@code WORKFLOW_150}), and extracts
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
            return;
        }
        String agentName = capturePattern.variableName().text();
        String workflowPrefix = typeDesc instanceof QualifiedNameReferenceNode qualifiedName
                ? qualifiedName.modulePrefix().text() : null;

        Optional<MappingConstructorExpressionNode> configOpt =
                findConfigMapping(varDecl.initializer().orElse(null));
        if (configOpt.isEmpty()) {
            return;
        }

        DurableAgentDeclInfo declInfo = extractDeclInfo(agentName, workflowPrefix, configOpt.get(),
                context);
        storeDeclInfo(context.documentId(), declInfo);
    }

    /**
     * Returns {@code true} when the type descriptor resolves to the workflow module's
     * {@code DurableAgent} class.
     */
    private boolean isDurableAgentVariable(TypeDescriptorNode typeDesc, SemanticModel semanticModel) {
        String typeName = typeDesc instanceof QualifiedNameReferenceNode qualifiedName
                ? qualifiedName.identifier().text()
                : typeDesc.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE ? typeDesc.toSourceCode().strip() : null;
        if (!DURABLE_AGENT_CLASS.equals(typeName)) {
            return false;
        }
        Optional<Symbol> symbolOpt = semanticModel.symbol(typeDesc);
        if (symbolOpt.isEmpty()) {
            return false;
        }
        Symbol symbol = symbolOpt.get();
        TypeSymbol typeSymbol = null;
        if (symbol.kind() == SymbolKind.TYPE && symbol instanceof TypeSymbol ts) {
            typeSymbol = ts;
        } else if (symbol.kind() == SymbolKind.CLASS && symbol instanceof ClassSymbol classSymbol) {
            typeSymbol = classSymbol;
        } else if (symbol instanceof VariableSymbol variableSymbol) {
            typeSymbol = variableSymbol.typeDescriptor();
        }
        if (typeSymbol instanceof TypeReferenceTypeSymbol typeRef) {
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
     * Unwraps {@code check new ({...})} / {@code new ({...})} down to the config mapping.
     */
    private Optional<MappingConstructorExpressionNode> findConfigMapping(ExpressionNode initializer) {
        ExpressionNode expr = initializer;
        if (expr instanceof CheckExpressionNode checkExpr) {
            expr = checkExpr.expression();
        }
        if (!(expr instanceof ImplicitNewExpressionNode newExpr)) {
            return Optional.empty();
        }
        if (newExpr.parenthesizedArgList().isEmpty()) {
            return Optional.empty();
        }
        for (FunctionArgumentNode arg : newExpr.parenthesizedArgList().get().arguments()) {
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
    private DurableAgentDeclInfo extractDeclInfo(String agentName, String workflowPrefix,
                                                 MappingConstructorExpressionNode config,
                                                 SyntaxNodeAnalysisContext context) {
        String modelSource = null;
        String systemPromptSource = null;
        String maxIterSource = null;
        List<DurableAgentDeclInfo.ActivityDecl> activities = new ArrayList<>();
        List<String> aiToolRefs = new ArrayList<>();
        List<DurableAgentDeclInfo.EventDecl> events = new ArrayList<>();
        List<DurableAgentDeclInfo.HumanTaskDecl> humanTasks = new ArrayList<>();

        Set<String> seenNames = new HashSet<>();

        for (MappingFieldNode field : config.fields()) {
            if (!(field instanceof SpecificFieldNode specificField)
                    || specificField.valueExpr().isEmpty()) {
                continue;
            }
            String fieldName = specificField.fieldName().toSourceCode().strip();
            ExpressionNode value = specificField.valueExpr().get();
            switch (fieldName) {
                case "model" -> modelSource = value.toSourceCode().strip();
                case "systemPrompt" -> systemPromptSource = value.toSourceCode().strip();
                case "maxIter" -> maxIterSource = value.toSourceCode().strip();
                case "activities" -> extractActivities(value, activities, seenNames, agentName, context);
                case "tools" -> extractTools(value, aiToolRefs, seenNames, agentName, context);
                case "events" -> extractEvents(value, events, seenNames, agentName, context);
                case "humanTasks" -> extractHumanTasks(value, humanTasks, seenNames, agentName, context);
                case "peers" -> extractPeerNames(value, seenNames, agentName, context);
                default -> {
                    // systemPrompt/model handled above; unknown fields are the type checker's concern
                }
            }
        }

        return new DurableAgentDeclInfo(agentName, workflowPrefix, modelSource, systemPromptSource,
                maxIterSource, activities, aiToolRefs, events, humanTasks);
    }

    private void extractActivities(ExpressionNode value, List<DurableAgentDeclInfo.ActivityDecl> activities,
                                   Set<String> seenNames, String agentName,
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
                activities.add(new DurableAgentDeclInfo.ActivityDecl(toolName, refSource, null));
            } else if (member instanceof MappingConstructorExpressionNode declMapping) {
                extractActivityDecl(declMapping, activities, seenNames, agentName, context);
            }
        }
    }

    private void extractActivityDecl(MappingConstructorExpressionNode declMapping,
                                     List<DurableAgentDeclInfo.ActivityDecl> activities,
                                     Set<String> seenNames, String agentName,
                                     SyntaxNodeAnalysisContext context) {
        String functionRefSource = null;
        String explicitName = null;
        StringBuilder meta = new StringBuilder();
        Location nameLocation = declMapping.location();
        for (MappingFieldNode declField : declMapping.fields()) {
            if (!(declField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                continue;
            }
            String key = sf.fieldName().toSourceCode().strip();
            ExpressionNode declValue = sf.valueExpr().get();
            switch (key) {
                case "activity" -> functionRefSource = declValue.toSourceCode().strip();
                case "name" -> {
                    explicitName = stringLiteralValue(declValue);
                    nameLocation = declValue.location();
                }
                // bindings may hold client objects, which are not json — they stay out of the
                // metadata and are re-emitted separately when activity binding support lands.
                case "bindings" -> { }
                default -> appendMetaField(meta, key, declValue.toSourceCode().strip());
            }
        }
        if (functionRefSource == null) {
            return;
        }
        String toolName = explicitName != null ? explicitName : simpleName(functionRefSource);
        checkUnique(toolName, seenNames, agentName, nameLocation, context);
        activities.add(new DurableAgentDeclInfo.ActivityDecl(toolName, functionRefSource,
                meta.isEmpty() ? null : "{" + meta + "}"));
    }

    private void extractTools(ExpressionNode value, List<String> aiToolRefs, Set<String> seenNames,
                              String agentName, SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (member.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                    || member.kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                String refSource = member.toSourceCode().strip();
                checkUnique(simpleName(refSource), seenNames, agentName, member.location(), context);
                aiToolRefs.add(refSource);
            }
            // ai:ToolConfig / toolkit constructor expressions carry their functions by value and
            // need no module-init registration; their names are not statically resolvable here.
        }
    }

    private void extractEvents(ExpressionNode value, List<DurableAgentDeclInfo.EventDecl> events,
                               Set<String> seenNames, String agentName,
                               SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode eventMapping)) {
                continue;
            }
            String name = null;
            String requestSource = null;
            String responseSource = null;
            String cardinality = "SINGLE_EVENT";
            Location nameLocation = eventMapping.location();
            for (MappingFieldNode eventField : eventMapping.fields()) {
                if (!(eventField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                String key = sf.fieldName().toSourceCode().strip();
                ExpressionNode fieldValue = sf.valueExpr().get();
                switch (key) {
                    case "name" -> {
                        name = stringLiteralValue(fieldValue);
                        nameLocation = fieldValue.location();
                    }
                    case "request" -> requestSource = fieldValue.toSourceCode().strip();
                    case "response" -> responseSource = fieldValue.toSourceCode().strip();
                    case "cardinality" -> cardinality = simpleName(fieldValue.toSourceCode().strip());
                    default -> {
                        // no other fields
                    }
                }
            }
            if (name == null || requestSource == null) {
                continue;
            }
            checkUnique(name, seenNames, agentName, nameLocation, context);
            events.add(new DurableAgentDeclInfo.EventDecl(name, requestSource, responseSource,
                    cardinality));
        }
    }

    private void extractHumanTasks(ExpressionNode value, List<DurableAgentDeclInfo.HumanTaskDecl> humanTasks,
                                   Set<String> seenNames, String agentName,
                                   SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode taskMapping)) {
                continue;
            }
            String name = null;
            StringBuilder meta = new StringBuilder();
            Location nameLocation = taskMapping.location();
            for (MappingFieldNode taskField : taskMapping.fields()) {
                if (!(taskField instanceof SpecificFieldNode sf) || sf.valueExpr().isEmpty()) {
                    continue;
                }
                String key = sf.fieldName().toSourceCode().strip();
                ExpressionNode fieldValue = sf.valueExpr().get();
                switch (key) {
                    case "name" -> {
                        name = stringLiteralValue(fieldValue);
                        nameLocation = fieldValue.location();
                    }
                    // resultType is a typedesc and timeout a structured duration — both stay out
                    // of the json metadata; the runner reads them when task support lands.
                    case "resultType", "timeout" -> { }
                    default -> appendMetaField(meta, key, fieldValue.toSourceCode().strip());
                }
            }
            if (name == null) {
                continue;
            }
            checkUnique(name, seenNames, agentName, nameLocation, context);
            humanTasks.add(new DurableAgentDeclInfo.HumanTaskDecl(name,
                    meta.isEmpty() ? null : "{" + meta + "}"));
        }
    }

    private void extractPeerNames(ExpressionNode value, Set<String> seenNames, String agentName,
                                  SyntaxNodeAnalysisContext context) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            if (!(member instanceof MappingConstructorExpressionNode peerMapping)) {
                continue;
            }
            for (MappingFieldNode peerField : peerMapping.fields()) {
                if (peerField instanceof SpecificFieldNode sf && sf.valueExpr().isPresent()
                        && "name".equals(sf.fieldName().toSourceCode().strip())) {
                    String name = stringLiteralValue(sf.valueExpr().get());
                    if (name != null) {
                        checkUnique(name, seenNames, agentName, sf.valueExpr().get().location(), context);
                    }
                }
            }
        }
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
        if (expression.kind() != SyntaxKind.STRING_LITERAL) {
            return null;
        }
        String text = expression.toSourceCode().strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
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
