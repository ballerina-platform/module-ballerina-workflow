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

import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ImportOrgNameNode;
import io.ballerina.compiler.syntax.tree.ImportPrefixNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeParser;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.plugins.ModifierTask;
import io.ballerina.projects.plugins.SourceModifierContext;
import io.ballerina.tools.text.TextDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Source modifier that transforms workflow process functions.
 * <p>
 * This modifier performs AST transformations:
 * 1. Replaces activity function calls with callActivity(funcPtr, args...)
 * 2. Adds registerWorkflow call at module level for each @Workflow function
 *
 * @since 0.1.0
 */
public class WorkflowSourceModifier implements ModifierTask<SourceModifierContext> {

    private final Map<DocumentId, WorkflowModifierContext> modifierContextMap;
    private final Map<String, Object> userData;

    public WorkflowSourceModifier(Map<DocumentId, WorkflowModifierContext> modifierContextMap,
                                  Map<String, Object> userData) {
        this.modifierContextMap = modifierContextMap;
        this.userData = userData;
    }

    /** @deprecated Retained for source-compat with older callers. */
    @Deprecated
    public WorkflowSourceModifier(Map<DocumentId, WorkflowModifierContext> modifierContextMap) {
        this(modifierContextMap, Collections.emptyMap());
    }

    @Override
    public void modify(SourceModifierContext context) {
        // Collect all process functions across all documents so we can generate
        // a single registerWorkflowsAndStart() call that covers every workflow.
        List<Map.Entry<DocumentId, WorkflowModifierContext>> entries = new ArrayList<>();
        List<ProcessFunctionInfo> allProcessInfos = new ArrayList<>();
        List<AgentFunctionInfo> allAgentInfos = new ArrayList<>();
        List<DurableAgentDeclInfo> allDurableAgentDecls = new ArrayList<>();

        for (Map.Entry<DocumentId, WorkflowModifierContext> entry : this.modifierContextMap.entrySet()) {
            if (!entry.getValue().getProcessInfoMap().isEmpty()
                    || !entry.getValue().getAgentInfoMap().isEmpty()
                    || !entry.getValue().getDurableAgentDeclMap().isEmpty()) {
                entries.add(entry);
                allProcessInfos.addAll(entry.getValue().getProcessInfoMap().values());
                allAgentInfos.addAll(entry.getValue().getAgentInfoMap().values());
                allDurableAgentDecls.addAll(entry.getValue().getDurableAgentDeclMap().values());
            }
        }

        // An agent registers as an ordinary workflow whose activities are its
        // tools plus the built-in LLM chat activity. Modeling agents as
        // ProcessFunctionInfo entries reuses the registerWorkflow emission and
        // the qualified-import copying below (workflow:llmChat needs the
        // ballerina/workflow import in the generated-code document).
        for (AgentFunctionInfo agent : allAgentInfos) {
            Map<String, String> activityMap = new java.util.LinkedHashMap<>(agent.activityToolRefs());
            activityMap.put(WorkflowConstants.LLM_CHAT_ACTIVITY,
                    agent.workflowPrefix() + ":" + WorkflowConstants.LLM_CHAT_ACTIVITY);
            activityMap.put(WorkflowConstants.GENERATE_ACTIVITY,
                    agent.workflowPrefix() + ":" + WorkflowConstants.GENERATE_ACTIVITY);
            activityMap.put(WorkflowConstants.EXECUTE_AGENT_TOOL_ACTIVITY,
                    agent.workflowPrefix() + ":" + WorkflowConstants.EXECUTE_AGENT_TOOL_ACTIVITY);
            // Human tasks registered on the AgentContext reuse the same qualified
            // "<functionName>.<taskName>" init registration as awaitHumanTask call sites.
            allProcessInfos.add(new ProcessFunctionInfo(agent.functionName(), activityMap,
                    agent.humanTaskNames()));
        }

        // Collect import declarations across every document in the module(s) being
        // modified, keyed by their alias prefix. We need this because the generated
        // __registerWorkflowsAndStart() function may reference activity functions by
        // a qualified prefix (e.g. `activity:callRestAPI`) that is only imported in
        // a *different* file from the one we choose to host the generated function.
        // Without copying the relevant import into the target file the generated
        // function fails to compile with "undefined module 'activity'".
        Map<String, ImportDeclarationNode> importsByPrefix = new HashMap<>();
        Set<String> conflictingPrefixes = new HashSet<>();
        for (Map.Entry<DocumentId, WorkflowModifierContext> entry : entries) {
            DocumentId docId = entry.getKey();
            Module module = context.currentPackage().module(docId.moduleId());
            ModulePartNode rootNode = module.document(docId).syntaxTree().rootNode();
            for (ImportDeclarationNode imp : rootNode.imports()) {
                String prefix = importPrefixOf(imp);
                if (prefix != null) {
                    ImportDeclarationNode existing = importsByPrefix.get(prefix);
                    if (existing == null) {
                        importsByPrefix.put(prefix, imp);
                    } else if (!sameImport(existing, imp)) {
                        conflictingPrefixes.add(prefix);
                    }
                }
            }
        }

        // Determine which prefixes the generated function will reference, so we
        // can selectively copy the corresponding imports into the target file.
        Set<String> requiredPrefixes = collectRequiredImportPrefixes(allProcessInfos);

        // Transform each document (AST-level activity call rewrites) …
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<DocumentId, WorkflowModifierContext> entry = entries.get(i);
            DocumentId documentId = entry.getKey();
            WorkflowModifierContext workflowContext = entry.getValue();

            Module module = context.currentPackage().module(documentId.moduleId());
            ModulePartNode rootNode = module.document(documentId).syntaxTree().rootNode();

            // … and append the combined registration function + invocation
            // only to the LAST document so that all @Workflow functions from
            // every source file are visible to the generated function body.
            boolean isLastDocument = (i == entries.size() - 1);
                boolean isTestDocument = !module.documentIds().contains(documentId);

            ModulePartNode updatedRootNode = transformDocument(
                    rootNode, workflowContext, isLastDocument ? allProcessInfos : null,
                    isLastDocument ? allAgentInfos : Collections.emptyList(),
                    isLastDocument ? allDurableAgentDecls : Collections.emptyList(),
                    isLastDocument
                        ? collectConnectionNames(documentId.moduleId().toString(), isTestDocument)
                        : Collections.emptyList());

            // Only add the import for the document that contains the generated
            // __registerWorkflowsAndStart() function to avoid unused-import errors.
            if (isLastDocument) {
                updatedRootNode = addWorkflowInternalImportIfMissing(updatedRootNode);
                updatedRootNode = addReferencedActivityImports(
                    updatedRootNode, importsByPrefix, requiredPrefixes, conflictingPrefixes);
            }

            SyntaxTree syntaxTree = module.document(documentId).syntaxTree().modifyWith(updatedRootNode);
            TextDocument textDocument = syntaxTree.textDocument();

            if (module.documentIds().contains(documentId)) {
                context.modifySourceFile(textDocument, documentId);
            } else {
                context.modifyTestSourceFile(textDocument, documentId);
            }
        }
    }

    private ModulePartNode transformDocument(ModulePartNode rootNode, WorkflowModifierContext workflowContext,
                                             List<ProcessFunctionInfo> allProcessInfos,
                                             List<AgentFunctionInfo> allAgentInfos,
                                             List<DurableAgentDeclInfo> allDurableAgentDecls,
                                             List<String> connectionNames) {
        NodeList<ModuleMemberDeclarationNode> members = rootNode.members();
        List<ModuleMemberDeclarationNode> newMembers = new ArrayList<>();
        for (ModuleMemberDeclarationNode member : members) {
            newMembers.add(member);
        }

        // When allProcessInfos is non-null this is the target document:
        // generate a private function that registers every workflow and
        // starts the runtime, plus a module-level variable that calls it.
        if (allProcessInfos != null && (!allProcessInfos.isEmpty() || !allDurableAgentDecls.isEmpty())) {
            newMembers.add(createRegisterAndStartFunction(allProcessInfos, allAgentInfos,
                    allDurableAgentDecls, connectionNames));
            newMembers.add(createRegisterAndStartInvocation());
        }

        NodeList<ModuleMemberDeclarationNode> updatedMembers = NodeFactory.createNodeList(newMembers);
        return rootNode.modify(rootNode.imports(), updatedMembers, rootNode.eofToken());
    }

    private List<String> collectConnectionNames(String moduleKey, boolean isTestDocument) {
        if (this.userData == null) {
            return Collections.emptyList();
        }
        Object raw = this.userData.get(WorkflowConstants.CONNECTION_VAR_NAMES);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        addConnectionNames(result, rawMap.get(scopeKey(moduleKey, false)));
        if (isTestDocument) {
            addConnectionNames(result, rawMap.get(scopeKey(moduleKey, true)));
        }
        return result;
    }

    private void addConnectionNames(List<String> result, Object rawNames) {
        if (!(rawNames instanceof Set<?> set)) {
            return;
        }
        for (Object o : set) {
            if (o instanceof String s && !result.contains(s)) {
                result.add(s);
            }
        }
    }

    private String scopeKey(String moduleKey, boolean isTestDocument) {
        return moduleKey + (isTestDocument ? "#test" : "#source");
    }

    /**
     * Generates a private function that registers all workflows and starts the runtime.
     * <pre>
     * function __registerWorkflowsAndStart() returns boolean|error {
     *     _ = check wfInternal:registerConnection("db", db);
     *     _ = check wfInternal:registerWorkflow(wf1, "wf1", {"act": act});
     *     _ = check wfInternal:registerHumanTask("approveExpense");
     *     _ = check wfInternal:startWorkflowRuntime();
     *     return true;
     * }
     * </pre>
     */
    private ModuleMemberDeclarationNode createRegisterAndStartFunction(
            List<ProcessFunctionInfo> allProcessInfos, List<AgentFunctionInfo> allAgentInfos,
            List<DurableAgentDeclInfo> allDurableAgentDecls, List<String> connectionNames) {
        StringBuilder body = new StringBuilder();
        body.append("function __registerWorkflowsAndStart() returns boolean|error {");
        body.append(System.lineSeparator());

        // Register module-level final clients before workflows so any activity
        // launched during workflow registration can already resolve them.
        for (String name : connectionNames) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerConnection(\"").append(name).append("\", ")
                    .append(name).append(");").append(System.lineSeparator());
        }

        for (ProcessFunctionInfo processInfo : allProcessInfos) {
            String activitiesArg = buildActivitiesArg(processInfo);
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerWorkflow(").append(processInfo.functionName())
                    .append(", \"").append(processInfo.functionName()).append("\", ")
                    .append(activitiesArg).append(");").append(System.lineSeparator());
        }

        // Collect qualified human task names ("workflowFunctionName.taskName") across every
        // workflow in this module. Qualification ensures uniqueness across workflows that
        // reuse the same short task name and matches the runtime qualification in awaitHumanTask.
        // A sorted set is used for deterministic code generation across compiler runs.
        Set<String> qualifiedHumanTaskNames = new TreeSet<>();
        for (ProcessFunctionInfo processInfo : allProcessInfos) {
            for (String taskName : processInfo.humanTaskNames()) {
                qualifiedHumanTaskNames.add(processInfo.functionName() + "." + taskName);
            }
        }
        for (String qualifiedName : qualifiedHumanTaskNames) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerHumanTask(\"")
                    .append(escapeBallerinaStringLiteral(qualifiedName))
                    .append("\");")
                    .append(System.lineSeparator());
        }

        // Register each agent's AI tool function pointers so the built-in
        // executeAgentTool activity wrapper can resolve them on every worker.
        for (AgentFunctionInfo agentInfo : allAgentInfos) {
            for (String toolRef : agentInfo.aiToolRefs()) {
                body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                        .append(":registerAgentTool(\"")
                        .append(escapeBallerinaStringLiteral(agentInfo.functionName()))
                        .append("\", ").append(toolRef).append(");")
                        .append(System.lineSeparator());
            }
        }

        // Register object-model durable agent declarations: identity + model first, then the
        // capability declarations, re-referencing the same symbols the user's config named.
        for (DurableAgentDeclInfo decl : allDurableAgentDecls) {
            appendDurableAgentRegistration(body, decl);
        }

        body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                .append(":startWorkflowRuntime();").append(System.lineSeparator());
        body.append("    return true;").append(System.lineSeparator());
        body.append("}");

        return (ModuleMemberDeclarationNode) NodeParser.parseModuleMemberDeclaration(body.toString());
    }

    /**
     * Generates the module-level variable that invokes the combined function.
     * <pre>
     * boolean _ = check __registerWorkflowsAndStart();
     * </pre>
     */
    private ModuleVariableDeclarationNode createRegisterAndStartInvocation() {
        return (ModuleVariableDeclarationNode) NodeParser.parseModuleMemberDeclaration(
                "boolean _ = check __registerWorkflowsAndStart();");
    }

    /**
     * Appends the registration statements for one object-model durable agent declaration.
     * <pre>
     * _ = check wfInternal:registerDurableAgentDecl("orderAgent", wso2Model, {...}, 16);
     * _ = check wfInternal:registerDurableAgentActivity("orderAgent", "checkStock", checkStock, {...});
     * _ = check wfInternal:registerAgentTool("orderAgent", priceLookup);
     * _ = check wfInternal:registerDurableAgentEvent("orderAgent", "chat", string, string, "MULTI_EVENT");
     * _ = check wfInternal:registerDurableAgentHumanTask("orderAgent", "approval", {...});
     * </pre>
     */
    private void appendDurableAgentRegistration(StringBuilder body, DurableAgentDeclInfo decl) {
        if (decl.modelSource() == null || decl.systemPromptSource() == null) {
            return;
        }
        String agentNameLiteral = "\"" + escapeBallerinaStringLiteral(decl.agentName()) + "\"";
        body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                .append(":registerDurableAgentDecl(").append(agentNameLiteral)
                .append(", ").append(decl.modelSource())
                .append(", ").append(decl.systemPromptSource())
                .append(", ").append(decl.maxIterSource() != null ? decl.maxIterSource() : "16")
                .append(");").append(System.lineSeparator());
        for (DurableAgentDeclInfo.ActivityDecl activity : decl.activities()) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerDurableAgentActivity(").append(agentNameLiteral)
                    .append(", \"").append(escapeBallerinaStringLiteral(activity.toolName()))
                    .append("\", ").append(activity.functionRefSource())
                    .append(", ").append(activity.metaSource() != null ? activity.metaSource() : "()")
                    .append(");").append(System.lineSeparator());
        }
        for (String toolRef : decl.aiToolRefs()) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerDurableAgentTool(").append(agentNameLiteral)
                    .append(", ").append(toolRef).append(");").append(System.lineSeparator());
        }
        for (DurableAgentDeclInfo.EventDecl event : decl.events()) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerDurableAgentEvent(").append(agentNameLiteral)
                    .append(", \"").append(escapeBallerinaStringLiteral(event.name()))
                    .append("\", ").append(event.requestTypeSource())
                    .append(", ").append(event.responseTypeSource() != null ? event.responseTypeSource() : "()")
                    .append(", \"").append(event.cardinality()).append("\");")
                    .append(System.lineSeparator());
        }
        for (DurableAgentDeclInfo.HumanTaskDecl task : decl.humanTasks()) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerDurableAgentHumanTask(").append(agentNameLiteral)
                    .append(", \"").append(escapeBallerinaStringLiteral(task.name()))
                    .append("\", ").append(task.metaSource() != null ? task.metaSource() : "()")
                    .append(");").append(System.lineSeparator());
        }
        // Register the shared object-model runner as this agent's workflow (the agent's own
        // workflow type + its activity map incl. the built-in agent activities), and bind the
        // agent's identity to the object so its driver methods (run/getResult/...) resolve it.
        String prefix = decl.workflowPrefix();
        if (prefix != null) {
            body.append("    _ = check ").append(WorkflowConstants.INTERNAL_MODULE_ALIAS)
                    .append(":registerDurableAgentRunner(").append(agentNameLiteral)
                    .append(", ").append(prefix).append(":runDurableAgentObject, {")
                    .append("\"").append(WorkflowConstants.LLM_CHAT_ACTIVITY).append("\": ")
                    .append(prefix).append(":").append(WorkflowConstants.LLM_CHAT_ACTIVITY)
                    .append(", \"").append(WorkflowConstants.GENERATE_ACTIVITY).append("\": ")
                    .append(prefix).append(":").append(WorkflowConstants.GENERATE_ACTIVITY)
                    .append(", \"").append(WorkflowConstants.EXECUTE_AGENT_TOOL_ACTIVITY).append("\": ")
                    .append(prefix).append(":").append(WorkflowConstants.EXECUTE_AGENT_TOOL_ACTIVITY)
                    .append("});").append(System.lineSeparator());
        }
        body.append("    ").append(decl.agentName()).append(".bindAgentName(")
                .append(agentNameLiteral).append(");").append(System.lineSeparator());
    }

    private String buildActivitiesArg(ProcessFunctionInfo processInfo) {
        if (processInfo.activityMap().isEmpty()) {
            return "()";
        }
        StringBuilder mapLiteral = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> activity : processInfo.activityMap().entrySet()) {
            if (!first) {
                mapLiteral.append(", ");
            }
            mapLiteral.append("\"").append(activity.getKey()).append("\": ").append(activity.getValue());
            first = false;
        }
        mapLiteral.append("}");
        return mapLiteral.toString();
    }

    /**
     * Returns the alias prefix declared by an import (the symbol used in
     * qualified references), or {@code null} if the import is malformed.
     * If no explicit alias is given, falls back to the last segment of the
     * dotted module name (e.g. {@code workflow.activity} → {@code activity}).
     */
    private String importPrefixOf(ImportDeclarationNode imp) {
        if (imp.prefix().isPresent()) {
            return imp.prefix().get().prefix().text();
        }
        SeparatedNodeList<IdentifierToken> moduleNames = imp.moduleName();
        if (moduleNames.isEmpty()) {
            return null;
        }
        return moduleNames.get(moduleNames.size() - 1).text();
    }

    /**
     * Scans every collected {@link ProcessFunctionInfo} for activity-call
     * references that use a qualified module prefix and returns the set of
     * those prefixes. Only these are copied into the target file later, so we
     * avoid pulling in unrelated imports and triggering unused-import warnings.
     */
    private Set<String> collectRequiredImportPrefixes(List<ProcessFunctionInfo> infos) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (ProcessFunctionInfo info : infos) {
            for (Map.Entry<String, String> e : info.activityMap().entrySet()) {
                addPrefixIfQualified(prefixes, e.getKey());
                addPrefixIfQualified(prefixes, e.getValue());
            }
        }
        return prefixes;
    }

    private static void addPrefixIfQualified(Set<String> prefixes, String ref) {
        if (ref == null) {
            return;
        }
        int colon = ref.indexOf(':');
        if (colon > 0) {
            prefixes.add(ref.substring(0, colon).trim());
        }
    }

    /**
     * Copies any import declarations from {@code importsByPrefix} whose alias
     * appears in {@code requiredPrefixes} into the target document, unless
     * already present there.
     */
    private ModulePartNode addReferencedActivityImports(ModulePartNode rootNode,
                                                        Map<String, ImportDeclarationNode> importsByPrefix,
                                                        Set<String> requiredPrefixes,
                                                        Set<String> conflictingPrefixes) {
        if (requiredPrefixes.isEmpty() || importsByPrefix.isEmpty()) {
            return rootNode;
        }
        Set<String> existingPrefixes = new java.util.HashSet<>();
        for (ImportDeclarationNode existing : rootNode.imports()) {
            String pref = importPrefixOf(existing);
            if (pref != null) {
                existingPrefixes.add(pref);
            }
        }
        NodeList<ImportDeclarationNode> imports = rootNode.imports();
        boolean changed = false;
        for (String prefix : requiredPrefixes) {
            if (conflictingPrefixes.contains(prefix)) {
                throw new IllegalStateException(
                        "Conflicting import prefix '" + prefix
                                + "' detected across workflow source files. "
                                + "Use unique import aliases for activity modules.");
            }
            if (existingPrefixes.contains(prefix)) {
                continue;
            }
            ImportDeclarationNode source = importsByPrefix.get(prefix);
            if (source == null) {
                continue;
            }
            imports = imports.add(source);
            changed = true;
        }
        if (!changed) {
            return rootNode;
        }
        return rootNode.modify().withImports(imports).apply();
    }

    private boolean sameImport(ImportDeclarationNode a, ImportDeclarationNode b) {
        String aOrg = a.orgName().isPresent() ? a.orgName().get().orgName().text() : "";
        String bOrg = b.orgName().isPresent() ? b.orgName().get().orgName().text() : "";
        if (!aOrg.equals(bOrg)) {
            return false;
        }
        if (a.moduleName().size() != b.moduleName().size()) {
            return false;
        }
        for (int i = 0; i < a.moduleName().size(); i++) {
            if (!a.moduleName().get(i).text().equals(b.moduleName().get(i).text())) {
                return false;
            }
        }
        return true;
    }

    private ModulePartNode addWorkflowInternalImportIfMissing(ModulePartNode rootNode) {
        boolean hasInternalImport = false;

        for (ImportDeclarationNode importNode : rootNode.imports()) {
            if (isWorkflowInternalImportNode(importNode)) {
                hasInternalImport = true;
                break;
            }
        }

        if (!hasInternalImport) {
            ImportDeclarationNode internalImport = createWorkflowInternalImportNode();
            NodeList<ImportDeclarationNode> imports = rootNode.imports().add(internalImport);
            return rootNode.modify().withImports(imports).apply();
        }

        return rootNode;
    }

    private boolean isWorkflowInternalImportNode(ImportDeclarationNode importNode) {
        if (importNode.orgName().isEmpty()) {
            return false;
        }
        String orgName = importNode.orgName().get().orgName().text();
        if (!WorkflowConstants.PACKAGE_ORG.equals(orgName)) {
            return false;
        }
        SeparatedNodeList<IdentifierToken> moduleNames = importNode.moduleName();
        if (moduleNames.size() < 2) {
            return false;
        }
        if (!WorkflowConstants.PACKAGE_NAME.equals(moduleNames.get(0).text())
                || !WorkflowConstants.INTERNAL_MODULE_NAME.equals(moduleNames.get(1).text())) {
            return false;
        }
        // Also verify that the import uses the expected alias so that the hardcoded alias
        // in createRegisterWorkflowCall() resolves correctly. If the user has imported the
        // module with a different alias, we treat it as missing and insert our own import.
        if (importNode.prefix().isEmpty()) {
            return false;
        }
        return WorkflowConstants.INTERNAL_MODULE_ALIAS.equals(importNode.prefix().get().prefix().text());
    }

    private ImportDeclarationNode createWorkflowInternalImportNode() {
        Token importKeyword = NodeFactory.createToken(SyntaxKind.IMPORT_KEYWORD,
                NodeFactory.createEmptyMinutiaeList(),
                NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")));

        Token orgNameToken = NodeFactory.createIdentifierToken(WorkflowConstants.PACKAGE_ORG);
        Token slashToken = NodeFactory.createToken(SyntaxKind.SLASH_TOKEN);
        ImportOrgNameNode importOrgNameToken = NodeFactory.createImportOrgNameNode(orgNameToken, slashToken);

        // Module name: workflow.internal
        IdentifierToken workflowToken = NodeFactory.createIdentifierToken(WorkflowConstants.PACKAGE_NAME);
        Token dotToken = NodeFactory.createToken(SyntaxKind.DOT_TOKEN);
        IdentifierToken internalToken = NodeFactory.createIdentifierToken(
                WorkflowConstants.INTERNAL_MODULE_NAME);
        SeparatedNodeList<IdentifierToken> moduleName = NodeFactory.createSeparatedNodeList(
                workflowToken, dotToken, internalToken);

        // Prefix alias: as wfInternal
        Token asKeyword = NodeFactory.createToken(SyntaxKind.AS_KEYWORD,
                NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")),
                NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")));
        Token prefixToken = NodeFactory.createIdentifierToken(WorkflowConstants.INTERNAL_MODULE_ALIAS);
        ImportPrefixNode importPrefix = NodeFactory.createImportPrefixNode(asKeyword, prefixToken);

        Token semicolonToken = NodeFactory.createToken(SyntaxKind.SEMICOLON_TOKEN);

        return NodeFactory.createImportDeclarationNode(importKeyword, importOrgNameToken, moduleName,
                importPrefix, semicolonToken);
    }

    private static String escapeBallerinaStringLiteral(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
