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
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeParser;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TreeModifier;
import io.ballerina.lib.workflow.compiler.descriptor.WorkflowGraphBuilder;
import io.ballerina.tools.text.LineRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Injects each durable call site's identity into the call itself, so the runtime can stamp it
 * onto the invocation it records in history.
 *
 * <p>{@code ctx->callActivity(postToLedger, {…})} in the {@code else} arm of an {@code if}
 * becomes {@code ctx->callActivity(postToLedger, {…}, stepId = "postToLedger#2")}. The ids come
 * from {@link WorkflowGraphBuilder}, the same walk that writes the descriptor's graph, so the id
 * published as static structure and the id reported by a running execution cannot disagree.
 *
 * <p>Injection is same-line: the argument goes in before the closing parenthesis, so no line moves
 * and the positions the descriptor recorded still point at the user's code.
 *
 * <p>What is written is the id {@link WorkflowGraphBuilder} <em>resolved</em> for the call, which is
 * the chosen id whenever the call chose a free one. Going through the resolver rather than trusting
 * the call is what keeps the graph and the execution in agreement even when the resolver had to
 * intervene — a duplicate id is disambiguated with a suffix, and it is the disambiguated id that
 * both the descriptor publishes and the call sends.
 *
 * <p>Naming a step is worth it: a chosen id is stable across edits, where a generated ordinal moves
 * when a call to the same activity is added earlier.
 *
 * @since 0.9.0
 */
public class CallSiteInjector extends TreeModifier {

    private final SemanticModel semanticModel;
    /** Site ids of the workflow function currently being rewritten, keyed by call range. */
    private Map<LineRange, String> stepIdsInScope = Map.of();

    public CallSiteInjector(SemanticModel semanticModel) {
        this.semanticModel = semanticModel;
    }

    /**
     * Rewrites every workflow function in a document.
     *
     * @param root the document's root node
     * @return the root with call-site identities injected
     */
    public ModulePartNode inject(ModulePartNode root) {
        return (ModulePartNode) root.apply(this);
    }

    @Override
    public FunctionDefinitionNode transform(FunctionDefinitionNode fnDef) {
        if (!WorkflowPluginUtils.hasWorkflowAnnotation(fnDef, semanticModel,
                WorkflowConstants.PROCESS_ANNOTATION)) {
            // Sites are defined per workflow; nothing outside one carries an identity.
            return fnDef;
        }
        Map<LineRange, String> outer = stepIdsInScope;
        Map<LineRange, String> sites = new HashMap<>();
        for (WorkflowGraphBuilder.CallSite site : WorkflowGraphBuilder.build(fnDef, semanticModel).sites()) {
            sites.put(site.range(), site.stepId());
        }
        stepIdsInScope = sites;
        try {
            return super.transform(fnDef);
        } finally {
            stepIdsInScope = outer;
        }
    }

    @Override
    public RemoteMethodCallActionNode transform(RemoteMethodCallActionNode remoteCall) {
        // Read the identity from the original node: the rewrite that follows moves columns.
        String stepId = stepIdsInScope.get(remoteCall.location().lineRange());
        RemoteMethodCallActionNode rewritten = super.transform(remoteCall);
        if (stepId == null) {
            return rewritten;
        }
        return rewritten.modify().withArguments(withStepId(rewritten.arguments(), stepId)).apply();
    }

    /** The call's arguments carrying {@code stepId}, replacing any step id already there. */
    private static SeparatedNodeList<FunctionArgumentNode> withStepId(
            SeparatedNodeList<FunctionArgumentNode> args, String stepId) {
        NamedArgumentNode argument = NodeFactory.createNamedArgumentNode(
                NodeFactory.createSimpleNameReferenceNode(
                        NodeFactory.createIdentifierToken(WorkflowConstants.ARG_STEP_ID)),
                NodeFactory.createToken(SyntaxKind.EQUAL_TOKEN,
                        NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")),
                        NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" "))),
                NodeParser.parseExpression("\"" + stepId + "\""));

        List<Node> nodes = new ArrayList<>();
        boolean replaced = false;
        for (FunctionArgumentNode arg : args) {
            if (!nodes.isEmpty()) {
                nodes.add(comma());
            }
            if (isStepIdArgument(arg)) {
                nodes.add(argument);
                replaced = true;
            } else {
                nodes.add(arg);
            }
        }
        if (!replaced) {
            if (!nodes.isEmpty()) {
                nodes.add(comma());
            }
            nodes.add(argument);
        }
        return NodeFactory.createSeparatedNodeList(nodes);
    }

    private static boolean isStepIdArgument(FunctionArgumentNode arg) {
        return arg instanceof NamedArgumentNode named
                && WorkflowConstants.ARG_STEP_ID.equals(named.argumentName().name().text());
    }

    private static Node comma() {
        return NodeFactory.createToken(SyntaxKind.COMMA_TOKEN,
                NodeFactory.createEmptyMinutiaeList(),
                NodeFactory.createMinutiaeList(NodeFactory.createWhitespaceMinutiae(" ")));
    }
}
