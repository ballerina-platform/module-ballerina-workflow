/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.util.HashMap;
import java.util.Map;

/**
 * The display names the packed workflow descriptor carries — what a {@code @display} annotation
 * said about a workflow, activity, agent or human task. A display name is never an identity: it
 * is joined onto a definition or a history node at read time by the name the engine knows, so a
 * rename is a rebuild and nothing else. Indexed once per descriptor document.
 *
 * @since 0.11.0
 */
public final class DisplayNames {

    /**
     * A declaration's display fields.
     *
     * @param label the {@code @display} label, or null
     * @param icon  the {@code @display} icon path, or null
     */
    public record Display(String label, String icon) {
        static final Display NONE = new Display(null, null);
    }

    private static final Object LOCK = new Object();
    private static Object indexedDocument;
    private static Map<String, Display> workflows = Map.of();
    private static Map<String, Display> activities = Map.of();
    private static Map<String, String> humanTaskTitles = Map.of();

    private DisplayNames() {
    }

    /**
     * The display of a workflow or agent definition.
     *
     * @param workflowType the Temporal type or the bare definition name
     * @return the display, never null
     */
    public static Display ofWorkflow(String workflowType) {
        ensureIndexed();
        return workflows.getOrDefault(stripPrefix(workflowType), Display.NONE);
    }

    /**
     * The display of an activity. Two workflows may declare the same activity name with different labels,
     * so a {@code <workflow>.<name>} key answers for its owner and the bare name is the fallback.
     *
     * @param activityType {@code <workflow>.<name>}, the plain name, or the legacy {@code <workflowType>.<name>}
     * @return the display, never null
     */
    public static Display ofActivity(String activityType) {
        ensureIndexed();
        Display display = activities.get(activityType);
        if (display == null) {
            display = activities.get(stripPrefix(activityType));
        }
        if (display == null) {
            int dot = activityType.lastIndexOf('.');
            display = dot > 0 ? activities.get(activityType.substring(dot + 1)) : null;
        }
        return display != null ? display : Display.NONE;
    }

    /**
     * The constant title of a human task, when the descriptor recorded one.
     *
     * @param qualifiedTaskName {@code <workflowOrAgent>.<task>}
     * @return the title, or null
     */
    public static String humanTaskTitle(String qualifiedTaskName) {
        ensureIndexed();
        return humanTaskTitles.get(qualifiedTaskName);
    }

    /** The display label of a workflow or agent definition, or null. */
    public static String workflowLabel(String workflowType) {
        return ofWorkflow(workflowType).label();
    }

    private static String stripPrefix(String workflowType) {
        return workflowType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX)
                ? workflowType.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) : workflowType;
    }

    // Re-indexes whenever the packed document changes: the module's own tests swap it in and out.
    private static void ensureIndexed() {
        Object document = WorkflowDescriptorNative.readPackedDescriptor();
        synchronized (LOCK) {
            if (document == indexedDocument) {
                return;
            }
            Map<String, Display> wf = new HashMap<>();
            Map<String, Display> act = new HashMap<>();
            Map<String, String> titles = new HashMap<>();
            if (document instanceof BMap<?, ?> root) {
                for (BMap<?, ?> workflow : entriesOf(root.get(DescriptorFields.WORKFLOWS))) {
                    String name = stringOf(workflow, DescriptorFields.NAME);
                    if (name == null) {
                        continue;
                    }
                    wf.put(name, displayOf(workflow));
                    for (BMap<?, ?> activity : entriesOf(workflow.get(DescriptorFields.ACTIVITIES))) {
                        String activityName = stringOf(activity, DescriptorFields.NAME);
                        if (activityName != null) {
                            Display display = displayOf(activity);
                            act.put(name + "." + activityName, display);
                            act.putIfAbsent(activityName, display);
                        }
                    }
                    indexTitles(name, workflow, titles);
                }
                for (BMap<?, ?> agent : entriesOf(root.get(DescriptorFields.AGENTS))) {
                    String name = stringOf(agent, DescriptorFields.NAME);
                    if (name == null) {
                        continue;
                    }
                    wf.put(name, displayOf(agent));
                    indexTitles(name, agent, titles);
                }
            }
            workflows = wf;
            activities = act;
            humanTaskTitles = titles;
            indexedDocument = document;
        }
    }

    private static void indexTitles(String owner, BMap<?, ?> entry, Map<String, String> titles) {
        for (BMap<?, ?> task : entriesOf(entry.get(DescriptorFields.HUMAN_TASKS))) {
            String taskName = stringOf(task, DescriptorFields.NAME);
            String title = stringOf(task, DescriptorFields.TITLE);
            if (taskName != null && title != null) {
                titles.put(owner + "." + taskName, title);
            }
        }
    }

    private static Display displayOf(BMap<?, ?> entry) {
        String label = stringOf(entry, DescriptorFields.DISPLAY_NAME);
        String icon = stringOf(entry, DescriptorFields.ICON);
        return label == null && icon == null ? Display.NONE : new Display(label, icon);
    }

    private static Iterable<BMap<?, ?>> entriesOf(Object array) {
        java.util.List<BMap<?, ?>> entries = new java.util.ArrayList<>();
        if (array instanceof BArray values) {
            for (long i = 0; i < values.getLength(); i++) {
                if (values.get(i) instanceof BMap<?, ?> entry) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static String stringOf(BMap<?, ?> entry, BString key) {
        return entry.get(key) instanceof BString value && !value.getValue().isBlank() ? value.getValue() : null;
    }
}
