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
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.BinaryExpressionNode;
import io.ballerina.compiler.syntax.tree.DoStatementNode;
import io.ballerina.compiler.syntax.tree.ElseBlockNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FieldAccessExpressionNode;
import io.ballerina.compiler.syntax.tree.ForEachStatementNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IfElseStatementNode;
import io.ballerina.compiler.syntax.tree.MatchClauseNode;
import io.ballerina.compiler.syntax.tree.MatchStatementNode;
import io.ballerina.compiler.syntax.tree.MethodCallExpressionNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.WaitActionNode;
import io.ballerina.compiler.syntax.tree.WhileStatementNode;
import io.ballerina.lib.workflow.compiler.WorkflowConstants;
import io.ballerina.lib.workflow.compiler.WorkflowPluginUtils;
import io.ballerina.tools.text.LineRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.BRANCH;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.COLUMN;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.EDGES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.FILE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.FROM;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_AWAIT_RESULT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_ACTIVITY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_BRANCH;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_CODE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_EXIT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.MODE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_CHILD_WORKFLOW;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_EVENT_WAIT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_HUMAN_TASK;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_LOOP;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_SLEEP;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_TRY;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.LABEL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.LINE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.NODES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.PARENT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.STEP_ID;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TARGET;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.WHEN;

/**
 * Builds one workflow's execution graph: the durable steps its body performs, in source order,
 * nested under the control flow that guards them.
 *
 * <p>The graph answers a question the activity list cannot: when the same activity is called
 * from both arms of an {@code if}, which arm ran? Every step gets a <b>step id</b> — the one
 * chosen at the call site with {@code stepId = "..."}, or {@code <target>#<ordinal>} generated from
 * the occurrences of that target within the workflow in source order. The runtime stamps that id
 * onto the activity invocation, so an execution's history joins back to this graph by step.
 *
 * <p>A generated id is deliberately an ordinal rather than a line number: it survives reformatting
 * and edits elsewhere in the file, and moves only when call sites of the same target are added,
 * removed, or reordered within the workflow — which is also why a chosen id is worth having, since
 * it does not move at all. Lexical positions travel alongside as display-only fields
 * ({@code line}, {@code column}), never as part of the identity.
 *
 * <p>Control flow is modelled as a structured block tree — the only shape a Ballerina function
 * body can take — and linked into edges by {@link #link}: siblings run in sequence, a
 * {@code BRANCH} enters each of its arms, a {@code LOOP} enters its body and is re-entered from
 * the body's tail, and every arm's tail flows on to whatever follows the container.
 *
 * @since 0.9.0
 */
public final class WorkflowGraphBuilder {

    /** Condition and pattern labels are display text; cap them so one long expression cannot
     * dominate the document that ships in every heartbeat. */
    private static final int MAX_LABEL_LENGTH = 120;

    // Where the step id lands when a caller passes it positionally rather than by name:
    // callActivity(activityFunction, args, T, retryPolicy, stepId) and
    // awaitHumanTask(taskName, userRoles, payload, title, description, timeout, T, stepId).
    private static final int CALL_ACTIVITY_STEP_ID_POSITION = 4;
    private static final int AWAIT_HUMAN_TASK_STEP_ID_POSITION = 7;
    // sleep(duration, stepId)
    private static final int SLEEP_STEP_ID_POSITION = 1;
    // runChildWorkflow(childWorkflow, input, stepId)
    private static final int RUN_CHILD_WORKFLOW_STEP_ID_POSITION = 2;
    // callWorkflow(childWorkflow, input, T, stepId)
    private static final int CALL_WORKFLOW_STEP_ID_POSITION = 3;

    private WorkflowGraphBuilder() {
    }

    /**
     * The step id chosen at a call site, or {@code null} when the call chooses none — or chooses
     * one this build cannot record, which {@code WorkflowValidatorTask} reports as an error.
     *
     * <p>Read here, and only here, so the graph the descriptor publishes, the ids the modifier
     * injects and the ids the validator checks are the same reading of the same source.
     *
     * @param remoteCall the {@code callActivity} / {@code awaitHumanTask} call
     * @return the chosen step id, or {@code null}
     */
    public static String chosenStepId(RemoteMethodCallActionNode remoteCall) {
        return blankAsAbsent(stepIdArgument(remoteCall));
    }

