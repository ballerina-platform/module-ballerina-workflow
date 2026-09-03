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

package io.ballerina.lib.workflow.worker;

import io.temporal.workflow.Workflow;

/**
 * Decides the Temporal names an execution uses for its activities, and keeps that decision
 * stable for the life of the execution.
 *
 * <p>Activities are scheduled under their plain Ballerina name. They used to be scheduled under
 * {@code <workflowType>.<activity>}, which added nothing — function names are already unique
 * within a package — while making one registry entry per (workflow, activity) pair and a longer
 * type name in every history.
 *
 * <p>The shortening cannot simply be applied, because an activity's type participates in replay
 * validation: the SDK compares the type recorded in history against the type the replaying code
 * schedules, so an execution started before the change would fail to replay. {@link
 * Workflow#getVersion} settles it per execution — an execution whose history has no marker
 * predates the change and keeps the legacy qualified names (which stay registered), while a new
 * execution records the marker once and uses the plain names.
 *
 * <p>The patch can be removed once no execution older than it can still be replayed; until then
 * both names resolve, and only which one is <em>scheduled</em> depends on the marker.
 *
 * @since 0.9.0
 */
public final class ActivityNaming {

    /** Marker change id. Changing this string re-opens the migration; don't. */
    public static final String LEGACY_ACTIVITY_NAMING_CHANGE_ID = "workflow-activity-name-unqualified";

    /** The patched version: schedule activities under their plain name. */
    public static final int UNQUALIFIED = 1;

    private ActivityNaming() {
    }

    /**
     * The Temporal activity type this execution must schedule {@code activityName} under. Must be
     * called from a workflow thread.
     *
     * @param workflowType the calling workflow's Temporal type
     * @param activityName the activity's plain name
     * @return the activity type to schedule
     */
    public static String activityTypeFor(String workflowType, String activityName) {
        return isUnqualified() ? activityName : workflowType + "." + activityName;
    }

    /**
     * The qualified name a review task for {@code activityName} is listed under, and the child
     * workflow type derived from it. Gated on the same marker as {@link #activityTypeFor}, so an
     * execution's review types stay exactly as its history recorded them; a new execution drops
     * the internal {@code workflow-} prefix, matching how human tasks are already qualified.
     *
     * @param workflowType the calling workflow's Temporal type
     * @param activityName the activity's plain name
     * @return the qualified review task name
     */
    public static String reviewTaskNameFor(String workflowType, String activityName) {
        if (!isUnqualified()) {
            return workflowType + "." + activityName;
        }
        String definitionName = workflowType.startsWith(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX)
                ? workflowType.substring(WorkflowWorkerNative.WORKFLOW_TYPE_PREFIX.length()) : workflowType;
        return definitionName + "." + activityName;
    }

    /** Whether this execution was started since the naming patch. */
    private static boolean isUnqualified() {
        return Workflow.getVersion(LEGACY_ACTIVITY_NAMING_CHANGE_ID, Workflow.DEFAULT_VERSION, UNQUALIFIED)
                != Workflow.DEFAULT_VERSION;
    }
}
