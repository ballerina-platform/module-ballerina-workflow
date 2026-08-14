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

package io.ballerina.lib.workflow.compiler.descriptor;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeDescTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.CheckExpressionNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ImplicitNewExpressionNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.VariableDeclarationNode;
import io.ballerina.lib.workflow.compiler.WorkflowConstants;
import io.ballerina.lib.workflow.compiler.WorkflowPluginUtils;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the Workflow Definition Descriptor ({@code workflow.def.json}) from the compiled
 * package: every workflow with its input, events, activities (input and output), human tasks
 * (result form) and review activities, plus every durable-agent declaration's static structure.
 * Invoked by {@link WorkflowDescriptorGenerator}, which packs the canonical bytes as a package
 * resource — present in the executable JAR, the BALA, and {@code bal test} runs alike.
 *
 * <p>Captures only the structural facts — what registers with the Temporal runtime, plus
 * schemas. Expression-valued instance parameters (roles, titles, retry counts, timeouts,
 * payloads) are deliberately not captured.
 *
 * @since 0.9.0
 */
public final class WorkflowDescriptorBuilder {

    public static final String DESCRIPTOR_VERSION = "1.0";

    private static final String DURABLE_AGENT_TYPE = "DurableAgent";

    private WorkflowDescriptorBuilder() {
    }

    /**
     * Builds the descriptor's canonical bytes for a package.
     *
     * @param currentPackage the package being compiled
     * @param compilation    its compilation (for semantic models)
     * @return the canonical descriptor bytes, or {@code null} when the package declares no
     *         workflows or agents
     */
    public static byte[] build(Package currentPackage, PackageCompilation compilation) {
        String major = majorVersion(currentPackage.packageVersion().value().toString());

        Map<String, Map<String, Object>> workflows = new TreeMap<>();
        Map<String, Map<String, Object>> agents = new TreeMap<>();

        for (ModuleId moduleId : currentPackage.moduleIds()) {
            Module module = currentPackage.module(moduleId);
            SemanticModel semanticModel = compilation.getSemanticModel(moduleId);
            String moduleQName = currentPackage.packageOrg().value() + "/" + module.moduleName().toString();
            for (DocumentId documentId : module.documentIds()) {
                ModulePartNode root = module.document(documentId).syntaxTree().rootNode();
                for (ModuleMemberDeclarationNode member : root.members()) {
                    if (member instanceof FunctionDefinitionNode fnDef
                            && WorkflowPluginUtils.hasWorkflowAnnotation(fnDef, semanticModel,
                                    WorkflowConstants.PROCESS_ANNOTATION)) {
                        Map<String, Object> entry =
                                buildWorkflowEntry(fnDef, semanticModel, moduleQName, major);
                        if (entry != null) {
                            workflows.put((String) entry.get("name"), entry);
                        }
                    } else if (member instanceof ModuleVariableDeclarationNode varDecl) {
                        Map<String, Object> agent = buildAgentEntry(varDecl, semanticModel);
                        if (agent != null) {
                            agents.put((String) agent.get("name"), agent);
                        }
                    }
                }
            }
        }

        if (workflows.isEmpty() && agents.isEmpty()) {
            return null;
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("descriptorVersion", DESCRIPTOR_VERSION);
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("org", currentPackage.packageOrg().value());
        pkg.put("name", currentPackage.packageName().value());
        pkg.put("version", currentPackage.packageVersion().value().toString());
        document.put("package", pkg);
        document.put("workflows", new ArrayList<>(workflows.values()));
        document.put("agents", new ArrayList<>(agents.values()));

        return DescriptorJson.withChecksum(document);
    }

    // ------------------------------------------------------------------
    // Workflows
    // ------------------------------------------------------------------

    private static Map<String, Object> buildWorkflowEntry(FunctionDefinitionNode fnDef, SemanticModel semanticModel,
                                                   String moduleQName, String major) {
        Optional<Symbol> symbol = semanticModel.symbol(fnDef);
        if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION) {
            return null;
        }
        FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
        String name = fnDef.functionName().text();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("kind", "WORKFLOW");
        entry.put("function", functionRef(moduleQName, major, name));

        List<ParameterSymbol> inputParams = new ArrayList<>();
        RecordTypeSymbol eventsRecord = null;
        for (ParameterSymbol param : fnSymbol.typeDescriptor().params().orElse(List.of())) {
            TypeSymbol effective = DescriptorSchemaGen.dereference(param.typeDescriptor(), 0);
            if (effective == null) {
                continue;
            }
            if (effective.typeKind() == TypeDescKind.OBJECT) {
                continue; // the workflow:Context parameter
            }
            if (effective.typeKind() == TypeDescKind.RECORD
                    && isEventsRecord((RecordTypeSymbol) effective)) {
                eventsRecord = (RecordTypeSymbol) effective;
                continue;
            }
            inputParams.add(param);
        }
        entry.put("input", DescriptorSchemaGen.parameterSlot(inputParams));
        entry.put("events", buildWorkflowEvents(eventsRecord));

        WorkflowBodyCollector collector = new WorkflowBodyCollector(semanticModel);
        fnDef.functionBody().accept(collector);
        entry.put("activities", new ArrayList<>(collector.activities.values()));
        entry.put("humanTasks", buildHumanTasks(collector.humanTaskResults));
        entry.put("reviewActivities", buildReviewActivities(collector.reviewedActivities));
        return entry;
    }