    /**
     * The step id chosen by a call that passes its arguments as a plain list — {@code ctx.sleep}, a
     * method rather than a remote call, so there is no method name to pick a positional index from.
     *
     * @param args the call's arguments
     * @return the chosen step id, or {@code null}
     */
    public static String chosenStepId(SeparatedNodeList<FunctionArgumentNode> args) {
        return blankAsAbsent(stepIdArgument(args, WorkflowConstants.SLEEP_METHOD));
    }

    private static String blankAsAbsent(Node expression) {
        String chosen = expression == null ? null : constantStepId(expression);

        // A blank id is no id, so the compiler generates one: nothing useful can be done with "" — it
        // would name a node after nothing — and it is what an empty form field produces, which makes
        // it an accident rather than a choice. Blankness is decided here rather than in
        // constantStepId, which answers only whether the value is a constant: a blank literal is a
        // perfectly good constant, and reporting it as an expression would be a lie.
        return chosen == null || chosen.isBlank() ? null : chosen;
    }

    /**
     * The expression a call passes as its step id, named or positional, or {@code null} when it
     * passes none. Both forms are read: a caller who supplies the typedesc and retry policy
     * positionally can reach the parameter positionally too, and a step id the graph missed but the
     * runtime received would be a mismatch nothing reports.
     *
     * @param remoteCall the {@code callActivity} / {@code awaitHumanTask} call
     * @return the step id expression, or {@code null}
     */
    public static Node stepIdArgument(RemoteMethodCallActionNode remoteCall) {
        return stepIdArgument(remoteCall.arguments(), remoteCall.methodName().name().text());
    }

    /**
     * The expression an argument list passes as its step id, named or positional, or {@code null}
     * when it passes none. The list form serves the calls that are not remote calls —
     * {@code ctx.sleep} — while reading both forms exactly as the remote-call form does.
     *
     * @param args       the call's arguments
     * @param methodName the context operation's method name, which fixes the positional index
     * @return the step id expression, or {@code null}
     */
    public static Node stepIdArgument(SeparatedNodeList<FunctionArgumentNode> args, String methodName) {
        for (FunctionArgumentNode arg : args) {
            if (arg instanceof NamedArgumentNode named
                    && WorkflowConstants.ARG_STEP_ID.equals(named.argumentName().name().text())) {
                return named.expression();
            }
        }
        int position = positionalStepIdIndex(methodName);
        if (position >= 0 && args.size() > position
                && args.get(position) instanceof PositionalArgumentNode positional) {
            return positional.expression();
        }
        return null;
    }

    /**
     * Where the step id lands when a caller passes it positionally to the named context
     * operation, or -1 when the operation has none. Shared with {@code CallSiteInjector},
     * which must REPLACE a positional step id rather than append a second, named one.
     *
     * @param methodName the context operation's method name
     * @return the zero-based positional index of {@code stepId}, or -1
     */
    public static int positionalStepIdIndex(String methodName) {
        return switch (methodName) {
            case WorkflowConstants.CALL_ACTIVITY_FUNCTION -> CALL_ACTIVITY_STEP_ID_POSITION;
            case WorkflowConstants.CALL_HUMAN_TASK_METHOD -> AWAIT_HUMAN_TASK_STEP_ID_POSITION;
            case WorkflowConstants.RUN_CHILD_WORKFLOW_METHOD -> RUN_CHILD_WORKFLOW_STEP_ID_POSITION;
            case WorkflowConstants.CALL_WORKFLOW_METHOD -> CALL_WORKFLOW_STEP_ID_POSITION;
            case WorkflowConstants.SLEEP_METHOD -> SLEEP_STEP_ID_POSITION;
            default -> -1;
        };
    }

    /**
     * A step id written as a compile-time constant string, or {@code null} for anything else. The
     * graph is written at build time, so an expression evaluated per execution cannot be described.
     *
     * @param expression the argument expression
     * @return the constant value, or {@code null}
     */
    public static String constantStepId(Node expression) {
        return WorkflowDescriptorBuilder.constantStringValue(expression);
    }

    /**
     * One durable step the runtime can be asked to stamp with its step id. The modifier matches
     * these against the call sites it rewrites, keyed by {@code range}, so the id the descriptor
     * publishes and the id the runtime reports are decided by this one walk.
     *
     * @param stepId the step's identity, e.g. {@code postToLedger#2}
     * @param kind   the node kind, e.g. {@code ACTIVITY}
     * @param target the called activity, task, or workflow name, or {@code null}
     * @param range  the source range of the call expression
     */
    public record CallSite(String stepId, String kind, String target, LineRange range) {
    }

