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

package io.ballerina.lib.workflow.context;

import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-JVM store of durable agents' final textual responses, keyed by workflow id. Agents have no workflow return
 * value, so the loop records the last assistant message here on completion.
 * <p>
 * Kept deliberately free of Temporal SDK references: test packages bind {@link #getFinalResponse} directly, and
 * jBallerina interop verification loads the whole class against the binding package's own platform classpath.
 *
 * @since 0.7.0
 */
public final class AgentResponseStore {

    private static final Map<String, String> FINAL_RESPONSES = new ConcurrentHashMap<>();

    private AgentResponseStore() {
        // Utility class
    }

    /**
     * Records the final response of an agent.
     *
     * @param workflowId the agent's workflow id
     * @param response   the final response text
     */
    public static void put(String workflowId, String response) {
        FINAL_RESPONSES.put(workflowId, response);
    }

    /**
     * Returns the final textual response recorded for a completed agent, or null if none.
     *
     * @param workflowId the agent's workflow id
     * @return the final response (BString) or null
     */
    public static Object getFinalResponse(BString workflowId) {
        String response = FINAL_RESPONSES.get(workflowId.getValue());
        return response == null ? null : StringUtils.fromString(response);
    }
}
