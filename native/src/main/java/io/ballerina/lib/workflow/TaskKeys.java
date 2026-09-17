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

package io.ballerina.lib.workflow;

/**
 * The wire vocabulary of a task: memo and input keys, decision and completion envelope fields. Writers and
 * readers share these so the two sides cannot drift.
 */
public final class TaskKeys {

    // Memo and child-input keys
    public static final String KIND = "workflowKind";
    public static final String TASK_ID = "taskId";
    public static final String TASK_NAME = "taskName";
    public static final String PARENT_WORKFLOW_ID = "parentWorkflowId";
    public static final String PARENT_WORKFLOW_TYPE = "parentWorkflowType";
    public static final String TITLE = "title";
    public static final String DESCRIPTION = "description";
    public static final String USER_ROLES = "userRoles";
    public static final String USERS = "users";
    public static final String EXCLUDED_USERS = "excludedUsers";
    public static final String EXCLUDED_ROLES = "excludedRoles";
    public static final String TASK_INPUT = "taskInput";
    public static final String FORM_SCHEMA = "formSchema";
    public static final String TIMEOUT_MILLIS = "timeoutMillis";
    public static final String CREATED_AT = "createdAt";
    public static final String TRIGGER = "trigger";
    public static final String ACTIVITY_NAME = "activityName";
    public static final String ERROR_MESSAGE = "errorMessage";
    /** Pre-0.10 reviews recorded the reviewed arguments under this key; read-only compatibility. */
    public static final String LEGACY_ACTIVITY_ARGS = "activityArgs";

    // Review triggers
    public static final String TRIGGER_PRE_RUN = "PRE_RUN";
    public static final String TRIGGER_ON_FAILURE = "ON_FAILURE";

    // Review decision envelope
    public static final String ACTION = "action";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_PROCEED = "proceed";
    public static final String ACTION_PROCEED_WITH_INPUT = "proceed-with-input";
    public static final String INPUT = "input";
    public static final String FEEDBACK = "feedback";
    public static final String TIMED_OUT = "timedOut";
    public static final String TIMED_OUT_AFTER = "timedOutAfter";
    public static final String TIMED_OUT_AT = "timedOutAt";
    public static final String FAILED = "failed";
    public static final String DECIDED_BY = "decidedBy";
    public static final String DECIDED_AT = "decidedAt";

    // Human task completion envelope
    public static final String COMPLETED_BY = "completedBy";
    public static final String COMPLETED_AT = "completedAt";
    public static final String IDENTITY_SOURCE = "identitySource";

    private TaskKeys() {
    }
}