    private static boolean isEventsRecord(RecordTypeSymbol record) {
        Map<String, RecordFieldSymbol> fields = record.fieldDescriptors();
        if (fields.isEmpty()) {
            return false;
        }
        for (RecordFieldSymbol field : fields.values()) {
            TypeSymbol fieldType = DescriptorSchemaGen.dereference(field.typeDescriptor(), 0);
            if (fieldType == null || fieldType.typeKind() != TypeDescKind.FUTURE) {
                return false;
            }
        }
        return true;
    }

    private static List<Object> buildWorkflowEvents(RecordTypeSymbol eventsRecord) {
        List<Object> events = new ArrayList<>();
        if (eventsRecord == null) {
            return events;
        }
        for (Map.Entry<String, RecordFieldSymbol> field
                : new TreeMap<>(eventsRecord.fieldDescriptors()).entrySet()) {
            TypeSymbol fieldType = DescriptorSchemaGen.dereference(field.getValue().typeDescriptor(), 0);
            TypeSymbol payloadType = null;
            if (fieldType instanceof io.ballerina.compiler.api.symbols.FutureTypeSymbol future) {
                payloadType = future.typeParameter().orElse(null);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("name", field.getKey());
            event.put("direction", "IN");
            event.put("cardinality", "SINGLE");
            event.put("payload", DescriptorSchemaGen.slot(payloadType));
            events.add(event);
        }
        return events;
    }

    private static List<Object> buildHumanTasks(Map<String, TypeSymbol> humanTaskResults) {
        List<Object> tasks = new ArrayList<>();
        for (Map.Entry<String, TypeSymbol> entry : humanTaskResults.entrySet()) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("name", entry.getKey());
            task.put("result", DescriptorSchemaGen.slot(entry.getValue()));
            tasks.add(task);
        }
        return tasks;
    }

