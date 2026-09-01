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
import io.ballerina.compiler.syntax.tree.Token;
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

import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.ACTIVITIES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.ACTIVITY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.AGENTS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.BAL_NIL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.BAL_STRING;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.CARDINALITY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.CARDINALITY_MULTI;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.CARDINALITY_SINGLE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.DIRECTION;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.DIRECTION_IN;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.EVENTS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.FUNCTION;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.GRAPH;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.HUMAN_TASKS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.INPUT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_NULL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_OBJECT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.JSON_STRING;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_WORKFLOW;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.LOSSY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.MODULE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.NAME;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.ORG;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.OUTPUT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.PACKAGE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.PAYLOAD;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.REQUEST;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.RESPONSE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.RESULT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.REVIEW_ACTIVITIES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SCHEMA;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SOURCE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SOURCE_ACTIVITY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SOURCE_AI_TOOL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SOURCE_PEER;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TOOLS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TRIGGERS;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TRIGGER_ON_FAILURE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TYPE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.VERSION;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.WORKFLOWS;


/**
 * Builds the Workflow Definition Descriptor ({@code workflow.def.json}) from the compiled
 * package: every workflow with its input, events, activities (input and output), human tasks
 * (result form) and review activities, plus every durable-agent declaration's static structure.
 * Built once by {@link WorkflowDescriptorGenerator} (which packs the canonical bytes into
 * build artifacts) and once by the source modifier (which embeds the same bytes as data in the
 * generated registration — the only carrier that reaches {@code bal test} runs). The build is
 * deterministic, so the two documents are byte-identical.
 *
 * <p>Captures only the structural facts — what registers with the Temporal runtime, plus
 * schemas. Expression-valued instance parameters (roles, titles, retry counts, timeouts,
 * payloads) are deliberately not captured.
 *
 * @since 0.9.0
 */
public final class WorkflowDescriptorBuilder {

    public static final String DESCRIPTOR_VERSION = "1.0";

    // The agent object type and the human-review retry policy are named in
    // WorkflowConstants, with the rest of the API vocabulary this builder matches.

    // callActivity(activityFunction, args, T, retryPolicy): the policy is the fourth
    // positional parameter, reachable when the caller supplies the typedesc explicitly.
    private static final int RETRY_POLICY_POSITION = 3;

