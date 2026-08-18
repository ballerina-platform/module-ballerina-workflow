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

/**
 * The vocabulary of the Workflow Definition Descriptor: every field name the document uses and
 * every JSON Schema keyword and type name the pinned dialect emits. These are the contract —
 * they are validated by {@code docs/spec/workflow-descriptor.schema.json}, read back by the
 * runtime, and compared byte for byte by the golden tests — so they are named here once rather
 * than repeated as literals at each use.
 *
 * <p>The runtime reads a subset of the same names; that side declares them in
 * {@code io.ballerina.lib.workflow.utils.DescriptorFields}. The two must agree, and the golden
 * tests plus the descriptor-registration tests fail if they drift apart.
 *
 * @since 0.9.0
 */
public final class DescriptorFields {

    // ── Document ──────────────────────────────────────────────────────────────
    /** Spec version of the document's shape. */
    public static final String DESCRIPTOR_VERSION = "descriptorVersion";
    /** The package the descriptor describes. */
    public static final String PACKAGE = "package";
    /** Organization of the described package. */
    public static final String ORG = "org";
    /** Content checksum over the canonical bytes. */
    public static final String CHECKSUM = "checksum";
    /** The described workflows. */
    public static final String WORKFLOWS = "workflows";
    /** The described durable agents. */
    public static final String AGENTS = "agents";

    // ── Shared ────────────────────────────────────────────────────────────────
    /** Name of a workflow, activity, task, event, agent, or tool. */
    public static final String NAME = "name";
    /** Version of a package or module. */
    public static final String VERSION = "version";
    /** WORKFLOW or AGENT. */
    public static final String KIND = "kind";
    /** The implementation binding: the module-level function the runtime resolves. */
    public static final String FUNCTION = "function";
    /** Qualified module name of a function binding. */
    public static final String MODULE = "module";

    // ── Workflow structure ────────────────────────────────────────────────────
    /** A workflow's, agent's, activity's, or tool's input slot. */
    public static final String INPUT = "input";
    /** An activity's return slot. */
    public static final String OUTPUT = "output";
    /** A human task's or agent's result slot. */
    public static final String RESULT = "result";
    /** Data events a workflow or agent waits on. */
    public static final String EVENTS = "events";
    /** Activities a workflow calls. */
    public static final String ACTIVITIES = "activities";
    /** Human tasks a workflow or agent awaits. */
    public static final String HUMAN_TASKS = "humanTasks";
    /** Which activities are human-reviewed, and when. */
    public static final String REVIEW_ACTIVITIES = "reviewActivities";
    /** The reviewed activity's name. */
    public static final String ACTIVITY = "activity";
    /** What starts a review: PRE_RUN or ON_FAILURE. */
    public static final String TRIGGERS = "triggers";
    /** Direction of a data event. */
    public static final String DIRECTION = "direction";
    /** Whether an event is consumed once or re-armed. */
    public static final String CARDINALITY = "cardinality";
    /** An event's payload slot. */
    public static final String PAYLOAD = "payload";
    /** An agent event's request slot. */
    public static final String REQUEST = "request";
    /** An agent event's response slot. */
    public static final String RESPONSE = "response";
    /** Capabilities advertised to an agent's model. */
    public static final String TOOLS = "tools";
    /** Where a tool comes from: ACTIVITY, AI_TOOL, or PEER. */
    public static final String SOURCE = "source";

    // ── Execution graph ───────────────────────────────────────────────────────
    /** A workflow's or agent's structure graph: what it does, and in what order. */
    public static final String GRAPH = "graph";
    /** The graph's steps, in source order. */
    public static final String NODES = "nodes";
    /** Directed edges between steps. */
    public static final String EDGES = "edges";
    /**
     * The stable per-workflow identity of one step. Chosen at the call site with
     * {@code stepId = "..."}, or generated as `<target>#<ordinal>` when the call names none.
     */
    public static final String STEP_ID = "stepId";
    /** What a step calls: an activity, task, child workflow, event, or tool name. */
    public static final String TARGET = "target";
    /** Display text for a step — a condition, a matched expression, a model reference. */
    public static final String LABEL = "label";
    /** The enclosing control-flow step's site, for a nested step. */
    public static final String PARENT = "parent";
    /** Which arm of the enclosing step a nested step belongs to. */
    public static final String BRANCH = "branch";
    /** Source file the graph was derived from. */
    public static final String FILE = "file";
    /** One-based source line of a step. */
    public static final String LINE = "line";
    /** One-based source column of a step. */
    public static final String COLUMN = "column";
    /** Edge origin site. */
    public static final String FROM = "from";
    /** Edge destination site. */
    public static final String TO = "to";
    /** What makes an edge the one taken: a branch arm, a loop entry, or a repeat. */
    public static final String WHEN = "when";