    private static List<Object> buildReviewActivities(Map<String, Set<String>> reviewedActivities) {
        List<Object> reviews = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : reviewedActivities.entrySet()) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("activity", entry.getKey());
            review.put("triggers", new ArrayList<>(entry.getValue()));
            reviews.add(review);
        }
        return reviews;
    }

    private static Map<String, Object> functionRef(String moduleQName, String major, String functionName) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("module", moduleQName);
        ref.put("version", major);
        ref.put("name", functionName);
        return ref;
    }

    /**
     * Collects the structural facts inside one workflow function body: activity calls (direct
     * and via {@code ctx->callActivity}), {@code awaitHumanTask} sites with their statically
     * declared result types, and activities gated by a {@code HumanReview} retry policy.
     */
    private static final class WorkflowBodyCollector extends NodeVisitor {

        private final SemanticModel semanticModel;
        final Map<String, Map<String, Object>> activities = new TreeMap<>();
        final Map<String, TypeSymbol> humanTaskResults = new TreeMap<>();
        final Map<String, Set<String>> reviewedActivities = new TreeMap<>();

        WorkflowBodyCollector(SemanticModel semanticModel) {
            this.semanticModel = semanticModel;
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbol = semanticModel.symbol(callNode);
            if (symbol.isPresent() && symbol.get().kind() == SymbolKind.FUNCTION) {
                FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
                if (WorkflowPluginUtils.hasWorkflowAnnotation(fnSymbol, WorkflowConstants.ACTIVITY_ANNOTATION)) {
                    addActivity(fnSymbol);
                }
            }
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        @Override
        public void visit(RemoteMethodCallActionNode remoteCall) {
            String methodName = remoteCall.methodName().name().text();
            if (WorkflowConstants.CALL_ACTIVITY_FUNCTION.equals(methodName)) {
                collectCallActivity(remoteCall);
            } else if (WorkflowConstants.CALL_HUMAN_TASK_METHOD.equals(methodName)) {
                collectAwaitHumanTask(remoteCall);
            }
            remoteCall.arguments().forEach(arg -> arg.accept(this));
        }

        private void collectCallActivity(RemoteMethodCallActionNode remoteCall) {
            SeparatedNodeList<FunctionArgumentNode> args = remoteCall.arguments();
            if (args.isEmpty() || !(args.get(0) instanceof PositionalArgumentNode posArg)) {
                return;
            }
            Optional<Symbol> symbol = semanticModel.symbol(posArg.expression());
            if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION) {
                return;
            }
            FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
            String activityName = addActivity(fnSymbol);
            if (activityName != null && hasHumanReviewRetryPolicy(args)) {
                reviewedActivities.computeIfAbsent(activityName, k -> new LinkedHashSet<>())
                        .add("ON_FAILURE");
            }
        }

        /**
         * True when the call site passes a {@code retryPolicy} whose static type is the
         * {@code HumanReview} shape ({@code string|string[]} — the reviewer roles). An
         * {@code AutoRetry}/{@code NoAutomaticRetry} record, or a type too dynamic to
         * classify, does not create a review activity in the descriptor.
         */
        private boolean hasHumanReviewRetryPolicy(SeparatedNodeList<FunctionArgumentNode> args) {
            for (FunctionArgumentNode arg : args) {
                if (arg instanceof NamedArgumentNode named
                        && "retryPolicy".equals(named.argumentName().name().text())) {
                    return isHumanReviewTyped(named.expression());
                }
            }
            return false;
        }

        private boolean isHumanReviewTyped(ExpressionNode expression) {
            Optional<TypeSymbol> typeOpt = semanticModel.typeOf(expression);
            if (typeOpt.isEmpty()) {
                return false;
            }
            TypeSymbol raw = typeOpt.get();
            if (raw instanceof io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol ref
                    && ref.getName().map("HumanReview"::equals).orElse(false)) {
                return true;
            }
            return isStringOrStringArray(DescriptorSchemaGen.dereference(raw, 0), 0);
        }

        private boolean isStringOrStringArray(TypeSymbol type, int depth) {
            if (type == null || depth > 6) {
                return false;
            }
            switch (type.typeKind()) {
                case STRING, STRING_CHAR, SINGLETON -> {
                    return true;
                }
                case ARRAY -> {
                    return isStringOrStringArray(DescriptorSchemaGen.dereference(
                            ((io.ballerina.compiler.api.symbols.ArrayTypeSymbol) type)
                                    .memberTypeDescriptor(), 0), depth + 1);
                }
                case UNION -> {
                    for (TypeSymbol member
                            : ((io.ballerina.compiler.api.symbols.UnionTypeSymbol) type)
                                    .memberTypeDescriptors()) {
                        if (!isStringOrStringArray(DescriptorSchemaGen.dereference(member, 0), depth + 1)) {
                            return false;
                        }
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void collectAwaitHumanTask(RemoteMethodCallActionNode remoteCall) {
            String taskName = extractConstantTaskName(remoteCall.arguments());
            if (taskName == null || taskName.contains(".") || taskName.contains("|")) {
                return; // non-constant / invalid names are diagnosed by the process analysis task
            }
            TypeSymbol resultType = declaredResultType(remoteCall);
            if (!humanTaskResults.containsKey(taskName)) {
                humanTaskResults.put(taskName, resultType);
                return;
            }
            TypeSymbol existing = humanTaskResults.get(taskName);
            if (existing == null) {
                humanTaskResults.put(taskName, resultType);
            } else if (resultType != null && !existing.signature().equals(resultType.signature())) {
                // Conflicting declared result types across call sites: unknown.
                humanTaskResults.put(taskName, null);
            }
        }

        /** The statically declared result type at the call site's enclosing variable declaration. */
        private TypeSymbol declaredResultType(RemoteMethodCallActionNode remoteCall) {
            Node parent = remoteCall.parent();
            while (parent != null && (parent.kind() == SyntaxKind.CHECK_ACTION
                    || parent.kind() == SyntaxKind.CHECK_EXPRESSION)) {
                parent = parent.parent();
            }
            if (!(parent instanceof VariableDeclarationNode varDecl)) {
                return null;
            }
            Optional<Symbol> symbol = semanticModel.symbol(varDecl);
            if (symbol.isPresent() && symbol.get() instanceof VariableSymbol varSymbol) {
                return varSymbol.typeDescriptor();
            }
            return null;
        }

        private String extractConstantTaskName(SeparatedNodeList<FunctionArgumentNode> args) {
            for (FunctionArgumentNode arg : args) {
                if (arg instanceof NamedArgumentNode named
                        && "taskName".equals(named.argumentName().name().text())) {
                    return constantStringValue(named.expression());
                }
            }
            if (!args.isEmpty() && args.get(0) instanceof PositionalArgumentNode posArg) {
                return constantStringValue(posArg.expression());
            }
            return null;
        }

        /** Adds one activity entry (idempotent by name); returns the activity name. */
        private String addActivity(FunctionSymbol fnSymbol) {
            String name = fnSymbol.getName().orElse(null);
            if (name == null) {
                return null;
            }
            activities.computeIfAbsent(name, n -> buildActivityEntry(n, fnSymbol));
            return name;
        }

        private Map<String, Object> buildActivityEntry(String name, FunctionSymbol fnSymbol) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            Optional<ModuleSymbol> moduleSymbol = fnSymbol.getModule();
            if (moduleSymbol.isPresent()) {
                String moduleQName = moduleSymbol.get().id().orgName() + "/"
                        + moduleSymbol.get().id().moduleName();
                entry.put("function",
                        functionRef(moduleQName, majorVersion(moduleSymbol.get().id().version()), name));
            }
            entry.put("input", DescriptorSchemaGen.parameterSlot(
                    dataParameters(fnSymbol.typeDescriptor().params().orElse(List.of()))));
            entry.put("output", DescriptorSchemaGen.outputSlot(
                    fnSymbol.typeDescriptor().returnTypeDescriptor().orElse(null)));
            return entry;
        }
    }

    /** Filters an activity's parameters to its data parameters (skips typedescs and client objects). */
    private static List<ParameterSymbol> dataParameters(List<ParameterSymbol> params) {
        List<ParameterSymbol> dataParams = new ArrayList<>();
        for (ParameterSymbol param : params) {
            TypeSymbol effective = DescriptorSchemaGen.dereference(param.typeDescriptor(), 0);
            if (effective != null && (effective.typeKind() == TypeDescKind.TYPEDESC
                    || effective.typeKind() == TypeDescKind.OBJECT)) {
                continue;
            }
            dataParams.add(param);
        }
        return dataParams;
    }

    // ------------------------------------------------------------------
    // Durable agents
    // ------------------------------------------------------------------

    private static Map<String, Object> buildAgentEntry(ModuleVariableDeclarationNode varDecl,
                                                SemanticModel semanticModel) {
        Optional<Symbol> symbol = semanticModel.symbol(varDecl);
        if (symbol.isEmpty() || !(symbol.get() instanceof VariableSymbol varSymbol)) {
            return null;
        }
        if (!isDurableAgentType(varSymbol.typeDescriptor())) {
            return null;
        }
        String agentName = varSymbol.getName().orElse(null);
        if (agentName == null) {
            return null;
        }
        MappingConstructorExpressionNode config = agentConfigMapping(varDecl);

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", agentName);

        Map<String, Object> inputSlot = defaultStringSlot();
        Map<String, Object> resultSlot = defaultNilSlot();
        List<Object> events = new ArrayList<>();
        List<Object> humanTasks = new ArrayList<>();
        Map<String, Map<String, Object>> tools = new TreeMap<>();

        if (config != null) {
            for (MappingFieldNode field : config.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = specific.fieldName().toSourceCode().trim();
                ExpressionNode value = specific.valueExpr().get();
                switch (fieldName) {
                    case "inputType" -> inputSlot = typedescSlot(semanticModel, value, defaultStringSlot());
                    case "resultType" -> resultSlot = typedescSlot(semanticModel, value, defaultNilSlot());
                    case "events" -> events = buildAgentEvents(semanticModel, value);
                    case "humanTasks" -> humanTasks = buildAgentHumanTasks(semanticModel, value);
                    case "activities" -> collectAgentActivities(semanticModel, value, tools);
                    case "tools" -> collectAgentTools(semanticModel, value, tools);
                    case "peers" -> collectAgentPeers(value, tools);
                    default -> {
                        // model, systemPrompt, maxIter: runtime values — not structure.
                    }
                }
            }
        }

        agent.put("input", inputSlot);
        agent.put("result", resultSlot);
        agent.put("events", events);
        agent.put("tools", new ArrayList<>(tools.values()));
        agent.put("humanTasks", humanTasks);
        return agent;
    }

    private static boolean isDurableAgentType(TypeSymbol type) {
        if (!(type instanceof io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol ref)) {
            return false;
        }
        if (!ref.getName().map(DURABLE_AGENT_TYPE::equals).orElse(false)) {
            return false;
        }
        return ref.getModule()
                .map(m -> WorkflowConstants.PACKAGE_ORG.equals(m.id().orgName())
                        && WorkflowConstants.PACKAGE_NAME.equals(m.id().moduleName()))
                .orElse(false);
    }

    private static MappingConstructorExpressionNode agentConfigMapping(ModuleVariableDeclarationNode varDecl) {
        if (varDecl.initializer().isEmpty()) {
            return null;
        }
        Node initializer = varDecl.initializer().get();
        if (initializer instanceof CheckExpressionNode check) {
            initializer = check.expression();
        }
        SeparatedNodeList<FunctionArgumentNode> args = null;
        if (initializer instanceof ImplicitNewExpressionNode implicitNew
                && implicitNew.parenthesizedArgList().isPresent()) {
            args = implicitNew.parenthesizedArgList().get().arguments();
        } else if (initializer instanceof io.ballerina.compiler.syntax.tree.ExplicitNewExpressionNode explicitNew) {
            args = explicitNew.parenthesizedArgList().arguments();
        }
        if (args == null || args.isEmpty() || !(args.get(0) instanceof PositionalArgumentNode posArg)) {
            return null;
        }
        return posArg.expression() instanceof MappingConstructorExpressionNode mapping ? mapping : null;
    }

    /** Resolves a {@code typedesc} config expression (e.g. {@code OrderInput}) to a typed slot. */
    private static Map<String, Object> typedescSlot(SemanticModel semanticModel, ExpressionNode expression,
                                             Map<String, Object> fallback) {
        Optional<TypeSymbol> typeOpt = semanticModel.typeOf(expression);
        if (typeOpt.isEmpty()) {
            return fallback;
        }
        TypeSymbol raw = typeOpt.get();
        if (raw.typeKind() == TypeDescKind.NIL) {
            return defaultNilSlot();
        }
        TypeSymbol effective = DescriptorSchemaGen.dereference(raw, 0);
        if (effective instanceof TypeDescTypeSymbol typedesc) {
            return DescriptorSchemaGen.slot(typedesc.typeParameter().orElse(null));
        }
        return fallback;
    }

    private static List<Object> buildAgentEvents(SemanticModel semanticModel, ExpressionNode value) {
        Map<String, Map<String, Object>> events = new TreeMap<>();
        for (MappingConstructorExpressionNode mapping : mappingsOf(value)) {
            String name = null;
            Map<String, Object> request = null;
            Map<String, Object> response = null;
            String cardinality = "MULTI";
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = specific.fieldName().toSourceCode().trim();
                ExpressionNode expr = specific.valueExpr().get();
                switch (fieldName) {
                    case "name" -> name = constantStringValue(expr);
                    case "request" -> request = typedescSlot(semanticModel, expr, null);
                    case "response" -> response = typedescSlot(semanticModel, expr, null);
                    case "cardinality" -> cardinality =
                            expr.toSourceCode().contains("SINGLE") ? "SINGLE" : "MULTI";
                    default -> {
                    }
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("name", name);
            event.put("cardinality", cardinality);
            event.put("request", request != null ? request : defaultStringSlot());
            if (response != null) {
                event.put("response", response);
            }
            events.put(name, event);
        }
        return new ArrayList<>(events.values());
    }

    private static List<Object> buildAgentHumanTasks(SemanticModel semanticModel, ExpressionNode value) {
        Map<String, Map<String, Object>> tasks = new TreeMap<>();
        for (MappingConstructorExpressionNode mapping : mappingsOf(value)) {
            String name = null;
            Map<String, Object> result = null;
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = specific.fieldName().toSourceCode().trim();
                ExpressionNode expr = specific.valueExpr().get();
                if ("name".equals(fieldName)) {
                    name = constantStringValue(expr);
                } else if ("resultType".equals(fieldName)) {
                    result = typedescSlot(semanticModel, expr, null);
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("name", name);
            task.put("result", result != null ? result : anydataSlot());
            tasks.put(name, task);
        }
        return new ArrayList<>(tasks.values());
    }

    private static void collectAgentActivities(SemanticModel semanticModel, ExpressionNode value,
                                        Map<String, Map<String, Object>> tools) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            String explicitName = null;
            ExpressionNode fnRef = null;
            Set<String> boundNames = new LinkedHashSet<>();
            if (member instanceof MappingConstructorExpressionNode mapping) {
                for (MappingFieldNode field : mapping.fields()) {
                    if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                        continue;
                    }
                    String fieldName = specific.fieldName().toSourceCode().trim();
                    ExpressionNode expr = specific.valueExpr().get();
                    switch (fieldName) {
                        case "activity" -> fnRef = expr;
                        case "name" -> explicitName = constantStringValue(expr);
                        case "bindings" -> {
                            if (expr instanceof MappingConstructorExpressionNode bindings) {
                                for (MappingFieldNode binding : bindings.fields()) {
                                    if (binding instanceof SpecificFieldNode b) {
                                        boundNames.add(b.fieldName().toSourceCode().trim()
                                                .replace("\"", ""));
                                    }
                                }
                            }
                        }
                        default -> {
                        }
                    }
                }
            } else if (member instanceof ExpressionNode expr) {
                fnRef = expr;
            }
            if (fnRef == null) {
                continue;
            }
            Optional<Symbol> symbol = semanticModel.symbol(fnRef);
            if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION) {
                continue;
            }
            FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
            String name = explicitName != null ? explicitName : fnSymbol.getName().orElse(null);
            if (name == null) {
                continue;
            }
            List<ParameterSymbol> params = new ArrayList<>();
            for (ParameterSymbol p : dataParameters(fnSymbol.typeDescriptor().params().orElse(List.of()))) {
                if (p.getName().map(boundNames::contains).orElse(false)) {
                    continue; // fixed at registration by bindings — not part of the tool's input
                }
                params.add(p);
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("source", "ACTIVITY");
            tool.put("input", DescriptorSchemaGen.parameterSlot(params));
            tools.put(name, tool);
        }
    }

    private static void collectAgentTools(SemanticModel semanticModel, ExpressionNode value,
                                   Map<String, Map<String, Object>> tools) {
        if (!(value instanceof ListConstructorExpressionNode list)) {
            return;
        }
        for (Node member : list.expressions()) {
            ExpressionNode ref = null;
            if (member instanceof MappingConstructorExpressionNode mapping) {
                for (MappingFieldNode field : mapping.fields()) {
                    if (field instanceof SpecificFieldNode specific && specific.valueExpr().isPresent()
                            && "tool".equals(specific.fieldName().toSourceCode().trim())) {
                        ref = specific.valueExpr().get();
                    }
                }
            } else if (member instanceof ExpressionNode expr) {
                ref = expr;
            }
            if (ref == null) {
                continue;
            }
            ExpressionNode toolRef = ref;
            Optional<Symbol> symbol = semanticModel.symbol(toolRef);
            String name = symbol.flatMap(Symbol::getName)
                    .orElseGet(() -> simpleNameOf(toolRef.toSourceCode().trim()));
            if (name == null || name.isEmpty()) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("source", "AI_TOOL");
            if (symbol.isPresent() && symbol.get().kind() == SymbolKind.FUNCTION) {
                FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
                tool.put("input", DescriptorSchemaGen.parameterSlot(
                        dataParameters(fnSymbol.typeDescriptor().params().orElse(List.of()))));
            }
            tools.put(name, tool);
        }
    }

    private static void collectAgentPeers(ExpressionNode value, Map<String, Map<String, Object>> tools) {
        for (MappingConstructorExpressionNode mapping : mappingsOf(value)) {
            String name = null;
            for (MappingFieldNode field : mapping.fields()) {
                if (field instanceof SpecificFieldNode specific && specific.valueExpr().isPresent()
                        && "name".equals(specific.fieldName().toSourceCode().trim())) {
                    name = constantStringValue(specific.valueExpr().get());
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("source", "PEER");
            tools.put(name, tool);
        }
    }

    private static List<MappingConstructorExpressionNode> mappingsOf(ExpressionNode value) {
        List<MappingConstructorExpressionNode> mappings = new ArrayList<>();
        if (value instanceof ListConstructorExpressionNode list) {
            for (Node member : list.expressions()) {
                if (member instanceof MappingConstructorExpressionNode mapping) {
                    mappings.add(mapping);
                }
            }
        }
        return mappings;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> defaultStringSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("type", "string");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        slot.put("schema", schema);
        return slot;
    }

    private static Map<String, Object> defaultNilSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("type", "()");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "null");
        slot.put("schema", schema);
        return slot;
    }

    private static Map<String, Object> anydataSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("type", "anydata");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        slot.put("schema", schema);
        slot.put("lossy", Boolean.TRUE);
        return slot;
    }

    private static String simpleNameOf(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? ref : ref.substring(colon + 1).trim();
    }

    private static String majorVersion(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** A compile-time constant string: a plain literal or a template without interpolations. */
    private static String constantStringValue(Node expression) {
        if (expression instanceof BasicLiteralNode literal
                && literal.kind() == SyntaxKind.STRING_LITERAL) {
            String raw = literal.literalToken().text();
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                return raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
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
}
