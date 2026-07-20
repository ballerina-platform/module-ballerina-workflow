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
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Analysis task (code-modifier phase) that detects {@code @workflow:DurableAgent} functions and collects the tool
 * references from {@code ctx.registerActivity(...)} call sites and {@code ctx.runDurableAgent(..., tools = [...])}
 * arguments, so the
 * source modifier can register those tools (plus the built-in {@code llmChat}/{@code generate} activities) at module
 * init on every worker.
 *
 * @since 0.6.0
 */
public class AgentFunctionAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private final Map<String, Object> userData;

    public AgentFunctionAnalysisTask(Map<String, Object> userData) {
        this.userData = userData;
    }

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionDefinitionNode functionNode)) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();
        AnnotationNode agentAnnotation = findAgentAnnotation(functionNode, semanticModel);
        if (agentAnnotation == null) {
            return;
        }

        String workflowPrefix = extractWorkflowPrefix(agentAnnotation);
        if (workflowPrefix == null) {
            // Unqualified @DurableAgent — only inside the workflow package itself, no codegen there.
            return;
        }

        String functionName = functionNode.functionName().text();
        ToolRegistrationCollector collector = new ToolRegistrationCollector();
        functionNode.functionBody().accept(collector);

        AgentFunctionInfo agentInfo = new AgentFunctionInfo(functionName, workflowPrefix,
                collector.activityToolRefs, collector.aiToolRefs, collector.humanTaskNames);
        addToModifierContext(context.documentId(), agentInfo);
    }

    private AnnotationNode findAgentAnnotation(FunctionDefinitionNode functionNode, SemanticModel semanticModel) {
        Optional<MetadataNode> metadataOpt = functionNode.metadata();
        if (metadataOpt.isEmpty()) {
            return null;
        }
        for (AnnotationNode annotation : metadataOpt.get().annotations()) {
            if (WorkflowPluginUtils.isWorkflowAnnotation(annotation, semanticModel,
                    WorkflowConstants.AGENT_ANNOTATION)) {
                return annotation;
            }
        }
        return null;
    }

    private String extractWorkflowPrefix(AnnotationNode agentAnnotation) {
        Node reference = agentAnnotation.annotReference();
        if (reference instanceof QualifiedNameReferenceNode qualifiedRef) {
            return qualifiedRef.modulePrefix().text();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addToModifierContext(DocumentId documentId, AgentFunctionInfo agentInfo) {
        Map<DocumentId, WorkflowModifierContext> modifierContextMap =
                (Map<DocumentId, WorkflowModifierContext>) userData.get(WorkflowConstants.MODIFIER_CONTEXT_MAP);
        if (modifierContextMap == null) {
            return;
        }
        WorkflowModifierContext modifierContext =
                modifierContextMap.computeIfAbsent(documentId, k -> new WorkflowModifierContext());
        modifierContext.addAgentInfo(agentInfo);
    }

    /**
     * Collects capability registrations from an agent body.
     * <ul>
     *   <li>{@code ctx.registerActivity(...)} — activity tool references (registered as workflow activities)</li>
     *   <li>{@code ctx.registerAgentTool(...)} — AI tool function references (registered for the
     *       {@code executeAgentTool} wrapper); {@code ai:ToolConfig} mapping constructors are skipped, as those carry
     *       their function pointer at runtime</li>
     *   <li>{@code ctx.registerHumanTask("name", ...)} — human task name literals (registered as human task
     *       workflow types)</li>
     * </ul>
     */
    private static final class ToolRegistrationCollector extends NodeVisitor {
        private final Map<String, String> activityToolRefs = new LinkedHashMap<>();
        private final java.util.List<String> aiToolRefs = new java.util.ArrayList<>();
        private final java.util.Set<String> humanTaskNames = new java.util.LinkedHashSet<>();

        @Override
        public void visit(MethodCallExpressionNode methodCall) {
            String methodName = methodCall.methodName().toSourceCode().trim();
            SeparatedNodeList<FunctionArgumentNode> args = methodCall.arguments();
            if (WorkflowConstants.REGISTER_ACTIVITY_METHOD.equals(methodName)) {
                if (!args.isEmpty() && args.get(0) instanceof PositionalArgumentNode posArg
                        && (posArg.expression().kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                        || posArg.expression().kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE)) {
                    String ref = posArg.expression().toSourceCode().trim();
                    int colon = ref.indexOf(':');
                    activityToolRefs.put(colon < 0 ? ref : ref.substring(colon + 1).trim(), ref);
                }
            } else if (WorkflowConstants.REGISTER_AGENT_TOOL_METHOD.equals(methodName)) {
                if (!args.isEmpty() && args.get(0) instanceof PositionalArgumentNode toolArg
                        && (toolArg.expression().kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                        || toolArg.expression().kind() == SyntaxKind.QUALIFIED_NAME_REFERENCE)) {
                    aiToolRefs.add(toolArg.expression().toSourceCode().trim());
                }
            } else if (WorkflowConstants.REGISTER_HUMAN_TASK_METHOD.equals(methodName)
                    && !args.isEmpty() && args.get(0) instanceof PositionalArgumentNode posArg
                    && posArg.expression().kind() == SyntaxKind.STRING_LITERAL) {
                String raw = posArg.expression().toSourceCode().trim();
                if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    humanTaskNames.add(raw.substring(1, raw.length() - 1));
                }
            }
            methodCall.arguments().forEach(arg -> arg.accept(this));
            methodCall.expression().accept(this);
        }



    }
}
