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

import io.ballerina.lib.workflow.compiler.descriptor.WorkflowDescriptorBuilder;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the descriptor's {@code graph}: that it describes each workflow's durable steps with a
 * stable per-call-site identity, links them the way control flow runs, draws an agent as the star
 * its model implies — and that the runtime is handed the very same identities, by the source
 * modifier, at every call site.
 *
 * <p>The assertions are structural rather than golden where the claim is about meaning ("both
 * arms of the {@code if} get their own site", "a loop body returns to its loop"); the golden
 * document in {@link WorkflowDescriptorTest} pins the serialized shape.
 *
 * @since 0.9.0
 */
public class WorkflowGraphTest {

    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources",
            "ballerina_sources").toAbsolutePath();
    private static final Path DISTRIBUTION_PATH = Paths.get("../", "target", "ballerina-runtime")
            .toAbsolutePath();
    private static final String PACKAGE = "graph_generation";
    private static final String AGENT_PACKAGE = "graph_agent_generation";

    static {
        System.setProperty("ballerina.home", DISTRIBUTION_PATH.toString());
    }

    /** The built document, parsed once: every test here reads the same descriptor. */
    private Map<String, Object> descriptor;

    @BeforeClass
    public void buildDescriptor() {
        descriptor = descriptorOf(PACKAGE);
    }

    private static Map<String, Object> descriptorOf(String packageName) {
        BuildProject project = loadProject(packageName);
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());
        byte[] bytes = WorkflowDescriptorBuilder.build(project.currentPackage(), compilation);
        Assert.assertNotNull(bytes, "The package declares workflows or agents; a descriptor must be built");
        return MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    // ── The motivating case ───────────────────────────────────────────────────

    @Test
    public void testBothArmsOfABranchGetTheirOwnSite() {
        Map<String, Object> graph = graphOf("shipOrder");
        List<Map<String, Object>> ledgerCalls = nodesTargeting(graph, "postToLedger");

        Assert.assertEquals(ledgerCalls.size(), 2,
                "postToLedger is called from two arms, so the graph has two nodes for it");
        Assert.assertEquals(ledgerCalls.get(0).get("stepId"), "postToLedger#1");
        Assert.assertEquals(ledgerCalls.get(1).get("stepId"), "postToLedger#2");

        // The identity has to say which arm, or the whole exercise is pointless.
        Assert.assertEquals(ledgerCalls.get(0).get("branch"), "then",
                "The first call is in the if arm");
        Assert.assertEquals(ledgerCalls.get(1).get("branch"), "then",
                "The second is the then arm of the else-if, which nests inside the outer else");
        Assert.assertNotEquals(ledgerCalls.get(0).get("parent"), ledgerCalls.get(1).get("parent"),
                "Two same-named branches are only distinguishable by their parent branch node");
    }

    @Test
    public void testElseIfNestsInsideTheOuterElse() {
        Map<String, Object> graph = graphOf("shipOrder");
        Map<String, Object> outerBranch = nodeAt(graph, "if#1");
        Map<String, Object> innerBranch = nodeAt(graph, "if#2");

        Assert.assertEquals(outerBranch.get("kind"), "BRANCH");
        Assert.assertEquals(outerBranch.get("label"), "stock.inStock",
                "A branch carries its condition as display text");
        Assert.assertEquals(innerBranch.get("parent"), "if#1");
        Assert.assertEquals(innerBranch.get("branch"), "else",
                "`else if` is an if inside the outer else arm");
        Assert.assertTrue(hasEdge(graph, "if#1", "if#2", "else"),
                "The outer branch enters the else-if on its else arm");
    }

    @Test
    public void testAChosenStepIdBecomesTheNodesIdentity() {
        Map<String, Object> graph = graphOf("namedSteps");
        List<Map<String, Object>> calls = nodesTargeting(graph, "bookCarrier");

        Assert.assertEquals(calls.get(0).get("stepId"), "book-primary",
                "A chosen id is what the graph publishes, so an execution reporting it can be placed");
        Assert.assertEquals(calls.get(1).get("stepId"), "bookCarrier#2",
                "and the ordinal is still consumed, so naming one call never renumbers a sibling");
        Assert.assertTrue(hasEdge(graph, "book-primary", "bookCarrier#2", null),
                "Edges refer to whichever id the node carries");
    }

    @Test
    public void testADuplicateChosenStepIdIsDisambiguatedEverywhere() {
        // Sharing an id is repaired rather than rejected — and the repair has to reach the call as
        // well as the graph, or the execution would report "book" for a node described as "book#2".
        BuildProject project = loadProject("duplicate_step_id");
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "A duplicate is a warning, not an error: " + compilation.diagnosticResult().diagnostics());

        Map<String, Object> graph = asObject(((Map<?, ?>) ((List<?>) descriptorOf("duplicate_step_id")
                .get("workflows")).get(0)).get("graph"));
        List<Map<String, Object>> calls = nodesTargeting(graph, "bookCarrier");
        Assert.assertEquals(calls.get(0).get("stepId"), "book", "The first step keeps the chosen id");
        Assert.assertEquals(calls.get(1).get("stepId"), "book#2", "and the second is suffixed");

        String rewritten = allSourcesOf(project);
        Assert.assertTrue(rewritten.contains("stepId = \"book#2\""),
                "The suffixed id must be written back to the call, so the run reports what the graph says");
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    public void testSequentialStepsAreLinkedInOrder() {
        Map<String, Object> graph = graphOf("shipOrder");
        Assert.assertTrue(hasEdge(graph, "checkStock#1", "if#1", null),
                "The step before the branch flows into it");
        Assert.assertTrue(hasEdge(graph, "postToLedger#1", "bookCarrier#1", null),
                "Siblings in the same arm run in sequence");
    }

    @Test
    public void testAnUnguardedBranchCanBeSkipped() {
        // `if decision.approved { ctx.sleep(...) }` has no else, so control can bypass the arm:
        // the branch node itself must remain an exit, or a following step looks unreachable.
        Map<String, Object> graph = graphOf("shipOrder");
        Map<String, Object> innerIf = nodeAt(graph, "if#3");
        Assert.assertEquals(innerIf.get("kind"), "BRANCH");
        Assert.assertTrue(hasEdge(graph, "if#3", "sleep#1", "then"));
        Assert.assertFalse(hasEdge(graph, "sleep#1", "if#3", null),
                "A branch is not a loop: its arm does not flow back");
    }

    @Test
    public void testALoopBodyReturnsToItsLoop() {
        Map<String, Object> graph = graphOf("retryShipments");
        Map<String, Object> loop = nodeAt(graph, "while#1");
        Assert.assertEquals(loop.get("kind"), "LOOP");
        Assert.assertTrue(hasEdge(graph, "while#1", "bookCarrier#1", "body"),
                "The loop enters its body");
        Assert.assertTrue(hasEdge(graph, "bookCarrier#1", "while#1", "repeat"),
                "and the body's tail returns to it");
        // The loop, not its body, is what the next step follows.
        Assert.assertTrue(hasEdge(graph, "while#1", "foreach#1", null));
    }

    @Test
    public void testForeachIsALoopOverItsExpression() {
        Map<String, Object> graph = graphOf("retryShipments");
        Map<String, Object> loop = nodeAt(graph, "foreach#1");
        Assert.assertEquals(loop.get("kind"), "LOOP");
        Assert.assertEquals(loop.get("label"), "0 ..< request.quantity");
        Assert.assertTrue(hasEdge(graph, "foreach#1", "notifyWarehouse#1", "body"));
    }

    @Test
    public void testFailureHandlingIsItsOwnArm() {
        Map<String, Object> graph = graphOf("reconcileShipment");
        Map<String, Object> tryNode = nodeAt(graph, "do#1");
        Assert.assertEquals(tryNode.get("kind"), "TRY");
        Assert.assertTrue(hasEdge(graph, "do#1", "notifyWarehouse#1", "do"),
                "The guarded block is one arm");
        Assert.assertTrue(hasEdge(graph, "do#1", "notifyWarehouse#2", "onFail"),
                "and the handler is the other — so a failed execution is drawn on the path it took");
    }

    @Test
    public void testEventWaitsAndMatchClausesAreDescribed() {
        Map<String, Object> graph = graphOf("reconcileShipment");
        Map<String, Object> wait = nodeAt(graph, "wait#1");
        Assert.assertEquals(wait.get("kind"), "EVENT_WAIT");
        Assert.assertEquals(wait.get("target"), "managerDecision");

        Map<String, Object> match = nodeAt(graph, "match#1");
        Assert.assertEquals(match.get("kind"), "BRANCH");
        Assert.assertTrue(hasEdge(graph, "match#1", "postToLedger#1", "true"),
                "A match clause's patterns label the edge into it");
        Assert.assertTrue(hasEdge(graph, "match#1", "bookCarrier#1", "false"));
    }

    @Test
    public void testHumanTaskSitesAreDescribed() {
        Map<String, Object> graph = graphOf("shipOrder");
        Map<String, Object> task = nodeAt(graph, "restockApproval#1");
        Assert.assertEquals(task.get("kind"), "HUMAN_TASK");
        Assert.assertEquals(task.get("target"), "restockApproval");
        Assert.assertEquals(task.get("branch"), "else");
    }

    @Test
    public void testLexicalPositionsAreCarriedForDisplay() {
        Map<String, Object> graph = graphOf("shipOrder");
        Assert.assertEquals(graph.get("file"), "main.bal");
        for (Map<String, Object> node : nodesOf(graph)) {
            Assert.assertTrue(((Number) node.get("line")).intValue() > 0,
                    "Every node carries a one-based line: " + node);
            Assert.assertTrue(((Number) node.get("column")).intValue() > 0,
                    "Every node carries a one-based column: " + node);
        }
    }

    @Test
    public void testAChildWorkflowIsDescribedButNeverStamped() {
        // Only callActivity and awaitHumanTask take a stepId. Stamping a child workflow call made
        // every one of them fail to compile — caught by the integration package, so pin it here
        // where the feedback is a minute rather than eight.
        Map<String, Object> graph = graphOf("dispatchShipment");
        Assert.assertEquals(nodeAt(graph, "retryShipments#1").get("kind"), "CHILD_WORKFLOW");

        BuildProject project = loadProject(PACKAGE);
        project.currentPackage().runCodeGenAndModifyPlugins();
        Assert.assertEquals(project.currentPackage().getCompilation().diagnosticResult().errorCount(), 0,
                "The rewritten sources must still compile: "
                        + project.currentPackage().getCompilation().diagnosticResult().diagnostics());
        Assert.assertTrue(allSourcesOf(project).contains("runChildWorkflow(retryShipments, request)"),
                "The child workflow call must reach the compiler exactly as written");
    }

    // ── The agent's star ──────────────────────────────────────────────────────

    @Test
    public void testAnAgentIsDrawnAsAStar() {
        // Its own package: the agent's ai:Wso2ModelProvider brings in ballerina/ai.
        Map<String, Object> graph = agentGraphOf(descriptorOf(AGENT_PACKAGE), "expenseAgent");

        Assert.assertEquals(nodeAt(graph, "agent").get("target"), "expenseAgent");
        Assert.assertEquals(nodeAt(graph, "model").get("label"), "expenseModel",
                "Which model reference was configured is structure worth drawing");

        // Channels feed the agent; capabilities hang off it. The direction is the semantics.
        Assert.assertTrue(hasEdge(graph, "event:billSubmitted", "agent", "in"));
        Assert.assertTrue(hasEdge(graph, "task:approveExpense", "agent", "in"));
        Assert.assertTrue(hasEdge(graph, "agent", "tool:makePayment", "out"));
        Assert.assertTrue(hasEdge(graph, "agent", "tool:validateClaim", "out"));
        Assert.assertTrue(hasEdge(graph, "agent", "model", "out"));

        Assert.assertEquals(nodeAt(graph, "tool:makePayment").get("source"), "ACTIVITY",
                "An activity-backed tool is drawn differently from an AI tool");
        Assert.assertEquals(nodeAt(graph, "tool:validateClaim").get("source"), "AI_TOOL");
    }

    // ── The runtime gets the same ids ─────────────────────────────────────────

    @Test
    public void testEveryDurableCallSiteIsStampedWithItsDescriptorSite() {
        BuildProject project = loadProject(PACKAGE);
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "The rewritten sources must still compile: "
                        + compilation.diagnosticResult().diagnostics());

        String rewritten = allSourcesOf(project);
        // Whatever the descriptor claims about activities and human tasks, the rewritten source
        // must actually pass — otherwise the graph describes sites no execution can report.
        List<String> stampable = new ArrayList<>();
        for (Object workflow : (List<?>) descriptor.get("workflows")) {
            Map<String, Object> graph = asObject(((Map<?, ?>) workflow).get("graph"));
            for (Map<String, Object> node : nodesOf(graph)) {
                String kind = (String) node.get("kind");
                if ("ACTIVITY".equals(kind) || "HUMAN_TASK".equals(kind)) {
                    stampable.add((String) node.get("stepId"));
                }
            }
        }
        Assert.assertFalse(stampable.isEmpty(), "The test package calls activities");
        for (String site : stampable) {
            Assert.assertTrue(rewritten.contains("stepId = \"" + site + "\""),
                    "Call site " + site + " is in the descriptor but was never injected");
        }
    }

    @Test
    public void testAChosenStepIdReachesTheCallUnchanged() {
        // Honouring the chosen id is only safe because the graph records the same one — that pair
        // is what the validator's uniqueness and constant-ness rules protect.
        BuildProject project = loadProject("graph_step_id_chosen");
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());

        String rewritten = allSourcesOf(project);
        Assert.assertTrue(rewritten.contains("stepId = \"stock-check\""),
                "The chosen id stays at the call site");
        Assert.assertFalse(rewritten.contains("stepId = \"checkStock#1\""),
                "and no generated id is added alongside it");
        Assert.assertEquals(rewritten.split("stepId = ", -1).length - 1, 1,
                "Exactly one step id per call");

        Map<String, Object> chosen = descriptorOf("graph_step_id_chosen");
        Map<String, Object> graph = asObject(((Map<?, ?>) ((List<?>) chosen.get("workflows")).get(0)).get("graph"));
        Assert.assertEquals(nodeAt(graph, "stock-check").get("target"), "checkStock",
                "and the graph carries the same id, which is what makes the join work");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> graphOf(String workflowName) {
        for (Object workflow : (List<?>) descriptor.get("workflows")) {
            Map<?, ?> entry = (Map<?, ?>) workflow;
            if (workflowName.equals(entry.get("name"))) {
                Map<String, Object> graph = asObject(entry.get("graph"));
                Assert.assertNotNull(graph, "Workflow '" + workflowName + "' has no graph");
                return graph;
            }
        }
        throw new AssertionError("No workflow '" + workflowName + "' in the descriptor");
    }

    private static Map<String, Object> agentGraphOf(Map<String, Object> document, String agentName) {
        for (Object agent : (List<?>) document.get("agents")) {
            Map<?, ?> entry = (Map<?, ?>) agent;
            if (agentName.equals(entry.get("name"))) {
                return asObject(entry.get("graph"));
            }
        }
        throw new AssertionError("No agent '" + agentName + "' in the descriptor");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodesOf(Map<String, Object> graph) {
        return (List<Map<String, Object>>) (List<?>) graph.get("nodes");
    }

    private static Map<String, Object> nodeAt(Map<String, Object> graph, String stepId) {
        for (Map<String, Object> node : nodesOf(graph)) {
            if (stepId.equals(node.get("stepId"))) {
                return node;
            }
        }
        throw new AssertionError("No node '" + stepId + "' in " + graph.get("nodes"));
    }

    private static List<Map<String, Object>> nodesTargeting(Map<String, Object> graph, String target) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> node : nodesOf(graph)) {
            if (target.equals(node.get("target"))) {
                matches.add(node);
            }
        }
        return matches;
    }

    /** Whether the graph links {@code from} to {@code to}; {@code when} of null means any. */
    @SuppressWarnings("unchecked")
    private static boolean hasEdge(Map<String, Object> graph, String from, String to, String when) {
        for (Object raw : (List<Object>) graph.get("edges")) {
            Map<?, ?> edge = (Map<?, ?>) raw;
            if (from.equals(edge.get("from")) && to.equals(edge.get("to"))
                    && (when == null || when.equals(edge.get("when")))) {
                return true;
            }
        }
        return false;
    }

    private static String allSourcesOf(BuildProject project) {
        StringBuilder sources = new StringBuilder();
        for (ModuleId moduleId : project.currentPackage().moduleIds()) {
            Module module = project.currentPackage().module(moduleId);
            for (DocumentId documentId : module.documentIds()) {
                sources.append(module.document(documentId).syntaxTree().toSourceCode());
            }
        }
        return sources.toString();
    }

    private static BuildProject loadProject(String packageName) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(packageName);
        Assert.assertTrue(Files.isDirectory(projectDirPath), "Missing test package: " + projectDirPath);
        Environment environment = EnvironmentBuilder.getBuilder()
                .setBallerinaHome(DISTRIBUTION_PATH).build();
        return BuildProject.load(ProjectEnvironmentBuilder.getBuilder(environment), projectDirPath);
    }

    /**
     * Just enough JSON to read the canonical descriptor back: objects, arrays, strings, numbers,
     * booleans and null. The descriptor is written by hand too (see {@code DescriptorJson}), so
     * reading it with a hand-written parser keeps the tests free of a dependency the plugin
     * itself does not have.
     */
    static final class MiniJson {

        private final String text;
        private int at;

        private MiniJson(String text) {
            this.text = text;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String json) {
            MiniJson parser = new MiniJson(json);
            Object value = parser.value();
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("Not a JSON object: " + json);
            }
            return (Map<String, Object>) value;
        }

        private Object value() {
            skipSpace();
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipSpace();
            if (peek() == '}') {
                at++;
                return result;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                result.put(key, value());
                skipSpace();
                char c = text.charAt(at++);
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or } at " + at);
                }
            }
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipSpace();
            if (peek() == ']') {
                at++;
                return result;
            }
            while (true) {
                result.add(value());
                skipSpace();
                char c = text.charAt(at++);
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or ] at " + at);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> sb.append(escaped);
                }
            }
        }

        private Object number() {
            int start = at;
            while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String raw = text.substring(start, at);
            return raw.contains(".") || raw.contains("e") || raw.contains("E")
                    ? Double.valueOf(raw) : Long.valueOf(raw);
        }

        private Object literal(String word, Object result) {
            if (!text.startsWith(word, at)) {
                throw new IllegalArgumentException("Unexpected token at " + at);
            }
            at += word.length();
            return result;
        }

        private char peek() {
            return text.charAt(at);
        }

        private void expect(char c) {
            if (text.charAt(at++) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + (at - 1));
            }
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
