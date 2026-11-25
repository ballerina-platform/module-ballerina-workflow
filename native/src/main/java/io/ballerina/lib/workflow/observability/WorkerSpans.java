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
import io.ballerina.runtime.observability.ObservabilityConstants;
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
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.temporal.common.converter.EncodedValues;
import io.temporal.workflow.WorkflowInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Worker-side spans for a run's execution — its lifetime, each activity attempt, each agent step, each data
// event — opened under the trace the run was started from, so one trace tells the run's whole story.
public final class WorkerSpans {

    public static final String SERVICE = "workflow";
    private static volatile Tracer tracer;
    // The carried context is a trace and span id, not W3C headers: header injection belongs to the tracer
    // provider's propagators, and not every provider has them.
    static final String INSTANCE_ID = "workflow.instance.id";
    static final String ROOT_INSTANCE_ID = "workflow.root.instance.id";
    static final String TRACE_ID = "traceId";
    static final String SPAN_ID = "spanId";
    // Hex trace flags, carried only when the span is not sampled: an absent key reads as sampled, as before.
    static final String TRACE_FLAGS = "traceFlags";
    // The run span a turn recorded in its sender's trace links back to.
    static final String LINK_TRACE_ID = "linkTraceId";
    static final String LINK_SPAN_ID = "linkSpanId";
    // The starter's span, carried in an agent's start header beside the instance's own context.
    static final String SENDER_TRACE_ID = "senderTraceId";
    static final String SENDER_SPAN_ID = "senderSpanId";
    static final String SENDER_TRACE_FLAGS = "senderTraceFlags";
    static final String CONVERSATION_ID = "gen_ai.conversation.id";
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerSpans.class);
    private static final Constructor<BSpan> BSPAN_OF = bSpanConstructor();

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

    // The context a run hands its activities and children when none reached it: the root's derived anchor.
    public static Map<String, String> fallbackContext(WorkflowInfo info) {
        String root = anchorInstanceOf(runTags(info));
        return withRoot(instanceContext(root), root);
    }

    // The context a run's own steps carry: the span to hang them from, and the instance whose trace that is.
    public static Map<String, String> runContext(Span span, WorkflowInfo info) {
        return withRoot(contextOf(span), anchorInstanceOf(runTags(info)));
    }

    // The sender's context from an agent update's optional argument at `index`; null when absent or malformed.
    // Runs in a workflow-thread update handler, so it must never throw: that would fail the task on every retry.
    public static Map<String, String> senderContextOf(EncodedValues args, int index) {
        try {
            if (args == null || args.getSize() <= index) {
                return null;
            }
            if (!(args.get(index, Object.class) instanceof Map<?, ?> raw)
                    || !(raw.get(TRACE_ID) instanceof String traceId)
                    || !(raw.get(SPAN_ID) instanceof String spanId)) {
                return null;
            }
            return idsOf(traceId, spanId, raw.get(TRACE_FLAGS) instanceof String flags ? flags : null);
        } catch (RuntimeException e) {
            LOGGER.debug("Ignoring an unreadable sender trace context", e);
            return null;
        }
    }

    // The context a turn records under: the sender's span, the run's root instance for its activities, and a link
    // back to the run's span. Null when the sender sent no valid context.
    public static Map<String, String> turnContext(Map<String, String> sender, Map<String, String> runContext) {
        if (sender == null || sender.get(TRACE_ID) == null || sender.get(SPAN_ID) == null) {
            return null;
        }
        if (!parentOf(sender).isValid()) {
            return null;
        }
        Map<String, String> context = new LinkedHashMap<>(idsOf(sender.get(TRACE_ID), sender.get(SPAN_ID),
                sender.get(TRACE_FLAGS)));
        String root = rootOf(runContext);
        if (root != null) {
            context.put(ROOT_INSTANCE_ID, root);
        }
        if (runContext != null && parentOf(runContext).isValid()
                && !runContext.get(TRACE_ID).equals(sender.get(TRACE_ID))) {
            context.put(LINK_TRACE_ID, runContext.get(TRACE_ID));
            context.put(LINK_SPAN_ID, runContext.get(SPAN_ID));
        }
        return context;
    }

    // The start header of an agent run: the instance's own context, plus the starter's span as `sender*` keys.
    public static Map<String, String> withSender(Map<String, String> instanceContext, Map<String, String> sender) {
        if (instanceContext == null || sender == null || sender.get(TRACE_ID) == null
                || sender.get(SPAN_ID) == null) {
            return instanceContext;
        }
        Map<String, String> header = new LinkedHashMap<>(instanceContext);
        header.put(SENDER_TRACE_ID, sender.get(TRACE_ID));
        header.put(SENDER_SPAN_ID, sender.get(SPAN_ID));
        if (sender.get(TRACE_FLAGS) != null) {
            header.put(SENDER_TRACE_FLAGS, sender.get(TRACE_FLAGS));
        }
        return header;
    }

    // The starter's span from a start header, or null when the start carried none.
    public static Map<String, String> senderOfStart(Map<String, String> header) {
        if (header == null || header.get(SENDER_TRACE_ID) == null || header.get(SENDER_SPAN_ID) == null) {
            return null;
        }
        return idsOf(header.get(SENDER_TRACE_ID), header.get(SENDER_SPAN_ID), header.get(SENDER_TRACE_FLAGS));
    }

    // A carried context as the remote parent it names, honouring its trace flags; invalid when it names none.
    static SpanContext parentOf(Map<String, String> context) {
        if (context == null) {
            return SpanContext.getInvalid();
        }
        return SpanContext.createFromRemoteParent(context.getOrDefault(TRACE_ID, ""),
                context.getOrDefault(SPAN_ID, ""), flagsOf(context.get(TRACE_FLAGS)), TraceState.getDefault());
    }

    // The run span a turn's context links back to, or null when it links to none.
    static SpanContext linkOf(Map<String, String> context) {
        if (context == null || context.get(LINK_TRACE_ID) == null || context.get(LINK_SPAN_ID) == null) {
            return null;
        }
        SpanContext link = SpanContext.createFromRemoteParent(context.get(LINK_TRACE_ID),
                context.get(LINK_SPAN_ID), TraceFlags.getSampled(), TraceState.getDefault());
        return link.isValid() ? link : null;
    }

    // Two hex digits, as W3C trace context writes them; anything else reads as sampled, the old default.
    static TraceFlags flagsOf(String hex) {
        if (hex == null || hex.length() != 2 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return TraceFlags.getSampled();
        }
        return TraceFlags.fromHex(hex, 0);
    }

    // A trace and span id, with the flags only when they say "not sampled".
    static Map<String, String> idsOf(String traceId, String spanId, String flags) {
        if (flagsOf(flags).isSampled()) {
            return Map.of(TRACE_ID, traceId, SPAN_ID, spanId);
        }
        return Map.of(TRACE_ID, traceId, SPAN_ID, spanId, TRACE_FLAGS, flagsOf(flags).asHex());
    }

    // The instance a carried context belongs to, for a thread that knows no more than the context it was given.
    public static String rootOf(Map<String, String> context) {
        return context == null ? null : context.get(ROOT_INSTANCE_ID);
    }

    // An activity is told only the workflow that scheduled it, so the run's context names the tree's root too.
    private static Map<String, String> withRoot(Map<String, String> context, String root) {
        if (context == null || root == null || root.isEmpty()) {
            return context;
        }
        Map<String, String> carried = new LinkedHashMap<>(context);
        carried.put(ROOT_INSTANCE_ID, root);
        return carried;
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
            SpanBuilder builder = tracer().spanBuilder(operation)
                    .setSpanKind(SpanKind.INTERNAL);
            SpanContext parentContext = parentOf(parent);
            SpanContext link = linkOf(parent);
            if (link != null && !link.getTraceId().equals(parentContext.getTraceId())) {
                builder.addLink(link);
            }
            if (!parentContext.isValid()) {
                // Nothing reached this thread — the instance's own trace, derived from its id.
                parentContext = InstanceTrace.anchorOf(anchorInstanceOf(tags));
            }
            if (parentContext != null && parentContext.isValid()) {
                builder.setParent(Context.root().with(Span.wrap(parentContext)));
            } else {
                builder.setNoParent();
            }
            Span span = builder.startSpan();
            tag(span, identityTags("worker"));
            tag(span, tags);
            return span;
        } catch (Exception e) {
            LOGGER.debug("Could not start worker span '{}'", operation, e);
            return null;
        }
    }

    // Resolved once: TracersStore keeps its tracers in an unsynchronized HashMap, and every span open
    // would otherwise race a concurrent put on it.
    static Tracer tracer() {
        Tracer resolved = tracer;
        if (resolved == null) {
            resolved = TracersStore.getInstance().getTracer(SERVICE);
            tracer = resolved;
        }
        return resolved;
    }

    // The bound the metrics path puts on caller-supplied data names, so a span never outgrows it either.
    public static String dataName(String name) {
        return WorkflowMetrics.boundedDataName(name);
    }

    // A span as the trace context its children carry.
    public static Map<String, String> contextOf(Span span) {
        if (span == null || !span.getSpanContext().isValid()) {
            return null;
        }
        SpanContext context = span.getSpanContext();
        return idsOf(context.getTraceId(), context.getSpanId(), context.getTraceFlags().asHex());
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
        if (failure instanceof AgentStepTelemetry.AgentStepFailure) {
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
        putConversationId(tags, info.getWorkflowType(), info.getWorkflowId());
        String taskKind = WorkflowMetrics.taskKindOf(info.getWorkflowType());
        if (!WorkflowMetrics.NONE.equals(taskKind)) {
            tags.put("workflow.task.kind", taskKind);
            tags.put("workflow.task.name", WorkflowMetrics.taskNameOf(info.getWorkflowType()));
        }
        return tags;
    }

    // A durable agent's instance is one conversation, so its spans carry the instance id as the conversation id.
    public static void putConversationId(Map<String, String> tags, String workflowType, String instanceId) {
        if (instanceId != null && WorkflowWorkerNative.isAgentWorkflowType(workflowType)) {
            tags.put(CONVERSATION_ID, instanceId);
        }
    }

    // Strand properties that make `span` the parent of what the strand observes; null when there is none.
    public static Map<String, Object> strandProperties(Span span, String functionModule, String functionName) {
        if (span == null || !span.getSpanContext().isValid() || BSPAN_OF == null) {
            return null;
        }
        try {
            ObserverContext context = new ObserverContext();
            context.setServiceName(ObservabilityConstants.DEFAULT_SERVICE_NAME);
            context.setEntrypointFunctionModule(functionModule != null ? functionModule : SERVICE);
            context.setEntrypointFunctionName(functionName);
            context.setOperationName(functionName);
            context.setSpan(BSPAN_OF.newInstance(tracer(), span));
            // A mutable map: the strand records its own observer contexts in it as it runs.
            Map<String, Object> properties = new HashMap<>();
            properties.put(ObservabilityConstants.KEY_OBSERVER_CONTEXT, context);
            return properties;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Could not hand span to the Ballerina strand", e);
            return null;
        }
    }

    // BSpan has no public way to wrap a span opened elsewhere; its own constructor does exactly that.
    // TODO: Use a public API once https://github.com/ballerina-platform/ballerina-lang/issues/44944 is fixed.
    private static Constructor<BSpan> bSpanConstructor() {
        try {
            Constructor<BSpan> constructor = BSpan.class.getDeclaredConstructor(Tracer.class, Span.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Worker spans cannot parent Ballerina spans: BSpan cannot wrap a span", e);
            return null;
        }
    }

    // Identity tags every span of the module carries: module, which side opened it, engine endpoint, task queue.
    static Map<String, String> identityTags(String type) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("span.type", "workflow");
        tags.put("module", "workflow");
        tags.put("type", type);
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