    /**
     * The graph of one workflow body, plus the call sites the modifier needs.
     *
     * @param graph the {@code graph} object for the descriptor, or {@code null} when the body
     *              performs no durable step at all
     * @param sites every stampable call site, in source order — activities and human tasks, the
     *              two calls that take a {@code stepId}
     */
    public record Result(Map<String, Object> graph, List<CallSite> sites) {

        static Result empty() {
            return new Result(null, List.of());
        }
    }

    /**
     * Builds the graph for one workflow function body.
     *
     * @param fnDef         the workflow function
     * @param semanticModel the module's semantic model
     * @return the graph and its call sites
     */
    public static Result build(FunctionDefinitionNode fnDef, SemanticModel semanticModel) {
        ChosenIdCollector chosen = new ChosenIdCollector(semanticModel);
        fnDef.functionBody().accept(chosen);
        BlockCollector collector = new BlockCollector(semanticModel, chosen.ids);
        fnDef.functionBody().accept(collector);
        Block body = collector.root;
        // A control-flow construct with nothing durable anywhere below it — no step, no return —
        // is invisible to the runtime and would draw as an empty box, so it is not described.
        // Pruned before linking, so flow connects straight across it, which is also what actually
        // happens. Ordinals were assigned during the walk and are not renumbered: a gap in the
        // emitted ordinals is deliberate, so a construct gaining its first step later does not
        // move its siblings' identities.
        Set<String> pruned = new HashSet<>();
        pruneEmptyContainers(body, pruned);
        if (!pruned.isEmpty()) {
            collector.nodes.removeIf(node -> pruned.contains(node.get(STEP_ID)));
        }
        if (collector.nodes.isEmpty()) {
            return Result.empty();
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        link(body, List.of(), edges);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put(FILE, fnDef.location().lineRange().fileName());
        graph.put(NODES, new ArrayList<Object>(collector.nodes));
        graph.put(EDGES, new ArrayList<Object>(edges));
        return new Result(graph, collector.sites);
    }

    // ------------------------------------------------------------------
    // Linking: a structured block tree becomes edges
    // ------------------------------------------------------------------

    /**
     * Removes containers whose subtree holds neither a step nor a return, recording what was
     * removed. Bottom-up, so a container whose only content was another empty container goes too.
     *
     * @param block the block to prune
     * @param pruned accumulates the removed step ids
     * @return true when the block still holds anything
     */
    private static boolean pruneEmptyContainers(Block block, Set<String> pruned) {
        block.items.removeIf(item -> {
            if (item.branches.isEmpty()) {
                return false; // a step or a return — always content
            }
            boolean hasContent = false;
            for (Block arm : item.branches) {
                if (pruneEmptyContainers(arm, pruned)) {
                    hasContent = true;
                }
            }
            if (!hasContent) {
                pruned.add(item.stepId);
                return true;
            }
            return false;
        });
        return !block.items.isEmpty();
    }

    /**
     * Links one block's items in sequence and returns the block's exit steps — the steps a
     * following step must be reachable from. Recurses into containers: a branch's exits are the
     * exits of every arm (plus the branch itself when an arm is missing, because control can
     * skip it), and a loop's exit is the loop node itself (its body may run zero times).
     *
     * @param block the block to link
     * @param entry the steps that flow into this block
     * @param edges accumulates the edges
     * @return the block's exit steps
     */
    private static List<String> link(Block block, List<String> entry, List<Map<String, Object>> edges) {
        List<String> incoming = new ArrayList<>(new LinkedHashSet<>(entry));
        String pendingLabel = block.entryLabel;
        for (Item item : block.items) {
            for (String from : incoming) {
                edges.add(edge(from, item.stepId, pendingLabel));
            }
            pendingLabel = null;
            incoming = item.terminal ? List.of() : List.of(item.stepId);
            if (!item.branches.isEmpty()) {
                // A set, because an empty arm's exit is the container itself — which is also
                // what a skippable branch contributes, and one edge is enough.
                Set<String> exits = new LinkedHashSet<>();
                for (Block arm : item.branches) {
                    List<String> armExits = link(arm, List.of(item.stepId), edges);
                    if (arm.loopBack) {
                        for (String tail : armExits) {
                            if (!tail.equals(item.stepId)) {
                                edges.add(edge(tail, item.stepId, DescriptorFields.WHEN_REPEAT));
                            }
                        }
                        // A loop's body always returns to the loop node, so the loop itself is
                        // what a following step continues from.
                        armExits = List.of();
                    }
                    exits.addAll(armExits);
                }
                if (item.exitsWithoutBranch || exits.isEmpty()) {
                    exits.add(item.stepId);
                }
                incoming = new ArrayList<>(exits);
            }
        }
        return incoming;
    }

    private static Map<String, Object> edge(String from, String to, String when) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put(FROM, from);
        edge.put(DescriptorFields.TO, to);
        if (when != null) {
            edge.put(WHEN, when);
        }
        return edge;
    }