    // ── Graph node kinds ──────────────────────────────────────────────────────
    /** A step that calls an activity. */
    public static final String KIND_ACTIVITY = "ACTIVITY";
    /** A step that awaits a human task. */
    public static final String KIND_HUMAN_TASK = "HUMAN_TASK";
    /** A step that starts or calls a child workflow. */
    public static final String KIND_CHILD_WORKFLOW = "CHILD_WORKFLOW";
    /** A step that blocks on one or more data events. */
    public static final String KIND_EVENT_WAIT = "EVENT_WAIT";
    /** A durable sleep. */
    public static final String KIND_SLEEP = "SLEEP";
    /** A conditional: `if`/`else` or `match`. */
    public static final String KIND_BRANCH = "BRANCH";
    /** A loop: `while` or `foreach`. */
    public static final String KIND_LOOP = "LOOP";
    /** A `do`/`on fail` block. */
    public static final String KIND_TRY = "TRY";
    /** The agent itself, at the centre of an agent's graph. */
    public static final String KIND_AGENT_NODE = "AGENT";
    /** The model an agent reasons with. */
    public static final String KIND_MODEL = "MODEL";
    /** A capability an agent may invoke. */
    public static final String KIND_TOOL = "TOOL";
    /** A data event channel an agent receives on. */
    public static final String KIND_EVENT = "EVENT";

    // ── Edge conditions ───────────────────────────────────────────────────────
    /** The arm taken when a condition holds. */
    public static final String WHEN_THEN = "then";
    /** The arm taken when it does not. */
    public static final String WHEN_ELSE = "else";
    /** Entry into a loop body. */
    public static final String WHEN_BODY = "body";
    /** The loop body's return to the loop. */
    public static final String WHEN_REPEAT = "repeat";
    /** The guarded block of a `do`/`on fail`. */
    public static final String WHEN_DO = "do";
    /** Its failure handler. */
    public static final String WHEN_ON_FAIL = "onFail";
    /** An agent's inbound edge: an event or task feeding the agent. */
    public static final String WHEN_IN = "in";
    /** An agent's outbound edge: a tool or model the agent invokes. */
    public static final String WHEN_OUT = "out";

    // ── Typed slots ───────────────────────────────────────────────────────────
    /** The resolved Ballerina type descriptor — always present in a slot. */
    public static final String TYPE = "type";
    /** The derived JSON Schema, when the type can be rendered as one. */
    public static final String SCHEMA = "schema";
    /** Marks a schema that only approximates its type. */
    public static final String LOSSY = "lossy";

    // ── JSON Schema keywords (the pinned dialect) ─────────────────────────────
    /** Object property schemas. */
    public static final String SCHEMA_PROPERTIES = "properties";
    /** Names of required properties. */
    public static final String SCHEMA_REQUIRED = "required";
    /** Array element schema. */
    public static final String SCHEMA_ITEMS = "items";
    /** Schema for properties beyond those named. */
    public static final String SCHEMA_ADDITIONAL_PROPERTIES = "additionalProperties";
    /** Alternatives of a union. */
    public static final String SCHEMA_ANY_OF = "anyOf";

    // ── JSON Schema type names ────────────────────────────────────────────────
    /** JSON object. */
    public static final String JSON_OBJECT = "object";
    /** JSON array. */
    public static final String JSON_ARRAY = "array";
    /** JSON string. */
    public static final String JSON_STRING = "string";
    /** JSON integer. */
    public static final String JSON_INTEGER = "integer";
    /** JSON number. */
    public static final String JSON_NUMBER = "number";
    /** JSON boolean. */
    public static final String JSON_BOOLEAN = "boolean";
    /** JSON null. */
    public static final String JSON_NULL = "null";

    // ── Enumerated values ─────────────────────────────────────────────────────
    /** `kind` of a `@workflow:Workflow` function. */
    public static final String KIND_WORKFLOW = "WORKFLOW";
    /** `kind` of a durable agent. */
    public static final String KIND_AGENT = "AGENT";
    /** A data event the workflow receives. */
    public static final String DIRECTION_IN = "IN";
    /** An event consumed once. */
    public static final String CARDINALITY_SINGLE = "SINGLE";
    /** An event re-armed per turn. */
    public static final String CARDINALITY_MULTI = "MULTI";
    /** A tool backed by a `@workflow:Activity` function. */
    public static final String SOURCE_ACTIVITY = "ACTIVITY";
    /** A tool backed by an AI tool or toolkit. */
    public static final String SOURCE_AI_TOOL = "AI_TOOL";
    /** A tool backed by a peer agent. */
    public static final String SOURCE_PEER = "PEER";
    /** A review that runs before the activity. */
    public static final String TRIGGER_PRE_RUN = "PRE_RUN";
    /** A review that runs when the activity fails. */
    public static final String TRIGGER_ON_FAILURE = "ON_FAILURE";

    // ── Ballerina type names used in slots ────────────────────────────────────
    /** The Ballerina nil type, as written in a slot's type field. */
    public static final String BAL_NIL = "()";
    /** The Ballerina anydata type. */
    public static final String BAL_ANYDATA = "anydata";
    /** The Ballerina string type. */
    public static final String BAL_STRING = "string";

    private DescriptorFields() {
    }
}
