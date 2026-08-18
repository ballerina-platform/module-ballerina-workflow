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

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.utils.DescriptorFields;
import io.ballerina.lib.workflow.utils.MetadataFields;
import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.FunctionType;
import io.ballerina.runtime.api.types.MapType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.TypeTags;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the workflow-metadata document published to control planes (e.g. the ICP runtime
 * bridge): the registered workflow definitions with input schemas, human tasks with their
 * completion-form schemas, activities with the input schemas that back review
 * "proceed-with-input" forms, the static review-action vocabulary, and durable-agent
 * declarations. Everything is read from the module-init-time registries, so the document is
 * complete at startup — before any workflow has executed — provided the compiler plugin
 * registered human-task result types (see {@code registerHumanTask}).
 *
 * <p>Schemas are JSON Schema documents serialized as strings, matching the convention of
 * {@code WorkflowDefinition.inputSchema} and {@code HumanTaskInfo.formSchema}.
 */
public final class WorkflowMetadataNative {

    private static final String METADATA_VERSION = "1.0";
    private static final MapType JSON_MAP_TYPE = TypeCreator.createMapType(PredefinedTypes.TYPE_JSON);
    private static final ArrayType JSON_ARRAY_TYPE = TypeCreator.createArrayType(PredefinedTypes.TYPE_JSON);

    private WorkflowMetadataNative() {
    }

    /**
     * Returns the workflow metadata document as a {@code json} value:
     * {@code {metadataVersion, definitions, humanTasks, activities, reviewActions, agents}}.
     *
     * @return the metadata as a Ballerina json value, or a Ballerina error on failure
     */
    public static Object getWorkflowMetadata() {
        try {
            BMap<BString, Object> root = ValueCreator.createMapValue(JSON_MAP_TYPE);
            root.put(MetadataFields.METADATA_VERSION, StringUtils.fromString(METADATA_VERSION));
            root.put(MetadataFields.DEFINITIONS, buildDefinitions());
            root.put(MetadataFields.HUMAN_TASKS, buildHumanTasks());
            root.put(MetadataFields.ACTIVITIES, buildActivities());
            root.put(MetadataFields.REVIEW_ACTIONS, buildReviewActions());
            root.put(MetadataFields.AGENTS, buildAgents());
            // The build-time Workflow Definition Descriptor (workflow.def.json), when packed:
            // the canonical, versioned, checksummed description of the same structures. Null
            // when the program was built without one (older plugin, or no executable JAR).
            root.put(MetadataFields.DESCRIPTOR, WorkflowDescriptorNative.readPackedDescriptor());
            return root;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Failed to build workflow metadata: " + e.getMessage()));
        }
    }

    private static BArray buildDefinitions() {
        BArray definitions = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        Map<String, io.ballerina.lib.workflow.worker.WorkflowFunctionRef> processRegistry =
                new TreeMap<>(WorkflowWorkerNative.getProcessRegistry());
        for (Map.Entry<String, io.ballerina.lib.workflow.worker.WorkflowFunctionRef> entry
                : processRegistry.entrySet()) {
            String displayType = stripPrefix(entry.getKey(), WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX);
            BMap<BString, Object> def = ValueCreator.createMapValue(JSON_MAP_TYPE);
            def.put(MetadataFields.WORKFLOW_TYPE, StringUtils.fromString(displayType));
            def.put(MetadataFields.KIND, DurableAgentNative.getAgentDecl(displayType) != null
                    ? MetadataFields.KIND_AGENT : MetadataFields.KIND_WORKFLOW);
            String inputSchema = ManagementNative.deriveWorkflowInputSchema(entry.getValue());
            def.put(MetadataFields.INPUT_SCHEMA,
                    inputSchema != null ? StringUtils.fromString(inputSchema) : null);
            definitions.append(def);
        }
        return definitions;
    }