    // ------------------------------------------------------------------
    // The block tree
    // ------------------------------------------------------------------

    /**
     * Collects the step ids the calls of one workflow chose, ahead of the walk that generates ids
     * for the rest. Two passes rather than one because a generated id has to avoid every chosen id,
     * including those of calls that come later in the body.
     */
    private static final class ChosenIdCollector extends NodeVisitor {

        private final SemanticModel semanticModel;
        private final Set<String> ids = new LinkedHashSet<>();

        ChosenIdCollector(SemanticModel semanticModel) {
            this.semanticModel = semanticModel;
        }

        @Override
        public void visit(RemoteMethodCallActionNode remoteCall) {
            String chosen = chosenStepId(remoteCall);
            if (chosen != null) {
                ids.add(chosen);
            }
            remoteCall.arguments().forEach(argument -> argument.accept(this));
        }

        @Override
        public void visit(MethodCallExpressionNode methodCall) {
            // `ctx.sleep` chooses ids too, and a chosen id this pass misses is one a generated
            // id can silently take — resolveChosen would then rename the sleep with no warning.
            boolean onContext = semanticModel.typeOf(methodCall.expression())
                    .map(WorkflowPluginUtils::isContextType)
                    .orElse(false);
            if (onContext && WorkflowConstants.SLEEP_METHOD.equals(
                    methodCall.methodName().toSourceCode().trim())) {
                String chosen = chosenStepId(methodCall.arguments());
                if (chosen != null) {
                    ids.add(chosen);
                }
            }
            methodCall.arguments().forEach(argument -> argument.accept(this));
        }
    }

    /** An ordered run of steps, optionally an arm of a container. */
    private static final class Block {
        final List<Item> items = new ArrayList<>();
        /** Labels the edge from the container into this arm ({@code then}, {@code else}, …). */
        String entryLabel;
        /** True when this arm is a loop body, whose tail flows back into the loop node. */
        boolean loopBack;

        Block(String entryLabel, boolean loopBack) {
            this.entryLabel = entryLabel;
            this.loopBack = loopBack;
        }
    }

    /** One step: a leaf, or a container with one arm per branch. */
    private static final class Item {
        final String stepId;
        final List<Block> branches = new ArrayList<>();
        /** True when control can bypass every arm — an {@code if} with no {@code else}. */
        boolean exitsWithoutBranch;
        /** True when flow ends at this item (a {@code return}): it contributes no exits. */
        boolean terminal;

        Item(String stepId) {
            this.stepId = stepId;
        }
    }

    /**
     * Walks a workflow body, emitting a node per durable step and per control-flow construct,
     * and assembling the block tree the linker consumes. Nodes are emitted in source order, so
     * the {@code nodes} array reads like the code.
     */
    private static final class BlockCollector extends NodeVisitor {

        private final SemanticModel semanticModel;
        /** Ids the calls in this workflow chose, so generation never steals one. */
        private final Set<String> chosenIds;
        /** Ids already handed out, so nothing is issued twice. */
        private final Set<String> taken = new LinkedHashSet<>();
        final List<Map<String, Object>> nodes = new ArrayList<>();
        /** The same nodes by step id, for the emitters that update a node after creating it. */
        final Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        final List<CallSite> sites = new ArrayList<>();
        final Block root = new Block(null, false);

        private final Map<String, Integer> ordinals = new LinkedHashMap<>();
        private Block current = root;
        private String currentParent;
        private String currentBranch;

        BlockCollector(SemanticModel semanticModel, Set<String> chosenIds) {
            this.semanticModel = semanticModel;
            this.chosenIds = chosenIds;
        }

        // ── Blocks: statement-level walking, so non-durable code is captured ─
        //
        // The diagram must read like the source, so code that does nothing durable still appears —
        // but collapsed: a maximal run of consecutive silent statements becomes one CODE node
        // ("4 statements"), because per-statement nodes would drown the steps the diagram exists
        // to show. A statement is silent when walking it emitted nothing; the durable call inside
        // `string x = check ctx->callActivity(...)` emits, so that statement is a step, not code.
        // Exit statements are excluded even where they emit nothing (the top level): a return is
        // the body ending, not code, and folding it in would make the count lie.

