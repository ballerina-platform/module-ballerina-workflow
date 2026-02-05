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

package io.ballerina.stdlib.workflow.registry;

import io.ballerina.runtime.api.types.Type;

/**
 * Information about an event (signal) extracted from a workflow process function signature.
 * <p>
 * In Ballerina workflow, events are modeled as fields of type future in the events record parameter. Each event
 * corresponds to a Temporal signal that the workflow can wait for. When a signal is received, the corresponding future
 * is completed with the signal data.
 * <p>
 * Example process signature:
 * <pre>
 * &#64;workflow:process
 * function processName(workflow:Context ctx, T input,
 *     record { future approval, future rejection } events) returns R|error { }
 * </pre>
 *
 * @param fieldName   the name of the event field (e.g., "approval")
 * @param valueType   the type of value the event carries (the T in future&lt;T&gt;)
 * @param processName the name of the process this event belongs to
 * @since 0.1.0
 */
public record EventInfo(String fieldName, Type valueType, String processName) {

    /**
     * Creates a new EventInfo instance.
     *
     * @param fieldName   the name of the event field (e.g., "approval")
     * @param valueType   the type of value the event carries (the T in future&lt;T&gt;)
     * @param processName the name of the process this event belongs to
     */
    public EventInfo {
        java.util.Objects.requireNonNull(fieldName, "fieldName must not be null");
        java.util.Objects.requireNonNull(processName, "processName must not be null");
    }

    /**
     * Gets the event field name. This corresponds to the signal name in Temporal.
     *
     * @return the event field name
     */
    @Override
    public String fieldName() {
        return fieldName;
    }

    @Override
    public String toString() {
        return "EventInfo{" +
                "fieldName='" + fieldName + '\'' +
                ", valueType=" + (valueType != null ? valueType.getName() : "unknown") +
                ", processName='" + processName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        EventInfo eventInfo = (EventInfo) obj;
        return fieldName.equals(eventInfo.fieldName) && processName.equals(eventInfo.processName);
    }

    @Override
    public int hashCode() {
        int result = fieldName.hashCode();
        result = 31 * result + processName.hashCode();
        return result;
    }
}
