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

package io.ballerina.stdlib.workflow.activity;

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Native implementation for the {@code workflow.activity} submodule.
 * <p>
 * Provides deterministic workflow utility functions that are safe to call
 * from inside {@code @workflow:Workflow} functions:
 * <ul>
 *   <li>{@link #sleepMillis(long)} – durable sleep that survives replays</li>
 *   <li>{@link #currentTimeUtc()} – workflow-time "now" (deterministic across replays)</li>
 * </ul>
 *
 * @since 0.3.0
 */
public final class WorkflowActivityNative {

    private WorkflowActivityNative() {
        // Utility class, prevent instantiation
    }

    /**
     * Durable sleep that is persisted in the workflow history.
     * <p>
     * Records the timer in the workflow event history. The sleep survives
     * program restarts and is replayed correctly without re-sleeping during
     * workflow replay.
     *
     * @param millis duration in milliseconds
     * @return null on success, a Ballerina error on failure
     */
    public static Object sleepMillis(long millis) {
        try {
            Workflow.sleep(Duration.ofMillis(millis));
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(
                    StringUtils.fromString("Workflow sleep failed: " + e.getMessage()));
        }
    }

    /**
     * Returns the current workflow time as epoch milliseconds.
     * <p>
     * The workflow engine records the timestamp at each workflow task and
     * provides it via {@code Workflow.currentTimeMillis()}. This value is
     * replayed identically, making it safe to use inside workflow functions.
     * It differs from the OS wall-clock time, which is non-deterministic and
     * must not be used inside workflow functions.
     *
     * @return epoch milliseconds as a long
     */
    public static long currentTimeMillis() {
        return Workflow.currentTimeMillis();
    }
}