        @Override
        public void visit(io.ballerina.compiler.syntax.tree.FunctionBodyBlockNode body) {
            walkStatements(body.statements());
        }

        @Override
        public void visit(io.ballerina.compiler.syntax.tree.BlockStatementNode block) {
            walkStatements(block.statements());
        }

        private void walkStatements(io.ballerina.compiler.syntax.tree.NodeList<
                io.ballerina.compiler.syntax.tree.StatementNode> statements) {
            Map<String, Object> codeNode = null;
            int codeCount = 0;
            for (io.ballerina.compiler.syntax.tree.StatementNode statement : statements) {
                boolean exitStatement = statement
                        instanceof io.ballerina.compiler.syntax.tree.ReturnStatementNode
                        || statement instanceof io.ballerina.compiler.syntax.tree.FailStatementNode
                        || statement instanceof io.ballerina.compiler.syntax.tree.PanicStatementNode;
                int before = nodes.size();
                statement.accept(this);
                if (nodes.size() > before || exitStatement) {
                    codeNode = null;
                    codeCount = 0;
                    continue;
                }
                if (codeNode == null) {
                    Item item = addNode(KIND_CODE, "code", null, null, statement);
                    codeNode = nodesById.get(item.stepId);
                    codeCount = 0;
                }
                codeCount++;
                codeNode.put(LABEL, codeCount == 1 ? "1 statement" : codeCount + " statements");
            }
        }

        // ── Control flow ──────────────────────────────────────────────────

        @Override
        public void visit(io.ballerina.compiler.syntax.tree.ReturnStatementNode returnStatement) {
            returnStatement.expression().ifPresent(expression -> expression.accept(this));
            exitNode("return", returnStatement);
        }

        @Override
        public void visit(io.ballerina.compiler.syntax.tree.FailStatementNode failStatement) {
            failStatement.expression().accept(this);
            exitNode("fail", failStatement);
        }

        @Override
        public void visit(io.ballerina.compiler.syntax.tree.PanicStatementNode panicStatement) {
            panicStatement.expression().accept(this);
            exitNode("panic", panicStatement);
        }

        /**
         * Emits an exit node — a return, fail or panic inside an arm. It is the only thing that
         * can explain a run that ended without reaching the steps after the branch, and it is
         * terminal: the linker draws no edge out of it, because real flow has none. An exit never
         * appears in an execution, so consumers attribute a run to one only when it is the single
         * exit consistent with the observed last step. At the top level it stays invisible — the
         * body's own exit already says the workflow can end there.
         */
        private void exitNode(String mode, Node statement) {
            if (current == root) {
                return;
            }
            Item item = addNode(KIND_EXIT, mode, null, null, statement);
            item.terminal = true;
            nodesById.get(item.stepId).put(MODE, mode);
        }

        @Override
        public void visit(IfElseStatementNode ifElse) {
            // A durable step inside the condition runs before the branch it decides.
            ifElse.condition().accept(this);
            Item item = addNode(KIND_BRANCH, "if", null, label(ifElse.condition()), ifElse);
            arm(item, DescriptorFields.WHEN_THEN, false, () -> ifElse.ifBody().accept(this));
            Optional<Node> elseBody = ifElse.elseBody();
            if (elseBody.isPresent()) {
                arm(item, DescriptorFields.WHEN_ELSE, false, () -> elseBody.get().accept(this));
            } else {
                item.exitsWithoutBranch = true;
            }
        }

        @Override
        public void visit(ElseBlockNode elseBlock) {
            // The `else` of an `else if` holds another if-else statement; visiting the body
            // directly keeps that nested chain flat in the block tree, so an `else if` reads as
            // a branch inside the outer branch's else arm.
            elseBlock.elseBody().accept(this);
        }

        @Override
        public void visit(WhileStatementNode whileNode) {
            whileNode.condition().accept(this);
            Item item = addNode(KIND_LOOP, "while", null, label(whileNode.condition()), whileNode);
            item.exitsWithoutBranch = true;
            arm(item, DescriptorFields.WHEN_BODY, true, () -> whileNode.whileBody().accept(this));
        }

        @Override
        public void visit(ForEachStatementNode forEach) {
            forEach.actionOrExpressionNode().accept(this);
            Item item = addNode(KIND_LOOP, "foreach", null, label(forEach.actionOrExpressionNode()), forEach);
            item.exitsWithoutBranch = true;
            arm(item, DescriptorFields.WHEN_BODY, true, () -> forEach.blockStatement().accept(this));
        }