    /** Display labels are capped so one long expression cannot dominate the document. */
    private static final int MAX_LABEL_LENGTH = 120;

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
        return build(currentPackage, compilation, false, null);
    }

    /**
     * Builds the descriptor, reporting every name collision — a workflow or agent whose simple
     * name is already claimed by another declaration in the package. The descriptor (and the
     * runtime registry it feeds) is keyed by simple name, so a colliding declaration would be
     * silently dropped; the collision consumer lets the caller surface a diagnostic instead.
     *
     * <p>{@code includeTestDocuments} controls whether workflows declared under {@code tests/}
     * are described. The descriptor packed as a build artifact never includes them — it
     * describes the integration, not its tests — but the copy embedded in generated
     * registration must, when that registration is hosted in a test document: registration is
     * descriptor-driven, so a test-only workflow that the descriptor omits would never reach
     * the process registry and could not execute under {@code bal test}.
     *
     * @param currentPackage       the package being compiled
     * @param compilation          its compilation (for semantic models)
     * @param includeTestDocuments whether to describe workflows declared in test documents
     * @param onCollision          invoked with a description of each dropped duplicate, or {@code null}
     * @return the canonical descriptor bytes, or {@code null} when nothing is declared
     */
    public static byte[] build(Package currentPackage, PackageCompilation compilation,
                               boolean includeTestDocuments,
                               java.util.function.Consumer<String> onCollision) {
        String major = majorVersion(currentPackage.packageVersion().value().toString());

        Map<String, Map<String, Object>> workflows = new TreeMap<>();
        Map<String, Map<String, Object>> agents = new TreeMap<>();

        for (ModuleId moduleId : currentPackage.moduleIds()) {
            Module module = currentPackage.module(moduleId);
            SemanticModel semanticModel = compilation.getSemanticModel(moduleId);
            String moduleQName = currentPackage.packageOrg().value() + "/" + module.moduleName().toString();
            java.util.List<DocumentId> documentIds = new ArrayList<>(module.documentIds());
            if (includeTestDocuments) {
                documentIds.addAll(module.testDocumentIds());
            }
            for (DocumentId documentId : documentIds) {
                ModulePartNode root = module.document(documentId).syntaxTree().rootNode();
                for (ModuleMemberDeclarationNode member : root.members()) {
                    if (member instanceof FunctionDefinitionNode fnDef
                            && WorkflowPluginUtils.hasWorkflowAnnotation(fnDef, semanticModel,
                                    WorkflowConstants.PROCESS_ANNOTATION)) {
                        Map<String, Object> entry =
                                buildWorkflowEntry(fnDef, semanticModel, moduleQName, major);
                        if (entry != null) {
                            String name = (String) entry.get(NAME);
                            if ((workflows.putIfAbsent(name, entry) != null || agents.containsKey(name))
                                    && onCollision != null) {
                                onCollision.accept("workflow '" + name + "' in module '" + moduleQName
                                        + "' collides with another workflow or agent of the same name;"
                                        + " workflow names must be unique across the package");
                            }
                        }
                    } else if (member instanceof ModuleVariableDeclarationNode varDecl) {
                        Map<String, Object> agent = buildAgentEntry(varDecl, semanticModel);
                        if (agent != null) {
                            String name = (String) agent.get(NAME);
                            if ((agents.putIfAbsent(name, agent) != null || workflows.containsKey(name))
                                    && onCollision != null) {
                                onCollision.accept("agent '" + name + "' in module '" + moduleQName
                                        + "' collides with another workflow or agent of the same name;"
                                        + " agent names must be unique across the package");
                            }
                        }
                    }
                }
            }
        }

        if (workflows.isEmpty() && agents.isEmpty()) {
            return null;
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put(DescriptorFields.DESCRIPTOR_VERSION, DESCRIPTOR_VERSION);
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put(ORG, currentPackage.packageOrg().value());
        pkg.put(NAME, currentPackage.packageName().value());
        pkg.put(VERSION, currentPackage.packageVersion().value().toString());
        document.put(PACKAGE, pkg);
        document.put(WORKFLOWS, new ArrayList<>(workflows.values()));
        document.put(AGENTS, new ArrayList<>(agents.values()));

        return DescriptorJson.withChecksum(document);
    }

    // ------------------------------------------------------------------
    // Workflows
    // ------------------------------------------------------------------

    /**
     * The mapping field's key as written, from token text rather than {@code toSourceCode()}:
     * the latter carries leading trivia, so a comment line above the field would become part
     * of the "name" and the field would silently stop matching.
     *
     * @param field the mapping field
     * @return the key name, or {@code null} for a key with no static name
     */
    private static String fieldKeyName(SpecificFieldNode field) {
        Node keyNode = field.fieldName();
        if (keyNode instanceof BasicLiteralNode literal && literal.kind() == SyntaxKind.STRING_LITERAL) {
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

    private static Map<String, Object> buildWorkflowEntry(FunctionDefinitionNode fnDef, SemanticModel semanticModel,
                                                   String moduleQName, String major) {
        Optional<Symbol> symbol = semanticModel.symbol(fnDef);
        if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION) {
            return null;
        }
        FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
        String name = fnDef.functionName().text();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(NAME, name);
        entry.put(KIND, KIND_WORKFLOW);
        entry.put(FUNCTION, functionRef(moduleQName, major, name));

        List<ParameterSymbol> inputParams = new ArrayList<>();
        RecordTypeSymbol eventsRecord = null;
        for (ParameterSymbol param : dataParameters(fnSymbol.typeDescriptor().params().orElse(List.of()))) {
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
        entry.put(INPUT, DescriptorSchemaGen.parameterSlot(inputParams));
        entry.put(EVENTS, buildWorkflowEvents(eventsRecord));

        WorkflowBodyCollector collector = new WorkflowBodyCollector(semanticModel);
        fnDef.functionBody().accept(collector);
        entry.put(ACTIVITIES, new ArrayList<>(collector.activities.values()));
        entry.put(HUMAN_TASKS, buildHumanTasks(collector.humanTaskResults, collector.conflictingHumanTasks));
        entry.put(REVIEW_ACTIVITIES, buildReviewActivities(collector.reviewedActivities));
        // The activity list says *what* the workflow calls; the graph says where, in what order,
        // and under which branch — the identity an execution's history is joined back to.
        entry.put(GRAPH, WorkflowGraphBuilder.build(fnDef, semanticModel).graph());
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
            event.put(NAME, field.getKey());
            event.put(DIRECTION, DIRECTION_IN);
            event.put(CARDINALITY, CARDINALITY_SINGLE);
            event.put(PAYLOAD, DescriptorSchemaGen.slot(payloadType));
            events.add(event);
        }
        return events;
    }

    private static List<Object> buildHumanTasks(Map<String, TypeSymbol> humanTaskResults,
                                                Set<String> conflicting) {
        List<Object> tasks = new ArrayList<>();
        for (Map.Entry<String, TypeSymbol> entry : humanTaskResults.entrySet()) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put(NAME, entry.getKey());
            TypeSymbol resultType = conflicting.contains(entry.getKey()) ? null : entry.getValue();
            task.put(RESULT, DescriptorSchemaGen.slot(resultType));
            tasks.add(task);
        }
        return tasks;
    }

    private static List<Object> buildReviewActivities(Map<String, Set<String>> reviewedActivities) {
        List<Object> reviews = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : reviewedActivities.entrySet()) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put(ACTIVITY, entry.getKey());
            review.put(TRIGGERS, new ArrayList<>(entry.getValue()));
            reviews.add(review);
        }
        return reviews;
    }

    private static Map<String, Object> functionRef(String moduleQName, String major, String functionName) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put(MODULE, moduleQName);
        ref.put(VERSION, major);
        ref.put(NAME, functionName);
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
        // Task names whose call sites declared different result types. Kept apart from
        // humanTaskResults so a later call site cannot resurrect one of the conflicting
        // types: the schema stays unknown, which is the only honest answer.
        final Set<String> conflictingHumanTasks = new LinkedHashSet<>();
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
                        .add(TRIGGER_ON_FAILURE);
            }
        }

        /**
         * True when the call site passes a {@code retryPolicy} whose static type is the
         * {@code HumanReview} shape ({@code string|string[]} — the reviewer roles). An
         * {@code AutoRetry}/{@code NoAutomaticRetry} record, or a type too dynamic to
         * classify, does not create a review activity in the descriptor.
         *
         * <p>Both call forms are recognized. {@code retryPolicy} is usually named, because
         * the typedesc parameter before it is normally inferred; but a caller that supplies
         * that typedesc explicitly can pass the policy positionally as the fourth argument —
         * {@code ctx->callActivity(fn, {}, string, "OPS")} — and that review activity must
         * appear in the descriptor too.
         */
        private boolean hasHumanReviewRetryPolicy(SeparatedNodeList<FunctionArgumentNode> args) {
            for (FunctionArgumentNode arg : args) {
                if (arg instanceof NamedArgumentNode named
                        && WorkflowConstants.ARG_RETRY_POLICY.equals(named.argumentName().name().text())) {
                    return isHumanReviewTyped(named.expression());
                }
            }
            if (args.size() > RETRY_POLICY_POSITION
                    && args.get(RETRY_POLICY_POSITION) instanceof PositionalArgumentNode positional) {
                return isHumanReviewTyped(positional.expression());
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
                    && ref.getName().map(WorkflowConstants.HUMAN_REVIEW_TYPE::equals).orElse(false)) {
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
            if (conflictingHumanTasks.contains(taskName)) {
                // Already known to disagree; a further call site cannot settle it.
                return;
            }
            if (!humanTaskResults.containsKey(taskName)) {
                humanTaskResults.put(taskName, resultType);
                return;
            }
            TypeSymbol existing = humanTaskResults.get(taskName);
            if (existing == null) {
                humanTaskResults.put(taskName, resultType);
            } else if (resultType != null && !existing.signature().equals(resultType.signature())) {
                // Call sites declare different result types: no single completion schema is
                // correct, so the task is recorded as conflicting and its schema stays unknown.
                conflictingHumanTasks.add(taskName);
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
                        && WorkflowConstants.ARG_TASK_NAME.equals(named.argumentName().name().text())) {
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
            entry.put(NAME, name);
            Optional<ModuleSymbol> moduleSymbol = fnSymbol.getModule();
            if (moduleSymbol.isPresent()) {
                String moduleQName = moduleSymbol.get().id().orgName() + "/"
                        + moduleSymbol.get().id().moduleName();
                entry.put(FUNCTION,
                        functionRef(moduleQName, majorVersion(moduleSymbol.get().id().version()), name));
            }
            entry.put(INPUT, DescriptorSchemaGen.parameterSlot(
                    dataParameters(fnSymbol.typeDescriptor().params().orElse(List.of()))));
            entry.put(OUTPUT, DescriptorSchemaGen.outputSlot(
                    fnSymbol.typeDescriptor().returnTypeDescriptor().orElse(null)));
            return entry;
        }
    }

    /**
     * Filters a function's parameters to its data parameters: skips typedescs and client
     * objects (mirroring the runtime's derivation) and rest parameters (the runtime's
     * {@code FunctionType.getParameters()} does not include them either).
     */
    private static List<ParameterSymbol> dataParameters(List<ParameterSymbol> params) {
        List<ParameterSymbol> dataParams = new ArrayList<>();
        for (ParameterSymbol param : params) {
            if (param.paramKind() == io.ballerina.compiler.api.symbols.ParameterKind.REST) {
                continue;
            }
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
        agent.put(NAME, agentName);

        Map<String, Object> inputSlot = defaultStringSlot();
        Map<String, Object> resultSlot = defaultNilSlot();
        String modelLabel = null;
        List<Object> events = new ArrayList<>();
        List<Object> humanTasks = new ArrayList<>();
        Map<String, Map<String, Object>> tools = new TreeMap<>();

        if (config != null) {
            for (MappingFieldNode field : config.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = fieldKeyName(specific);
                if (fieldName == null) {
                    continue;
                }
                ExpressionNode value = specific.valueExpr().get();
                switch (fieldName) {
                    case WorkflowConstants.AGENT_CONFIG_INPUT_TYPE ->
                            inputSlot = typedescSlot(semanticModel, value, defaultStringSlot());
                    case WorkflowConstants.AGENT_CONFIG_RESULT_TYPE ->
                            resultSlot = typedescSlot(semanticModel, value, defaultNilSlot());
                    case WorkflowConstants.AGENT_CONFIG_EVENTS -> events = buildAgentEvents(semanticModel, value);
                    case WorkflowConstants.AGENT_CONFIG_HUMAN_TASKS ->
                            humanTasks = buildAgentHumanTasks(semanticModel, value);
                    case WorkflowConstants.AGENT_CONFIG_ACTIVITIES ->
                            collectAgentActivities(semanticModel, value, tools);
                    case WorkflowConstants.AGENT_CONFIG_TOOLS -> collectAgentTools(semanticModel, value, tools);
                    case WorkflowConstants.AGENT_CONFIG_PEERS -> collectAgentPeers(value, tools);
                    case WorkflowConstants.AGENT_CONFIG_MODEL -> modelLabel = sourceLabel(value);
                    default -> {
                        // systemPrompt, maxIter: runtime values — not structure.
                    }
                }
            }
        }

        agent.put(INPUT, inputSlot);
        agent.put(RESULT, resultSlot);
        agent.put(EVENTS, events);
        agent.put(TOOLS, new ArrayList<>(tools.values()));
        agent.put(HUMAN_TASKS, humanTasks);
        agent.put(GRAPH, AgentGraphBuilder.build(agentName, modelLabel, events,
                new ArrayList<>(tools.values()), humanTasks));
        return agent;
    }

    /**
     * The source text of a config expression, as a display label: the model an agent reasons
     * with is a runtime value, but which reference was configured is structure worth drawing.
     * Capped, because the descriptor ships in every full heartbeat.
     */
    private static String sourceLabel(ExpressionNode expression) {
        String text = expression.toSourceCode().trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= MAX_LABEL_LENGTH ? text : text.substring(0, MAX_LABEL_LENGTH - 1) + "\u2026";
    }

    private static boolean isDurableAgentType(TypeSymbol type) {
        if (!(type instanceof io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol ref)) {
            return false;
        }
        if (!ref.getName().map(WorkflowConstants.DURABLE_AGENT_TYPE::equals).orElse(false)) {
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
        for (NamedEntry namedEntry : namedMappingsOf(value)) {
            MappingConstructorExpressionNode mapping = namedEntry.config();
            String name = namedEntry.name();
            Map<String, Object> request = null;
            Map<String, Object> response = null;
            String cardinality = CARDINALITY_MULTI;
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = fieldKeyName(specific);
                if (fieldName == null) {
                    continue;
                }
                ExpressionNode expr = specific.valueExpr().get();
                switch (fieldName) {
                    case WorkflowConstants.DECL_NAME -> name = constantStringValue(expr);
                    case WorkflowConstants.DECL_REQUEST -> request = typedescSlot(semanticModel, expr, null);
                    case WorkflowConstants.DECL_RESPONSE -> response = typedescSlot(semanticModel, expr, null);
                    case WorkflowConstants.DECL_CARDINALITY -> cardinality =
                            expr.toSourceCode().contains(WorkflowConstants.CARDINALITY_SINGLE_EVENT)
                                ? CARDINALITY_SINGLE : CARDINALITY_MULTI;
                    default -> {
                    }
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put(NAME, name);
            event.put(CARDINALITY, cardinality);
            event.put(REQUEST, request != null ? request : defaultStringSlot());
            if (response != null) {
                event.put(RESPONSE, response);
            }
            events.put(name, event);
        }
        return new ArrayList<>(events.values());
    }

    private static List<Object> buildAgentHumanTasks(SemanticModel semanticModel, ExpressionNode value) {
        Map<String, Map<String, Object>> tasks = new TreeMap<>();
        for (NamedEntry namedEntry : namedMappingsOf(value)) {
            MappingConstructorExpressionNode mapping = namedEntry.config();
            String name = namedEntry.name();
            Map<String, Object> result = null;
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode specific) || specific.valueExpr().isEmpty()) {
                    continue;
                }
                String fieldName = fieldKeyName(specific);
                if (fieldName == null) {
                    continue;
                }
                ExpressionNode expr = specific.valueExpr().get();
                if (WorkflowConstants.DECL_NAME.equals(fieldName)) {
                    name = constantStringValue(expr);
                } else if (WorkflowConstants.AGENT_CONFIG_RESULT_TYPE.equals(fieldName)) {
                    result = typedescSlot(semanticModel, expr, null);
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> task = new LinkedHashMap<>();
            task.put(NAME, name);
            task.put(RESULT, result != null ? result : anydataSlot());
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
                    String fieldName = fieldKeyName(specific);
                    if (fieldName == null) {
                        continue;
                    }
                    ExpressionNode expr = specific.valueExpr().get();
                    switch (fieldName) {
                        case WorkflowConstants.DECL_ACTIVITY -> fnRef = expr;
                        case WorkflowConstants.DECL_NAME -> explicitName = constantStringValue(expr);
                        case WorkflowConstants.DECL_BINDINGS -> {
                            if (expr instanceof MappingConstructorExpressionNode bindings) {
                                for (MappingFieldNode binding : bindings.fields()) {
                                    if (binding instanceof SpecificFieldNode b) {
                                        String boundName = fieldKeyName(b);
                                        if (boundName != null) {
                                            boundNames.add(boundName);
                                        }
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
            tool.put(NAME, name);
            tool.put(SOURCE, SOURCE_ACTIVITY);
            tool.put(INPUT, DescriptorSchemaGen.parameterSlot(params));
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
                            && WorkflowConstants.DECL_TOOL.equals(fieldKeyName(specific))) {
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
            tool.put(NAME, name);
            tool.put(SOURCE, SOURCE_AI_TOOL);
            if (symbol.isPresent() && symbol.get().kind() == SymbolKind.FUNCTION) {
                FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
                tool.put(INPUT, DescriptorSchemaGen.parameterSlot(
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
                        && WorkflowConstants.DECL_NAME.equals(fieldKeyName(specific))) {
                    name = constantStringValue(specific.valueExpr().get());
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put(NAME, name);
            tool.put(SOURCE, SOURCE_PEER);
            tools.put(name, tool);
        }
    }

    /**
     * A named config entry from either declaration form. The mapping form ({@code
     * events: {chat: {...}}}) is the primary style: the key IS the name and the value is the
     * config. The deprecated array form carries the name as a {@code name} field inside each
     * entry, which the caller reads — so array entries come back with a null name.
     *
     * @param value the field's value expression
     * @return the entries, in source order
     */
    private static List<NamedEntry> namedMappingsOf(ExpressionNode value) {
        List<NamedEntry> entries = new ArrayList<>();
        if (value instanceof MappingConstructorExpressionNode mapping) {
            for (MappingFieldNode field : mapping.fields()) {
                if (field instanceof SpecificFieldNode specific && specific.valueExpr().isPresent()
                        && specific.valueExpr().get() instanceof MappingConstructorExpressionNode config) {
                    String key = fieldKeyName(specific);
                    if (key != null) {
                        entries.add(new NamedEntry(key, config));
                    }
                }
            }
            return entries;
        }
        if (value instanceof ListConstructorExpressionNode list) {
            for (Node member : list.expressions()) {
                if (member instanceof MappingConstructorExpressionNode config) {
                    entries.add(new NamedEntry(null, config));
                }
            }
        }
        return entries;
    }

    private record NamedEntry(String name, MappingConstructorExpressionNode config) { }

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
        slot.put(TYPE, BAL_STRING);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, JSON_STRING);
        slot.put(SCHEMA, schema);
        return slot;
    }

    private static Map<String, Object> defaultNilSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put(TYPE, BAL_NIL);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, JSON_NULL);
        slot.put(SCHEMA, schema);
        return slot;
    }

    private static Map<String, Object> anydataSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put(TYPE, DescriptorFields.BAL_ANYDATA);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, JSON_OBJECT);
        slot.put(SCHEMA, schema);
        slot.put(LOSSY, Boolean.TRUE);
        return slot;
    }

    private static String simpleNameOf(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? ref : ref.substring(colon + 1).trim();
    }

    /** Decodes the escape sequences a string literal's token text carries verbatim. */
    private static String unescapeStringLiteral(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                default -> out.append(c).append(next);
            }
        }
        return out.toString();
    }

    private static String majorVersion(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** A compile-time constant string: a plain literal or a template without interpolations. */
    static String constantStringValue(Node expression) {
        if (expression instanceof BasicLiteralNode literal
                && literal.kind() == SyntaxKind.STRING_LITERAL) {
            String raw = literal.literalToken().text();
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                return unescapeStringLiteral(raw.substring(1, raw.length() - 1));
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