    private static BArray buildHumanTasks() {
        BArray humanTasks = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        for (String registered : new TreeSet<>(WorkflowWorkerNative.getHumanTaskRegistry())) {
            String displayName = stripPrefix(registered, WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX);
            String typeKey = registered.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX)
                    ? registered : WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX + registered;
            Type resultType = WorkflowWorkerNative.getHumanTaskResultType(typeKey);
            humanTasks.append(buildHumanTaskEntry(displayName, resultType));
        }
        return humanTasks;
    }

    private static BMap<BString, Object> buildHumanTaskEntry(String displayName, Type resultType) {
        BMap<BString, Object> task = ValueCreator.createMapValue(JSON_MAP_TYPE);
        task.put(MetadataFields.NAME, StringUtils.fromString(displayName));
        // The registry knows the result type once the task has executed (lazy registration in
        // awaitHumanTask); before that, the completion-form schema comes from the packed
        // workflow descriptor, which the compiler plugin generated at build time.
        String resultSchema = resultType != null ? TypesUtil.toJsonSchema(resultType)
                : descriptorHumanTaskSchema(displayName);
        task.put(MetadataFields.RESULT_SCHEMA,
                resultSchema != null ? StringUtils.fromString(resultSchema) : null);
        return task;
    }

    /**
     * Looks up a human task's completion-form schema in the packed workflow descriptor
     * ({@code workflow.def.json}). The descriptor nests tasks under their workflow with short
     * names; the registry's display name is the qualified {@code <workflow>.<task>}.
     *
     * @param displayName the qualified task name
     * @return the schema serialized as a JSON string, or {@code null} when no descriptor is
     *         packed, the task is not described, or its result slot carries no schema
     */
    private static String descriptorHumanTaskSchema(String displayName) {
        int separator = displayName.indexOf('.');
        if (separator <= 0) {
            return null;
        }
        String workflowName = displayName.substring(0, separator);
        String taskName = displayName.substring(separator + 1);
        Object descriptor = WorkflowDescriptorNative.readPackedDescriptor();
        if (!(descriptor instanceof BMap<?, ?> document)) {
            return null;
        }
        Object workflows = document.get(DescriptorFields.WORKFLOWS);
        if (!(workflows instanceof BArray workflowArray)) {
            return null;
        }
        for (long i = 0; i < workflowArray.getLength(); i++) {
            if (!(workflowArray.get(i) instanceof BMap<?, ?> workflow)
                    || !nameEquals(workflow, workflowName)) {
                continue;
            }
            Object tasks = workflow.get(DescriptorFields.HUMAN_TASKS);
            if (!(tasks instanceof BArray taskArray)) {
                return null;
            }
            for (long j = 0; j < taskArray.getLength(); j++) {
                if (taskArray.get(j) instanceof BMap<?, ?> task && nameEquals(task, taskName)) {
                    Object result = task.get(DescriptorFields.RESULT);
                    if (result instanceof BMap<?, ?> slot) {
                        Object schema = slot.get(DescriptorFields.SCHEMA);
                        return schema != null ? StringUtils.getJsonString(schema) : null;
                    }
                    return null;
                }
            }
            return null;
        }
        return null;
    }

    private static boolean nameEquals(BMap<?, ?> entry, String expected) {
        Object name = entry.get(DescriptorFields.NAME);
        return name instanceof BString bName && bName.getValue().equals(expected);
    }

    /**
     * One entry per (workflow, activity) pair, as consumers expect. The registry is keyed by the
     * plain activity name, so which workflows declare an activity comes from the ownership map
     * rather than from splitting a qualified key.
     */
    private static BArray buildActivities() {
        BArray activities = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        Map<String, io.ballerina.lib.workflow.worker.WorkflowFunctionRef> activityRegistry =
                WorkflowWorkerNative.getActivityRegistry();
        Map<String, java.util.Set<String>> owners =
                new TreeMap<>(WorkflowWorkerNative.getActivityOwners());
        for (Map.Entry<String, java.util.Set<String>> entry : owners.entrySet()) {
            String activityName = entry.getKey();
            io.ballerina.lib.workflow.worker.WorkflowFunctionRef ref = activityRegistry.get(activityName);
            String inputSchema = ref != null ? deriveActivityInputSchema(ref) : null;
            for (String owner : new TreeSet<>(entry.getValue())) {
                BMap<BString, Object> activity = ValueCreator.createMapValue(JSON_MAP_TYPE);
                activity.put(MetadataFields.WORKFLOW_TYPE, StringUtils.fromString(
                        stripPrefix(owner, WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX)));
                activity.put(MetadataFields.NAME, StringUtils.fromString(activityName));
                activity.put(MetadataFields.INPUT_SCHEMA,
                        inputSchema != null ? StringUtils.fromString(inputSchema) : null);
                activities.append(activity);
            }
        }
        return activities;
    }

    /**
     * Builds the JSON Schema for an activity's data parameters — the same shape a review
     * activity's {@code proceed-with-input} form uses: one property per data parameter,
     * skipping typedescs and client objects, honoring parameter defaults.
     */
    private static String deriveActivityInputSchema(io.ballerina.lib.workflow.worker.WorkflowFunctionRef fn) {
        if (fn == null || !(fn.getType() instanceof FunctionType funcType)) {
            return null;
        }
        Parameter[] allParams = funcType.getParameters();
        List<Parameter> dataParams = new ArrayList<>();
        if (allParams != null) {
            for (Parameter p : allParams) {
                if (p.type.getTag() == TypeTags.TYPEDESC_TAG || WorkflowWorkerNative.isObjectParam(p)) {
                    continue;
                }
                dataParams.add(p);
            }
        }
        Parameter[] params = dataParams.toArray(new Parameter[0]);
        return TypesUtil.toJsonSchemaForParameters(params, 0, params.length, true);
    }

    private static BArray buildReviewActions() {
        BArray actions = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        actions.append(MetadataFields.ACTION_PROCEED);
        actions.append(MetadataFields.ACTION_PROCEED_WITH_INPUT);
        actions.append(MetadataFields.ACTION_REJECT);
        return actions;
    }

    private static BArray buildAgents() {
        BArray agents = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        Map<String, DurableAgentNative.AgentDecl> declRegistry =
                new TreeMap<>(DurableAgentNative.getAgentDeclRegistry());
        for (DurableAgentNative.AgentDecl decl : declRegistry.values()) {
            BMap<BString, Object> agent = ValueCreator.createMapValue(JSON_MAP_TYPE);
            agent.put(MetadataFields.NAME, StringUtils.fromString(decl.agentName()));
            agent.put(MetadataFields.EVENTS, toJsonStringArray(decl.events().keySet()));
            List<String> toolNames = new ArrayList<>(decl.activities().keySet());
            toolNames.addAll(decl.tools().keySet());
            agent.put(MetadataFields.TOOLS, toJsonStringArray(toolNames));
            List<String> taskNames = new ArrayList<>();
            for (String taskName : decl.humanTasks().keySet()) {
                taskNames.add(decl.agentName() + "." + taskName);
            }
            agent.put(MetadataFields.HUMAN_TASKS, toJsonStringArray(taskNames));
            agents.append(agent);
        }
        return agents;
    }

    private static BArray toJsonStringArray(Iterable<String> values) {
        BArray array = ValueCreator.createArrayValue(JSON_ARRAY_TYPE);
        for (String value : values) {
            array.append(StringUtils.fromString(value));
        }
        return array;
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
