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

package io.ballerina.lib.workflow.utils;

import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BString;

/**
 * The field names and enumerated values of the workflow-metadata document that
 * {@code management:getWorkflowMetadata()} returns. Each name must match a field of the
 * corresponding record in {@code modules/management/metadata.bal} — {@code WorkflowMetadata},
 * {@code WorkflowDefinitionMeta}, {@code HumanTaskMeta}, {@code ActivityMeta}, and
 * {@code AgentMeta} — because the Ballerina side converts the document with
 * {@code cloneWithType}: a name that does not match makes the conversion fail rather than
 * quietly omit a field, so they are declared once here instead of repeated as literals.
 *
 * <p>This is the *metadata* vocabulary, distinct from the descriptor's own field names in
 * {@link DescriptorFields}, even where a name coincides.
 *
 * @since 0.9.0
 */
public final class MetadataFields {

    // ── Document ──────────────────────────────────────────────────────────────
    /** Version of the metadata document's shape. */
    public static final BString METADATA_VERSION = StringUtils.fromString("metadataVersion");
    /** Registered workflow definitions. */
    public static final BString DEFINITIONS = StringUtils.fromString("definitions");
    /** Human task types with their completion-form schemas. */
    public static final BString HUMAN_TASKS = StringUtils.fromString("humanTasks");
    /** Registered activities with their input schemas. */
    public static final BString ACTIVITIES = StringUtils.fromString("activities");
    /** The static review-activity decision vocabulary. */
    public static final BString REVIEW_ACTIONS = StringUtils.fromString("reviewActions");
    /** Declared durable agents. */
    public static final BString AGENTS = StringUtils.fromString("agents");
    /** The build-time workflow descriptor, when one is packed. */
    public static final BString DESCRIPTOR = StringUtils.fromString("descriptor");

    // ── Entries ───────────────────────────────────────────────────────────────
    /** Registered workflow (or agent) type name. */
    public static final BString WORKFLOW_TYPE = StringUtils.fromString("workflowType");
    /** WORKFLOW or AGENT. */
    public static final BString KIND = StringUtils.fromString("kind");
    /** JSON Schema of a workflow's input, serialized as a string. */
    public static final BString INPUT_SCHEMA = StringUtils.fromString("inputSchema");
    /** Name of a human task, activity, or agent. */
    public static final BString NAME = StringUtils.fromString("name");
    /** JSON Schema of a human task's completion form, serialized as a string. */
    public static final BString RESULT_SCHEMA = StringUtils.fromString("resultSchema");
    /** An agent's declared event channel names. */
    public static final BString EVENTS = StringUtils.fromString("events");
    /** Tool names an agent advertises to its model. */
    public static final BString TOOLS = StringUtils.fromString("tools");

    // ── Values ────────────────────────────────────────────────────────────────
    /** `kind` of a `@workflow:Workflow` function. */
    public static final BString KIND_WORKFLOW = StringUtils.fromString("WORKFLOW");
    /** `kind` of a durable agent. */
    public static final BString KIND_AGENT = StringUtils.fromString("AGENT");

    /** Review decision: run the activity again as it was called. */
    public static final BString ACTION_PROCEED = StringUtils.fromString("proceed");
    /** Review decision: run the activity again with reviewer-supplied input. */
    public static final BString ACTION_PROCEED_WITH_INPUT = StringUtils.fromString("proceed-with-input");
    /** Review decision: fail the activity permanently. */
    public static final BString ACTION_REJECT = StringUtils.fromString("reject");

    private MetadataFields() {
    }
}
