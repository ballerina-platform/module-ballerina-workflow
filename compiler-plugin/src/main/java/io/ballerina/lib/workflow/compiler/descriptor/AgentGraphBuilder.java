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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.EDGES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.FROM;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_AGENT_NODE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_EVENT;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_HUMAN_TASK;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_MODEL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.KIND_TOOL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.LABEL;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.NAME;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.NODES;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.SOURCE;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.STEP_ID;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TARGET;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.TO;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.WHEN;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.WHEN_IN;
import static io.ballerina.lib.workflow.compiler.descriptor.DescriptorFields.WHEN_OUT;

/**
 * Builds a durable agent's graph, in the same {@code nodes}/{@code edges} shape a workflow's
 * graph uses, so one renderer draws both.
 *
 * <p>An agent has no lexical control flow to describe — the model decides what to call and in
 * what order — so the graph is a star rather than a flow: the agent at the centre, the channels
 * that feed it (data events, human tasks) on the inbound side, and what it may invoke (tools,
 * the model) on the outbound side. {@code when} carries which side an edge is on, so a layout
 * can place inputs and outputs without interpreting node kinds.
 *
 * <p>Because there is no call site to name, an agent's step ids are derived from names
 * ({@code tool:checkStock}, {@code event:billSubmitted}) and an execution joins to them by target
 * name — the invocation history names the tool, which is the only identity the model's choice has.
 *
 * @since 0.9.0
 */
public final class AgentGraphBuilder {

    private static final String AGENT_ID = "agent";
    private static final String MODEL_ID = "model";
    private static final String TOOL_ID_PREFIX = "tool:";
    private static final String EVENT_ID_PREFIX = "event:";
    private static final String TASK_ID_PREFIX = "task:";

    private AgentGraphBuilder() {
    }

    /**
     * Builds the graph for one agent declaration.
     *
     * @param agentName  the agent's name
     * @param modelLabel the source reference of the configured model, or {@code null}
     * @param events     the agent's described data events
     * @param tools      the agent's described tools
     * @param humanTasks the agent's described human tasks
     * @return the {@code graph} object for the descriptor
     */
    public static Map<String, Object> build(String agentName, String modelLabel, List<Object> events,
                                            List<Object> tools, List<Object> humanTasks) {
        List<Object> nodes = new ArrayList<>();
        List<Object> edges = new ArrayList<>();

        nodes.add(node(AGENT_ID, KIND_AGENT_NODE, agentName, null, null));

        // Inbound: what reaches the agent from outside.
        for (Object event : events) {
            String name = nameOf(event);
            if (name != null) {
                nodes.add(node(EVENT_ID_PREFIX + name, KIND_EVENT, name, null, null));
                edges.add(edge(EVENT_ID_PREFIX + name, AGENT_ID, WHEN_IN));
            }
        }
        for (Object task : humanTasks) {
            String name = nameOf(task);
            if (name != null) {
                nodes.add(node(TASK_ID_PREFIX + name, KIND_HUMAN_TASK, name, null, null));
                edges.add(edge(TASK_ID_PREFIX + name, AGENT_ID, WHEN_IN));
            }
        }

        // Outbound: what the agent may invoke. Every durable agent has a model — the node
        // is unconditional; only its label depends on whether the configured expression
        // produced one (an inline construction may not).
        nodes.add(node(MODEL_ID, KIND_MODEL, null, modelLabel, null));
        edges.add(edge(AGENT_ID, MODEL_ID, WHEN_OUT));
        for (Object tool : tools) {
            String name = nameOf(tool);
            if (name != null) {
                nodes.add(node(TOOL_ID_PREFIX + name, KIND_TOOL, name, null, sourceOf(tool)));
                edges.add(edge(AGENT_ID, TOOL_ID_PREFIX + name, WHEN_OUT));
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put(NODES, nodes);
        graph.put(EDGES, edges);
        return graph;
    }

    private static Map<String, Object> node(String stepId, String kind, String target, String label,
                                            String source) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put(STEP_ID, stepId);
        node.put(KIND, kind);
        if (target != null) {
            node.put(TARGET, target);
        }
        if (label != null) {
            node.put(LABEL, label);
        }
        if (source != null) {
            // A tool's backing kind (ACTIVITY, AI_TOOL, PEER) changes how it is drawn.
            node.put(SOURCE, source);
        }
        return node;
    }

    private static Map<String, Object> edge(String from, String to, String when) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put(FROM, from);
        edge.put(TO, to);
        edge.put(WHEN, when);
        return edge;
    }

    private static String nameOf(Object entry) {
        return entry instanceof Map<?, ?> map && map.get(NAME) instanceof String name ? name : null;
    }

    private static String sourceOf(Object entry) {
        return entry instanceof Map<?, ?> map && map.get(SOURCE) instanceof String source ? source : null;
    }
}
