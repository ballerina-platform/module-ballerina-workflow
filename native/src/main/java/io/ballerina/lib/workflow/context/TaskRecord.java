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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.context;

import io.ballerina.lib.workflow.TaskKeys;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One task as the runtime records it — the memo a listing reads and the inputs its child workflow runs with.
 * Human tasks and review activities both build this, so the two never drift apart.
 *
 * @param kind               {@link #HUMAN_TASK} or {@link #REVIEW_ACTIVITY}
 * @param taskId             the child workflow id
 * @param taskName           the qualified task name
 * @param parentWorkflowId   the creating workflow's id
 * @param parentWorkflowType the creating workflow's user-facing type
 * @param stepId             the creating call site, or null
 * @param title              inbox summary
 * @param description        context shown with the form or decision
 * @param userRoles          roles that may act
 * @param users              user ids that may act
 * @param excludedUsers      user ids that may not act
 * @param excludedRoles      roles that may not act
 * @param taskInput          what the decider is shown
 * @param formSchema         JSON schema of the answer, or null
 * @param timeoutMillis      deadline, or null to wait indefinitely
 * @param createdAt          creation instant on the workflow clock
 * @param trigger            review only: PRE_RUN or ON_FAILURE
 * @param activityName       review only: the reviewed activity
 * @param errorMessage       review only: the failure under review, empty for PRE_RUN
 */
public record TaskRecord(String kind, String taskId, String taskName, String parentWorkflowId,
                         String parentWorkflowType, String stepId, String title, String description,
                         List<String> userRoles, List<String> users, List<String> excludedUsers,
                         List<String> excludedRoles, Object taskInput, String formSchema, Long timeoutMillis,
                         String createdAt, String trigger, String activityName, String errorMessage) {

    public static final String HUMAN_TASK = "HUMAN_TASK";
    public static final String REVIEW_ACTIVITY = "REVIEW_ACTIVITY";

    public static Builder builder(String kind) {
        return new Builder(kind);
    }

    public boolean isReview() {
        return REVIEW_ACTIVITY.equals(kind);
    }

    // Readable without history: what a listing, an inbox and a completion check need.
    public Map<String, Object> toMemo() {
        Map<String, Object> memo = new HashMap<>();
        memo.put(TaskKeys.KIND, kind);
        memo.put(TaskKeys.TASK_NAME, taskName);
        memo.put(TaskKeys.PARENT_WORKFLOW_ID, parentWorkflowId);
        putIfSet(memo, TaskKeys.PARENT_WORKFLOW_TYPE, parentWorkflowType);
        memo.put(TaskKeys.TITLE, title);
        memo.put(TaskKeys.DESCRIPTION, description);
        memo.put(TaskKeys.USER_ROLES, userRoles);
        putIfAny(memo, TaskKeys.USERS, users);
        putIfAny(memo, TaskKeys.EXCLUDED_USERS, excludedUsers);
        putIfAny(memo, TaskKeys.EXCLUDED_ROLES, excludedRoles);
        memo.put(TaskKeys.TASK_INPUT, taskInput);
        putIfSet(memo, TaskKeys.FORM_SCHEMA, formSchema);
        putIfSet(memo, TaskKeys.TIMEOUT_MILLIS, timeoutMillis);
        memo.put(TaskKeys.CREATED_AT, createdAt);
        if (stepId != null) {
            memo.put(WorkflowContextNative.STEP_ID_KEY, stepId);
            if (isReview()) {
                memo.put(WorkflowContextNative.REVIEW_STEP_ID_KEY,
                        stepId + WorkflowContextNative.REVIEW_STEP_ID_SUFFIX);
            }
        }
        if (isReview()) {
            memo.put(TaskKeys.TRIGGER, trigger);
            memo.put(TaskKeys.ACTIVITY_NAME, activityName);
            memo.put(TaskKeys.ERROR_MESSAGE, errorMessage == null ? "" : errorMessage);
        }
        return memo;
    }

    // Ordered for a human reading the rendered envelope: what it is, why, what it carries, where from.
    public Map<String, Object> toInputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(TaskKeys.TASK_ID, taskId);
        inputs.put(TaskKeys.TASK_NAME, taskName);
        inputs.put(TaskKeys.TITLE, title);
        inputs.put(TaskKeys.DESCRIPTION, description);
        inputs.put(TaskKeys.USER_ROLES, userRoles);
        putIfAny(inputs, TaskKeys.USERS, users);
        putIfAny(inputs, TaskKeys.EXCLUDED_USERS, excludedUsers);
        putIfAny(inputs, TaskKeys.EXCLUDED_ROLES, excludedRoles);
        inputs.put(TaskKeys.TASK_INPUT, taskInput);
        inputs.put(TaskKeys.TIMEOUT_MILLIS, timeoutMillis);
        inputs.put(TaskKeys.PARENT_WORKFLOW_ID, parentWorkflowId);
        putIfSet(inputs, TaskKeys.PARENT_WORKFLOW_TYPE, parentWorkflowType);
        if (isReview()) {
            inputs.put(TaskKeys.TRIGGER, trigger);
            inputs.put(TaskKeys.ACTIVITY_NAME, activityName);
            inputs.put(TaskKeys.ERROR_MESSAGE, errorMessage == null ? "" : errorMessage);
        }
        return inputs;
    }

    private static void putIfSet(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putIfAny(Map<String, Object> target, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    /**
     * Fluent construction; unset lists default to empty and unset strings to null.
     */
    public static final class Builder {
        private final String kind;
        private String taskId;
        private String taskName;
        private String parentWorkflowId;
        private String parentWorkflowType;
        private String stepId;
        private String title;
        private String description;
        private List<String> userRoles = List.of();
        private List<String> users = List.of();
        private List<String> excludedUsers = List.of();
        private List<String> excludedRoles = List.of();
        private Object taskInput;
        private String formSchema;
        private Long timeoutMillis;
        private String createdAt;
        private String trigger;
        private String activityName;
        private String errorMessage;

        private Builder(String kind) {
            this.kind = kind;
        }

        public Builder taskId(String value) {
            this.taskId = value;
            return this;
        }

        public Builder taskName(String value) {
            this.taskName = value;
            return this;
        }

        public Builder parentWorkflowId(String value) {
            this.parentWorkflowId = value;
            return this;
        }

        public Builder parentWorkflowType(String value) {
            this.parentWorkflowType = value;
            return this;
        }

        public Builder stepId(String value) {
            this.stepId = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder userRoles(List<String> value) {
            this.userRoles = value == null ? List.of() : value;
            return this;
        }

        public Builder users(List<String> value) {
            this.users = value == null ? List.of() : value;
            return this;
        }

        public Builder excludedUsers(List<String> value) {
            this.excludedUsers = value == null ? List.of() : value;
            return this;
        }

        public Builder excludedRoles(List<String> value) {
            this.excludedRoles = value == null ? List.of() : value;
            return this;
        }

        public Builder taskInput(Object value) {
            this.taskInput = value;
            return this;
        }

        public Builder formSchema(String value) {
            this.formSchema = value;
            return this;
        }

        public Builder timeoutMillis(Long value) {
            this.timeoutMillis = value;
            return this;
        }

        public Builder createdAt(String value) {
            this.createdAt = value;
            return this;
        }

        public Builder trigger(String value) {
            this.trigger = value;
            return this;
        }

        public Builder activityName(String value) {
            this.activityName = value;
            return this;
        }

        public Builder errorMessage(String value) {
            this.errorMessage = value;
            return this;
        }

        public TaskRecord build() {
            return new TaskRecord(kind, taskId, taskName, parentWorkflowId, parentWorkflowType, stepId, title,
                    description, userRoles, users, excludedUsers, excludedRoles, taskInput, formSchema,
                    timeoutMillis, createdAt, trigger, activityName, errorMessage);
        }
    }
}