        @Override
        public void visit(MatchStatementNode match) {
            match.condition().accept(this);
            Item item = addNode(KIND_BRANCH, "match", null, label(match.condition()), match);
            item.exitsWithoutBranch = true;
            for (MatchClauseNode clause : match.matchClauses()) {
                StringBuilder patterns = new StringBuilder();
                for (Node pattern : clause.matchPatterns()) {
                    if (!patterns.isEmpty()) {
                        patterns.append('|');
                    }
                    patterns.append(pattern.toSourceCode().trim());
                }
                String branchLabel = truncate(patterns.toString());
                arm(item, branchLabel, false, () -> clause.blockStatement().accept(this));
            }
        }

        @Override
        public void visit(DoStatementNode doStatement) {
            if (doStatement.onFailClause().isEmpty()) {
                doStatement.blockStatement().accept(this);
                return;
            }
            Item item = addNode(KIND_TRY, "do", null, null, doStatement);
            arm(item, DescriptorFields.WHEN_DO, false, () -> doStatement.blockStatement().accept(this));
            arm(item, DescriptorFields.WHEN_ON_FAIL, false,
                    () -> doStatement.onFailClause().get().blockStatement().accept(this));
        }

        /** Collects one arm of a container, with the arm's nodes parented to it. */
        private void arm(Item container, String branchLabel, boolean loopBack, Runnable walk) {
            Block outerBlock = current;
            String outerParent = currentParent;
            String outerBranch = currentBranch;

            Block arm = new Block(branchLabel, loopBack);
            container.branches.add(arm);
            current = arm;
            currentParent = container.stepId;
            currentBranch = branchLabel;
            try {
                walk.run();
            } finally {
                current = outerBlock;
                currentParent = outerParent;
                currentBranch = outerBranch;
            }
        }

        // ── Durable steps ─────────────────────────────────────────────────

