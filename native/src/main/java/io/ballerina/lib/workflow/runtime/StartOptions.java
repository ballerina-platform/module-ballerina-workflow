/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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

package io.ballerina.lib.workflow.runtime;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How a new instance is started: the caller's id, if any, and what to do when that id is
 * already taken by a running or a closed instance. Without a caller id nothing is set and the
 * engine's defaults apply, exactly as before ids could be chosen.
 *
 * @param instanceId     the caller-chosen id, or null for a generated UUID v7
 * @param ifRunning      policy name when an instance with the id is running
 * @param ifClosed       policy name when an instance with the id has closed
 * @param timeoutSeconds whole-execution timeout, or null
 * @param startedBy      starter identity for the memo, or null
 * @since 0.11.0
 */
public record StartOptions(String instanceId, String ifRunning, String ifClosed, Long timeoutSeconds,
                           String startedBy) {

    public static final String FAIL = "FAIL";
    public static final String USE_EXISTING = "USE_EXISTING";
    public static final String TERMINATE_EXISTING = "TERMINATE_EXISTING";
    public static final String ALLOW_DUPLICATE = "ALLOW_DUPLICATE";
    public static final String ALLOW_DUPLICATE_FAILED_ONLY = "ALLOW_DUPLICATE_FAILED_ONLY";
    public static final String REJECT_DUPLICATE = "REJECT_DUPLICATE";

    /** The longest id accepted, well under the server's own limit. */
    // The engine measures an id in UTF-8 bytes, so this limit does too
    public static final int MAX_INSTANCE_ID_BYTES = 255;

    // Prefixes the runtime issues to its children; a caller id wearing one would be misclassified
    // by the legacy kind fallback. The compiler plugin keeps an equal copy (WORKFLOW_166).
    public static final List<String> RESERVED_PREFIXES = WorkflowWorkerNative.RESERVED_INSTANCE_ID_PREFIXES;

    /** Options for a generated id: no policies set. */
    public static StartOptions generated() {
        return new StartOptions(null, FAIL, ALLOW_DUPLICATE, null, null);
    }

    /**
     * Checks the caller id, if any, and the policy names.
     *
     * @throws InvalidStartOptionsException with the reason when something is not acceptable
     */
    public void validate() {
        if (instanceId != null) {
            String trimmed = instanceId.strip();
            if (trimmed.isEmpty()) {
                throw new InvalidStartOptionsException("instanceId must not be blank");
            }
            if (!trimmed.equals(instanceId)) {
                throw new InvalidStartOptionsException("instanceId must not have leading or trailing whitespace");
            }
            int length = instanceId.getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_INSTANCE_ID_BYTES) {
                throw new InvalidStartOptionsException("instanceId must be at most " + MAX_INSTANCE_ID_BYTES
                        + " bytes in UTF-8, got " + length);
            }
            for (String prefix : RESERVED_PREFIXES) {
                if (instanceId.startsWith(prefix)) {
                    throw new InvalidStartOptionsException("instanceId must not start with the reserved prefix '"
                            + prefix + "'");
                }
            }
        }
        conflictPolicy();
        reusePolicy();
    }

    /** Whether the caller chose the id. */
    public boolean hasCallerId() {
        return instanceId != null;
    }

    public WorkflowIdConflictPolicy conflictPolicy() {
        return switch (ifRunning == null ? FAIL : ifRunning) {
            case FAIL -> WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
            case USE_EXISTING -> WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
            case TERMINATE_EXISTING -> WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING;
            default -> throw new InvalidStartOptionsException("ifRunning must be one of FAIL, USE_EXISTING or "
                    + "TERMINATE_EXISTING, got '" + ifRunning + "'");
        };
    }

    public WorkflowIdReusePolicy reusePolicy() {
        return switch (ifClosed == null ? ALLOW_DUPLICATE : ifClosed) {
            case ALLOW_DUPLICATE -> WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE;
            case ALLOW_DUPLICATE_FAILED_ONLY ->
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY;
            case REJECT_DUPLICATE -> WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
            default -> throw new InvalidStartOptionsException("ifClosed must be one of ALLOW_DUPLICATE, "
                    + "ALLOW_DUPLICATE_FAILED_ONLY or REJECT_DUPLICATE, got '" + ifClosed + "'");
        };
    }
}
