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

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.runtime.observability.ObserverContext;
import io.ballerina.runtime.observability.tracer.BSpan;
import io.ballerina.runtime.observability.tracer.TracersStore;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// The spans for the calls an application makes about an instance: starting it, sending it data, waiting for its
// result, deciding one of its tasks.
//
// They open in the instance's trace rather than under the caller, so a run's interactions read as one story
// instead of one span in each unrelated request trace. The request that made the call is not lost: the span
// carries a link to the caller's own span, which is what a tracing UI follows back.
//
// A span is built when it closes, from the start instant kept here: which instance a call belongs to is
// sometimes known only by then — a start learns the id it was given, a decision learns which run owns the task.
public final class ClientSpans {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSpans.class);
    private static final ConcurrentHashMap<Long, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    // Bounds the map if a caller ever opens spans it never closes; the module's own wrappers always close.
    private static final int MAX_PENDING = 10000;
    private static final String NONE = "none";
    // Resolved once: looking the host up can block on DNS, and every client span closes on a request thread.
    private static final String HOST = hostName();

    private record Pending(Instant startedAt, SpanContext caller) {
    }

    private ClientSpans() {
    }

    // Opens a recording; 0 when tracing is off or nothing would be recorded.
    public static long begin(Environment env) {
        if (!ObserveUtils.isTracingEnabled() || !TracersStore.getInstance().isInitialized()) {
            return 0;
        }
        try {
            if (PENDING.size() >= MAX_PENDING) {
                LOGGER.debug("Too many client spans are open; not recording another");
                return 0;
            }
            long id = NEXT_ID.getAndIncrement();
            PENDING.put(id, new Pending(Instant.now(), callerContext(env)));
            return id;
        } catch (Exception e) {
            LOGGER.debug("Could not open a client span", e);
            return 0;
        }
    }

    // Records the span that `begin` opened, in the trace of `instanceId` and linked to the caller's span.
    public static void end(long id, String name, Map<String, String> tags, String instanceId,
                           String errorType, String errorMessage) {
        Pending pending = PENDING.remove(id);
        if (pending == null) {
            return;
        }
        try {
            SpanBuilder builder = TracersStore.getInstance().getTracer(WorkerSpans.SERVICE).spanBuilder(name)
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(pending.startedAt());
            SpanContext anchor = InstanceTrace.anchorOf(instanceId);
            if (anchor != null) {
                builder.setParent(Context.root().with(Span.wrap(anchor)));
            } else {
                builder.setNoParent();
            }
            if (pending.caller() != null) {
                builder.addLink(pending.caller());
            }
            Span span = builder.startSpan();
            WorkerSpans.tag(span, identityTags());
            WorkerSpans.tag(span, tags);
            if (!errorType.isEmpty()) {
                // Strings, not booleans: a tracer's span record may declare its tags as strings.
                span.setAttribute("error", "true");
                span.setAttribute("error.type", errorType);
                span.setAttribute("error.message", errorMessage);
                span.setStatus(StatusCode.ERROR, errorMessage);
            }
            span.end();
        } catch (Exception e) {
            LOGGER.debug("Could not record client span '{}'", name, e);
        }
    }

    // The span the caller was in when it made the call, so the recorded span can link back to its request.
    private static SpanContext callerContext(Environment env) {
        if (env == null) {
            return null;
        }
        ObserverContext context = ObserveUtils.getObserverContextOfCurrentFrame(env);
        BSpan span = context == null ? null : context.getSpan();
        if (span == null) {
            return null;
        }
        BMap<BString, Object> ids = span.getBSpanContext();
        SpanContext caller = SpanContext.createFromRemoteParent(
                String.valueOf(ids.get(StringUtils.fromString(WorkerSpans.TRACE_ID))),
                String.valueOf(ids.get(StringUtils.fromString(WorkerSpans.SPAN_ID))),
                TraceFlags.getSampled(), TraceState.getDefault());
        return caller.isValid() ? caller : null;
    }

    // The worker's identity tags plus the host, which places a client call.
    static Map<String, String> identityTags() {
        Map<String, String> tags = WorkerSpans.identityTags("client");
        tags.put("host", HOST);
        return tags;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return NONE;
        }
    }
}