        @Override
        public void visit(RemoteMethodCallActionNode remoteCall) {
            String methodName = remoteCall.methodName().name().text();
            switch (methodName) {
                case WorkflowConstants.CALL_ACTIVITY_FUNCTION -> {
                    String target = activityTargetOf(remoteCall.arguments());
                    if (target != null) {
                        addCallSite(KIND_ACTIVITY, target, remoteCall);
                    }
                }
                case WorkflowConstants.CALL_HUMAN_TASK_METHOD -> {
                    String target = constantTaskNameOf(remoteCall.arguments());
                    if (target != null) {
                        addCallSite(KIND_HUMAN_TASK, target, remoteCall);
                    }
                }
                case WorkflowConstants.CALL_WORKFLOW_METHOD, WorkflowConstants.RUN_CHILD_WORKFLOW_METHOD -> {
                    String target = functionNameOf(remoteCall.arguments());
                    if (target != null) {
                        addCallSite(KIND_CHILD_WORKFLOW, target, remoteCall);
                    }
                }
                default -> {
                    // Other remote methods (await, getChildWorkflowResult, sendDataToChildWorkflow)
                    // read or coordinate state rather than adding a step of their own.
                }
            }
            remoteCall.arguments().forEach(arg -> arg.accept(this));
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbol = semanticModel.symbol(callNode);
            if (symbol.isPresent() && symbol.get().kind() == SymbolKind.FUNCTION) {
                FunctionSymbol fnSymbol = (FunctionSymbol) symbol.get();
                if (WorkflowPluginUtils.hasWorkflowAnnotation(fnSymbol, WorkflowConstants.ACTIVITY_ANNOTATION)) {
                    // A direct call to an @Activity function: the descriptor lists it among the
                    // workflow's activities, so the graph shows it as a step too.
                    fnSymbol.getName().ifPresent(name -> addNode(KIND_ACTIVITY, name, name, null, callNode));
                } else {
                    addWorkflowModuleStep(fnSymbol, callNode);
                }
            }
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Describes {@code workflow:getWorkflowResult} called from inside a workflow: the runtime
         * routes it through an implicit activity so it stays deterministic, and it therefore appears
         * in history as the activity {@code workflow:getResult}. It is a step the author wrote —
         * leaving it out of the graph would hide a durable wait — unlike {@code getInfo} and the
         * agent loop's polling, which are reads.
         */
        private void addWorkflowModuleStep(FunctionSymbol fnSymbol, FunctionCallExpressionNode callNode) {
            if (!isWorkflowModule(fnSymbol)) {
                return;
            }
            String name = fnSymbol.getName().orElse(null);
            if (name == null) {
                return;
            }
            // Only getWorkflowResult reaches here: `workflow:run` and `workflow:sendData` are compile
            // errors inside a workflow (WORKFLOW_138 sends the author to ctx->runChildWorkflow and
            // ctx->sendDataToChildWorkflow), so neither can appear in a workflow's history.
            if (WorkflowConstants.GET_WORKFLOW_RESULT_FUNCTION.equals(name)) {
                addNode(KIND_AWAIT_RESULT, "awaitResult", null, null, callNode);
            }
        }

        private boolean isWorkflowModule(FunctionSymbol fnSymbol) {
            return fnSymbol.getModule()
                    .map(module -> WorkflowConstants.PACKAGE_ORG.equals(module.id().orgName())
                            && WorkflowConstants.PACKAGE_NAME.equals(module.id().moduleName()))
                    .orElse(false);
        }

        @Override
        public void visit(MethodCallExpressionNode methodCall) {
            // Matched on the receiver's type as well as the name. On the name alone, any
            // user-defined `sleep()` in a workflow body became a sleep node — and the modifier
            // would then inject a stepId argument into a method that has no such parameter.
            boolean onContext = semanticModel.typeOf(methodCall.expression())
                    .map(WorkflowPluginUtils::isContextType)
                    .orElse(false);
            if (onContext && WorkflowConstants.SLEEP_METHOD.equals(
                    methodCall.methodName().toSourceCode().trim())) {
                // `ctx.sleep` is a plain method, not a remote call, so its chosen id is read from the
                // method's own arguments.
                String chosen = chosenStepId(methodCall.arguments());
                Item item = addNode(KIND_SLEEP, "sleep", null, null, methodCall, chosen);
                sites.add(new CallSite(item.stepId, KIND_SLEEP, null, methodCall.location().lineRange()));
            }
            methodCall.arguments().forEach(arg -> arg.accept(this));
        }

        @Override
        public void visit(WaitActionNode waitAction) {
            // `check wait events.approval` — and its alternate form
            // `wait events.a | events.b`, which waits on whichever arrives first.
            List<String> eventNames = new ArrayList<>();
            collectEventNames(waitAction.waitFutureExpr(), eventNames);
            if (eventNames.isEmpty()) {
                return;
            }
            addNode(KIND_EVENT_WAIT, "wait", String.join("|", eventNames), null, waitAction);
        }

        private void collectEventNames(Node expression, List<String> names) {
            if (expression instanceof BinaryExpressionNode binary) {
                collectEventNames(binary.lhsExpr(), names);
                collectEventNames(binary.rhsExpr(), names);
                return;
            }
            if (expression instanceof FieldAccessExpressionNode fieldAccess
                    && fieldAccess.fieldName() instanceof SimpleNameReferenceNode field) {
                names.add(field.name().text());
            }
        }

        // ── Node construction ─────────────────────────────────────────────

        /**
         * Adds a node and records it as a stampable call site. A step id chosen at the call site
         * becomes the node's identity; the ordinal is consumed either way, so naming one call of
         * an activity does not renumber the others.
         */
        private void addCallSite(String kind, String target, RemoteMethodCallActionNode source) {
            Item item = addNode(kind, target, target, null, source, chosenStepId(source));
            sites.add(new CallSite(item.stepId, kind, target, source.location().lineRange()));
        }

        /**
         * Adds one node in source order and appends it to the block being collected.
         *
         * @param kind       the node kind
         * @param idPrefix   what a generated id counts occurrences of
         * @param target     the named activity, task, workflow, or event, or {@code null}
         * @param label      display text (a condition or matched expression), or {@code null}
         * @param source     the syntax node the position comes from
         * @return the item added to the current block
         */
        private Item addNode(String kind, String idPrefix, String target, String label, Node source) {
            return addNode(kind, idPrefix, target, label, source, null);
        }

        private Item addNode(String kind, String idPrefix, String target, String label, Node source,
                             String chosenStepId) {
            // The ordinal advances even when the step is named, or naming one call of an activity
            // would renumber its siblings — the very fragility a chosen id exists to avoid.
            String generated = nextGeneratedId(idPrefix);
            String stepId = chosenStepId != null ? resolveChosen(chosenStepId) : generated;
            taken.add(stepId);
            LineRange range = source.location().lineRange();

            Map<String, Object> node = new LinkedHashMap<>();
            nodesById.put(stepId, node);
            node.put(STEP_ID, stepId);
            node.put(KIND, kind);
            if (target != null) {
                node.put(TARGET, target);
            }
            if (label != null) {
                node.put(LABEL, label);
            }
            if (currentParent != null) {
                node.put(PARENT, currentParent);
                node.put(BRANCH, currentBranch);
            }
            // Ballerina positions are zero-based; diagnostics and editors are one-based.
            node.put(LINE, range.startLine().line() + 1);
            node.put(COLUMN, range.startLine().offset() + 1);
            nodes.add(node);

            Item item = new Item(stepId);
            current.items.add(item);
            return item;
        }

        /**
         * The next generated id for {@code prefix}, stepping over ids already used and ids a call
         * elsewhere in this workflow chose for itself. Skipping is what keeps chosen and generated
         * ids from colliding, and it is why a chosen id needs no reserved characters — a step may be
         * called {@code order#1} if that is what reads best.
         */
        private String nextGeneratedId(String prefix) {
            String candidate;
            do {
                candidate = prefix + "#" + ordinals.merge(prefix, 1, Integer::sum);
            } while (isClaimed(candidate));
            return candidate;
        }

        /**
         * The id a step actually gets when its call chose {@code chosen}: that id, or — when another
         * step already has it — the same name with the first free numeric suffix.
         *
         * <p>Two steps cannot share an id, since an id names one node of the graph. Rather than
         * refusing to build, the later one is disambiguated and {@code WorkflowValidatorTask} warns:
         * duplicating an id is easy (copy a step in the designer, paste a call) and the honest
         * repair is obvious. The disambiguated id is what the descriptor publishes <em>and</em> what
         * the modifier writes back to the call, so the graph and the execution still agree.
         */
        private String resolveChosen(String chosen) {
            if (!taken.contains(chosen)) {
                return chosen;
            }
            int suffix = 2;
            String candidate;
            do {
                candidate = chosen + "#" + suffix++;
            } while (isClaimed(candidate));
            return candidate;
        }

        /** Whether an id is already used, or spoken for by a call that chose it. */
        private boolean isClaimed(String candidate) {
            return taken.contains(candidate) || chosenIds.contains(candidate);
        }

        // ── Argument reading ──────────────────────────────────────────────

        /**
         * The activity function of a {@code callActivity} — the first positional argument,
         * or the {@code activityFunction} named argument (a caller who names it must still
         * produce a graph node and a {@code CallSite}, or the call vanishes from the
         * descriptor and never receives its step id).
         */
        private String activityTargetOf(SeparatedNodeList<FunctionArgumentNode> args) {
            return callTargetOf(args, "activityFunction");
        }

        /** The child workflow of a {@code runChildWorkflow}/{@code callWorkflow} — positional or named. */
        private String functionNameOf(SeparatedNodeList<FunctionArgumentNode> args) {
            return callTargetOf(args, "childWorkflow");
        }

        private String callTargetOf(SeparatedNodeList<FunctionArgumentNode> args, String paramName) {
            ExpressionNode expression = null;
            if (!args.isEmpty() && args.get(0) instanceof PositionalArgumentNode posArg) {
                expression = posArg.expression();
            } else {
                for (FunctionArgumentNode arg : args) {
                    if (arg instanceof NamedArgumentNode named
                            && paramName.equals(named.argumentName().name().text())) {
                        expression = named.expression();
                        break;
                    }
                }
            }
            if (expression == null) {
                return null;
            }
            Optional<Symbol> symbol = semanticModel.symbol(expression);
            if (symbol.isEmpty() || symbol.get().kind() != SymbolKind.FUNCTION) {
                return null;
            }
            return symbol.get().getName().orElse(null);
        }

        /** The constant task name of an {@code awaitHumanTask} call, named or positional. */
        private String constantTaskNameOf(SeparatedNodeList<FunctionArgumentNode> args) {
            for (FunctionArgumentNode arg : args) {
                if (arg instanceof NamedArgumentNode named
                        && WorkflowConstants.ARG_TASK_NAME.equals(named.argumentName().name().text())) {
                    return WorkflowDescriptorBuilder.constantStringValue(named.expression());
                }
            }
            if (!args.isEmpty() && args.get(0) instanceof PositionalArgumentNode posArg) {
                return WorkflowDescriptorBuilder.constantStringValue(posArg.expression());
            }
            return null;
        }

        private String label(Node expression) {
            return truncate(expression.toSourceCode().trim());
        }
    }

    private static String truncate(String text) {
        String flattened = text.replaceAll("\\s+", " ");
        return flattened.length() <= MAX_LABEL_LENGTH
                ? flattened : flattened.substring(0, MAX_LABEL_LENGTH - 1) + "…";
    }
}
