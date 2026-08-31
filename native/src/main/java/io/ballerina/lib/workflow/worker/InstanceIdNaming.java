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
 * Decides the instance ids an execution issues for the children it starts — human tasks,
 * review activities, child workflows, and child agents — and keeps that decision stable for
 * the life of the execution.
 *
 * <p>Child ids used to carry a classifying prefix ({@code humantask-}, {@code reviewactivity-},
 * {@code childwf-}, {@code childagent-}). The kind now travels in the memo and the Temporal
 * type name, so new ids drop the prefix.
 *
 * <p>The shortening cannot simply be applied, because a child's workflow id participates in
 * replay validation: the SDK compares the id recorded in {@code
 * StartChildWorkflowExecutionInitiated} history events against the id the replaying code
 * issues, so an execution parked on a human task started before the change would fail to
 * replay with a bare id. {@link Workflow#getVersion} settles it per execution, exactly as
 * {@link ActivityNaming} does for activity types — an execution whose history has no marker
 * predates the change and keeps issuing the legacy prefixed ids, while a new execution
 * records the marker once and issues bare ids.
 *
 * <p>The patch can be removed once no execution older than it can still be replayed.
 *
 * @since 1.0.0
 */
public final class InstanceIdNaming {

    /** Marker change id. Changing this string re-opens the migration; don't. */
    public static final String BARE_INSTANCE_IDS_CHANGE_ID = "workflow-bare-instance-ids";

    /** The patched version: issue child instance ids without a classifying prefix. */
    public static final int BARE = 1;

    private InstanceIdNaming() {
    }

    /**
     * The instance id this execution must issue for a new child. Must be called from a
     * workflow thread.
     *
     * @param legacyPrefix the prefix ids of this kind carried before the change
     * @param bareId       the id in its new, unprefixed form
     * @return {@code bareId} for executions started since the change, {@code legacyPrefix +
     *         bareId} for executions whose history predates it
     */
    public static String childInstanceId(String legacyPrefix, String bareId) {
        return isBare() ? bareId : legacyPrefix + bareId;
    }

    /** Whether this execution was started since the id patch. */
    private static boolean isBare() {
        return Workflow.getVersion(BARE_INSTANCE_IDS_CHANGE_ID, Workflow.DEFAULT_VERSION, BARE)
                != Workflow.DEFAULT_VERSION;
    }
}
