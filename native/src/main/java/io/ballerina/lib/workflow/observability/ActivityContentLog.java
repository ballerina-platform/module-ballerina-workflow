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

package io.ballerina.lib.workflow.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.converter.EncodedValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

// Logs each activity attempt's arguments and result when captureActivityContent is on; never fails execution.
public final class ActivityContentLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityContentLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // One value longer than this is cut, so an oversized payload cannot flood the log.
    static final int MAX_CHARS = 8192;

    private ActivityContentLog() {
    }

    // Records one attempt after it completed or failed; args[0] is the named-argument map, result is null on failure.
    public static void record(ActivityInfo info, EncodedValues args, long durationMillis, Object result,
                              Throwable failure) {
        if (!ObservabilityNative.isActivityContentCaptured()) {
            return;
        }
        try {
            Object namedArgs;
            try {
                namedArgs = args.get(0, Map.class);
            } catch (Exception e) {
                namedArgs = "<undecodable: " + e.getMessage() + ">";
            }
            StringBuilder line = new StringBuilder("Activity execution")
                    .append(" type=").append(info.getActivityType())
                    .append(" workflowId=").append(info.getWorkflowId())
                    .append(" runId=").append(info.getRunId())
                    .append(" activityId=").append(info.getActivityId())
                    .append(" attempt=").append(info.getAttempt())
                    .append(" outcome=").append(failure == null ? "success" : "failure")
                    .append(" durationMs=").append(durationMillis)
                    .append(" args=").append(render(namedArgs));
            if (failure == null) {
                line.append(" result=").append(render(result));
            } else {
                line.append(" error=").append(render(String.valueOf(failure)));
            }
            LOGGER.info(line.toString());
        } catch (Exception e) {
            LOGGER.debug("Failed to log activity content", e);
        }
    }

    private static String render(Object value) {
        String text;
        try {
            text = MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            text = String.valueOf(value);
        }
        if (text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS) + "…(" + (text.length() - MAX_CHARS) + " more chars)";
        }
        return text;
    }
}
