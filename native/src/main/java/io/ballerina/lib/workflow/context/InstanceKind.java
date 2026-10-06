/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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

package io.ballerina.lib.workflow.context;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;

// The kinds of instance the module starts, and the one rule for reading an instance's kind back.
public final class InstanceKind {

    public static final String WORKFLOW = "WORKFLOW";
    public static final String AGENT = "AGENT";
    public static final String CHILD_WORKFLOW = "CHILD_WORKFLOW";
    public static final String HUMAN_TASK = TaskRecord.HUMAN_TASK;
    public static final String REVIEW_ACTIVITY = TaskRecord.REVIEW_ACTIVITY;
    // Pre-0.7.0 review activities were stamped with this kind.
    public static final String LEGACY_RETRY_TASK = "RETRY_TASK";

    private InstanceKind() {
    }

    // The memo kind when present, else the type and then the id prefix; null arguments are skipped.
    public static String of(String memoKind, String workflowId, String workflowType) {
        if (memoKind != null && !memoKind.isBlank()) {
            return LEGACY_RETRY_TASK.equals(memoKind) ? REVIEW_ACTIVITY : memoKind;
        }
        if (workflowType != null) {
            if (workflowType.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX)) {
                return HUMAN_TASK;
            }
            if (isReviewActivityType(workflowType)) {
                return REVIEW_ACTIVITY;
            }
        }
        if (workflowId != null) {
            if (workflowId.startsWith(WorkflowWorkerNative.HUMANTASK_TYPE_PREFIX)) {
                return HUMAN_TASK;
            }
            if (workflowId.startsWith(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX)) {
                return REVIEW_ACTIVITY;
            }
            if (workflowId.startsWith(WorkflowWorkerNative.CHILD_WORKFLOW_ID_PREFIX)) {
                return CHILD_WORKFLOW;
            }
        }
        return WORKFLOW;
    }

    public static boolean isReviewActivityType(String workflowType) {
        return workflowType.startsWith(WorkflowWorkerNative.REVIEW_ACTIVITY_TYPE_PREFIX)
                || WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE.equals(workflowType)
                || workflowType.startsWith(WorkflowWorkerNative.LEGACY_RETRYTASK_WORKFLOW_TYPE + "-");
    }
}
