/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

package io.ballerina.lib.workflow.test;

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BString;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * History export and replay for the module tests. Kept apart from {@link TestNatives}, which example
 * programs bind for their mock models: this class alone reaches into the SDK's replay support.
 */
public final class HistoryReplay {

    private HistoryReplay() {
    }

    /**
     * The full event history of an instance as JSON, so a test can keep it as a replay fixture.
     *
     * @param workflowId the instance
     * @return the history JSON, or an error
     */
    public static Object exportHistoryJson(BString workflowId) {
        try {
            return StringUtils.fromString(WorkflowWorkerNative.getWorkflowClient()
                    .fetchHistory(workflowId.getValue()).toJson(true));
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to export the history of '" + workflowId.getValue() + "': " + e.getMessage()));
        }
    }

    /**
     * Replays a history JSON file against this process's worker: the workflow code registered now must make
     * the same decisions the history records, or the replay fails as a real worker would after a restart.
     *
     * @param path the JSON file, relative to the working directory
     * @return null when the history replays, or an error naming the divergence
     */
    public static Object replayHistoryFile(BString path) {
        try {
            return replayHistoryJson(StringUtils.fromString(Files.readString(Path.of(path.getValue()))));
        } catch (IOException e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Failed to read the history file '" + path.getValue() + "': " + e.getMessage()));
        }
    }

    /**
     * Replays a history JSON against this process's worker.
     *
     * @param json the history as exported by {@link #exportHistoryJson}
     * @return null when the history replays, or an error naming the divergence
     */
    public static Object replayHistoryJson(BString json) {
        try {
            WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(json.getValue()),
                    WorkflowWorkerNative.getWorker());
            return null;
        } catch (Exception e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "The history does not replay against the current code: " + e.getMessage()));
        }
    }
}
