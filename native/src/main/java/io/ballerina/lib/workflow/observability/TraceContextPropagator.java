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

import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

// Carries the starting request's W3C trace context in Temporal headers, so every workflow task, activity and
// child of a run can open its spans under the trace that started it.
public final class TraceContextPropagator implements ContextPropagator {

    public static final String NAME = "ballerina-workflow-trace-context";
    private static final String HEADER_KEY = "workflowTraceContext";
    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
        if (!(context instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Optional<Payload> payload = DataConverter.getDefaultInstance().toPayload(map);
        return payload.map(p -> Map.of(HEADER_KEY, p)).orElse(Map.of());
    }

    @Override
    public Object deserializeContext(Map<String, Payload> header) {
        Payload payload = header.get(HEADER_KEY);
        if (payload == null) {
            return null;
        }
        return DataConverter.getDefaultInstance().fromPayload(payload, Map.class, Map.class);
    }

    @Override
    public Object getCurrentContext() {
        return CURRENT.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setCurrentContext(Object context) {
        if (context instanceof Map<?, ?> map) {
            CURRENT.set((Map<String, String>) map);
        } else {
            CURRENT.remove();
        }
    }

    // The trace context the engine delivered for the task running on this thread, or null.
    public static Map<String, String> current() {
        return CURRENT.get();
    }

    // Makes `context` what this thread propagates from now on — the run span, once the worker has opened it.
    public static void setCurrent(Map<String, String> context) {
        CURRENT.set(context);
    }

    // Runs a client-side start with the given context as the one the SDK reads into the start headers.
    public static <T> T runWith(Map<String, String> traceContext, Supplier<T> start) {
        Map<String, String> previous = CURRENT.get();
        CURRENT.set(traceContext);
        try {
            return start.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
