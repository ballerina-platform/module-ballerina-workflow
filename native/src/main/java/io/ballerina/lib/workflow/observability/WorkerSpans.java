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

import io.ballerina.lib.workflow.worker.WorkflowWorkerNative;
import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.runtime.observability.tracer.TracersStore;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.temporal.workflow.WorkflowInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

// Worker-side spans for a run's execution — its lifetime, each activity attempt, each agent step, each data
// event — opened under the trace the run was started from, so one trace tells the run's whole story.
public final class WorkerSpans {

    public static final String SERVICE = "workflow";
    // The carried context is a trace and span id, not W3C headers: header injection belongs to the tracer
    // provider's propagators, and not every provider has them.
    static final String INSTANCE_ID = "workflow.instance.id";
    static final String ROOT_INSTANCE_ID = "workflow.root.instance.id";
    static final String TRACE_ID = "traceId";
    static final String SPAN_ID = "spanId";
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerSpans.class);

    private WorkerSpans() {
    }

    // The context a start puts on the wire, so the run's spans open in the instance's own trace.
    public static Map<String, String> instanceContext(String instanceId) {
        if (!ObserveUtils.isTracingEnabled()) {
            return null;
        }
        SpanContext anchor = InstanceTrace.anchorOf(instanceId);
        if (anchor == null) {
            return null;
        }
        return Map.of(TRACE_ID, anchor.getTraceId(), SPAN_ID, anchor.getSpanId());
    }

    // Opens a span under the run's propagated trace context (a root span when the run has none); null when off.
    public static Span begin(String operation, Map<String, String> tags) {
        return begin(operation, tags, TraceContextPropagator.current());
    }

    // As above, under an explicit parent context — for threads the engine does not hand the context to.
    public static Span begin(String operation, Map<String, String> tags, Map<String, String> parent) {
        if (!ObserveUtils.isTracingEnabled() || !TracersStore.getInstance().isInitialized()) {
            return null;
        }
        try {
            SpanBuilder builder = TracersStore.getInstance().getTracer(SERVICE).spanBuilder(operation)
                    .setSpanKind(SpanKind.INTERNAL);
            SpanContext parentContext = parent == null ? null : SpanContext.createFromRemoteParent(
                    parent.getOrDefault(TRACE_ID, ""), parent.getOrDefault(SPAN_ID, ""),
                    TraceFlags.getSampled(), TraceState.getDefault());
            if (parentContext == null || !parentContext.isValid()) {
                // Nothing reached this thread — the instance's own trace, derived from its id.
                parentContext = InstanceTrace.anchorOf(anchorInstanceOf(tags));
            }
            if (parentContext != null && parentContext.isValid()) {
                builder.setParent(Context.root().with(Span.wrap(parentContext)));
            } else {
                builder.setNoParent();
            }
            Span span = builder.startSpan();
            tag(span, identityTags());
            tag(span, tags);
            return span;
        } catch (Exception e) {
            LOGGER.debug("Could not start worker span '{}'", operation, e);
            return null;
        }
    }

    // A span as the trace context its children carry.
    public static Map<String, String> contextOf(Span span) {
        if (span == null || !span.getSpanContext().isValid()) {
            return null;
        }
        return Map.of(TRACE_ID, span.getSpanContext().getTraceId(), SPAN_ID, span.getSpanContext().getSpanId());
    }

    public static void tag(Span span, Map<String, String> tags) {
        if (span == null || tags == null) {
            return;
        }
        tags.forEach(span::setAttribute);
    }

    public static void end(Span span, Throwable failure) {
        if (span == null) {
            return;
        }
        try {
            if (failure != null) {
                String message = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
                // A string, not a boolean: a tracer's span record may declare its tags as strings.
                span.setAttribute("error", "true");
                span.setAttribute("error.type", errorTypeOf(failure));
                span.setAttribute("error.message", message);
                span.setStatus(StatusCode.ERROR, message);
            }
            span.end();
        } catch (Exception e) {
            LOGGER.debug("Could not finish worker span", e);
        }
    }

    // An agent step reports its failure type as the exception message; engine failures carry a type of their own.
    private static String errorTypeOf(Throwable failure) {
        if (failure.getClass().getSimpleName().equals("AgentStepFailure")) {
            return failure.getMessage();
        }
        return WorkflowMetrics.errorTypeOf(failure);
    }

    // A span for something that already happened, carrying its own duration tag.
    public static void point(String operation, Map<String, String> tags, Throwable failure) {
        end(begin(operation, tags), failure);
    }

    public static void point(String operation, Map<String, String> tags, Throwable failure,
                             Map<String, String> parent) {
        end(begin(operation, tags, parent), failure);
    }

    // The instance whose trace a span belongs to: the root run of a tree, else the run itself.
    static String anchorInstanceOf(Map<String, String> tags) {
        if (tags == null) {
            return null;
        }
        String root = tags.get(ROOT_INSTANCE_ID);
        return root != null ? root : tags.get(INSTANCE_ID);
    }

    // The tags that place a span on its run.
    public static Map<String, String> runTags(WorkflowInfo info) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(INSTANCE_ID, info.getWorkflowId());
        // A task child or a child workflow belongs to the trace of the run it was started from, not its own.
        info.getRootWorkflowId().filter(root -> !root.equals(info.getWorkflowId()))
                .ifPresent(root -> tags.put(ROOT_INSTANCE_ID, root));
        tags.put("workflow.run.id", info.getRunId());
        tags.put("workflow.type", info.getWorkflowType());
        String taskKind = WorkflowMetrics.taskKindOf(info.getWorkflowType());
        if (!WorkflowMetrics.NONE.equals(taskKind)) {
            tags.put("workflow.task.kind", taskKind);
            tags.put("workflow.task.name", WorkflowMetrics.taskNameOf(info.getWorkflowType()));
        }
        return tags;
    }

    private static Map<String, String> identityTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("span.type", "workflow");
        tags.put("module", "workflow");
        tags.put("type", "worker");
        String url = WorkflowWorkerNative.getServerUrl();
        if (url != null && !url.isEmpty()) {
            tags.put("remote.url", url);
        }
        String queue = WorkflowWorkerNative.getTaskQueue();
        if (queue != null && !queue.isEmpty()) {
            tags.put("task.queue", queue);
        }
        return tags;
    }
}
