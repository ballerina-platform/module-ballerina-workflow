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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorkerSpansTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";
    private static final String RUN_TRACE = "a".repeat(32);
    private static final String RUN_SPAN = "b".repeat(16);

    // Encodes the arguments as the engine hands them to the update handler.
    private static EncodedValues updateArgs(Object... values) {
        DataConverter converter = DataConverter.getDefaultInstance();
        return new EncodedValues(converter.toPayloads(values), converter);
    }

    @Test
    public void testSenderContextIsReadFromTheThirdArgument() {
        Map<String, String> context = WorkerSpans.senderContextOf(
                updateArgs("chat", "hello", Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN)), 2);
        Assert.assertEquals(context, Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN));
    }

    @Test
    public void testAnUpdateFromAnOlderSenderHasNoContext() {
        Assert.assertNull(WorkerSpans.senderContextOf(updateArgs("chat", "hello"), 2));
        Assert.assertNull(WorkerSpans.senderContextOf(new EncodedValues(Optional.empty(),
                DataConverter.getDefaultInstance()), 2));
        Assert.assertNull(WorkerSpans.senderContextOf(null, 2));
    }

    @DataProvider
    public Object[][] malformedContexts() {
        Map<String, Object> numericIds = new LinkedHashMap<>();
        numericIds.put(WorkerSpans.TRACE_ID, 42);
        numericIds.put(WorkerSpans.SPAN_ID, true);
        return new Object[][]{
                {"not-a-map"},
                {42},
                {List.of(TRACE, SPAN)},
                {numericIds},
                {Map.of(WorkerSpans.TRACE_ID, TRACE)},
                {Map.of("trace", TRACE, "span", SPAN)},
                {Map.of(WorkerSpans.TRACE_ID, Map.of("nested", TRACE), WorkerSpans.SPAN_ID, SPAN)}
        };
    }

    // A malformed context must be ignored, not thrown: the handler runs on the workflow thread.
    @Test(dataProvider = "malformedContexts")
    public void testAMalformedSenderContextIsIgnored(Object malformed) {
        Assert.assertNull(WorkerSpans.senderContextOf(updateArgs("chat", "hello", malformed), 2));
    }

    @Test
    public void testATurnRecordsUnderTheSenderKeepsTheRunsRootAndLinksToTheRun() {
        Map<String, String> turn = WorkerSpans.turnContext(
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN),
                Map.of(WorkerSpans.TRACE_ID, RUN_TRACE, WorkerSpans.SPAN_ID, RUN_SPAN,
                        WorkerSpans.ROOT_INSTANCE_ID, "agent-1"));
        Assert.assertEquals(turn, Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN,
                WorkerSpans.ROOT_INSTANCE_ID, "agent-1",
                WorkerSpans.LINK_TRACE_ID, RUN_TRACE, WorkerSpans.LINK_SPAN_ID, RUN_SPAN));
        SpanContext link = WorkerSpans.linkOf(turn);
        Assert.assertNotNull(link);
        Assert.assertEquals(link.getTraceId(), RUN_TRACE);
        Assert.assertEquals(link.getSpanId(), RUN_SPAN);
    }

    // A sender already in the run's trace needs no link back, and a run with no context has none to give.
    @Test
    public void testATurnLinksOnlyWhenItLeavesTheRunsTrace() {
        Map<String, String> sender = Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN);
        Assert.assertNull(WorkerSpans.linkOf(WorkerSpans.turnContext(sender,
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, RUN_SPAN))));
        Assert.assertNull(WorkerSpans.linkOf(WorkerSpans.turnContext(sender, null)));
        Assert.assertNull(WorkerSpans.linkOf(WorkerSpans.turnContext(sender,
                Map.of(WorkerSpans.ROOT_INSTANCE_ID, "agent-1"))));
    }

    @Test
    public void testAnUnsampledSenderStaysUnsampled() {
        Map<String, String> sender = WorkerSpans.senderContextOf(updateArgs("chat", "hello",
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN, WorkerSpans.TRACE_FLAGS, "00")), 2);
        Assert.assertEquals(sender, Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN,
                WorkerSpans.TRACE_FLAGS, "00"));
        Map<String, String> turn = WorkerSpans.turnContext(sender, null);
        Assert.assertEquals(turn.get(WorkerSpans.TRACE_FLAGS), "00");
        Assert.assertFalse(WorkerSpans.parentOf(turn).isSampled());
        Assert.assertTrue(WorkerSpans.parentOf(turn).isValid());
    }

    // No flags, or flags that cannot be read, mean "sampled": what every context carried before flags were sent.
    @Test
    public void testMissingOrMalformedFlagsReadAsSampled() {
        Assert.assertTrue(WorkerSpans.parentOf(Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN))
                .isSampled());
        for (String flags : new String[]{"", "1", "zz", "0001", "sampled"}) {
            Assert.assertTrue(WorkerSpans.flagsOf(flags).isSampled(), "flags '" + flags + "'");
        }
        Assert.assertTrue(WorkerSpans.flagsOf(null).isSampled());
        Assert.assertFalse(WorkerSpans.flagsOf("00").isSampled());
        Assert.assertEquals(WorkerSpans.idsOf(TRACE, SPAN, "01"),
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN));
        Assert.assertFalse(WorkerSpans.parentOf(null).isValid());
    }

    @Test
    public void testASpansContextCarriesItsFlagsOnlyWhenUnsampled() {
        Span sampled = Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault()));
        Span unsampled = Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.getDefault(), TraceState.getDefault()));
        Assert.assertEquals(WorkerSpans.contextOf(sampled),
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN));
        Assert.assertEquals(WorkerSpans.contextOf(unsampled),
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN, WorkerSpans.TRACE_FLAGS, "00"));
    }

    @Test
    public void testTheStarterRidesTheStartHeaderBesideTheInstancesContext() {
        Map<String, String> instance = Map.of(WorkerSpans.TRACE_ID, RUN_TRACE, WorkerSpans.SPAN_ID, RUN_SPAN);
        Map<String, String> header = WorkerSpans.withSender(instance,
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN, WorkerSpans.TRACE_FLAGS, "00"));
        // The instance's own context is untouched: the run still opens in its own trace.
        Assert.assertEquals(header.get(WorkerSpans.TRACE_ID), RUN_TRACE);
        Assert.assertEquals(header.get(WorkerSpans.SPAN_ID), RUN_SPAN);
        Assert.assertEquals(WorkerSpans.senderOfStart(header),
                Map.of(WorkerSpans.TRACE_ID, TRACE, WorkerSpans.SPAN_ID, SPAN, WorkerSpans.TRACE_FLAGS, "00"));
    }

    @Test
    public void testAStartWithNoStarterCarriesNone() {
        Map<String, String> instance = Map.of(WorkerSpans.TRACE_ID, RUN_TRACE, WorkerSpans.SPAN_ID, RUN_SPAN);
        Assert.assertSame(WorkerSpans.withSender(instance, null), instance);
        Assert.assertNull(WorkerSpans.withSender(null, Map.of(WorkerSpans.TRACE_ID, TRACE,
                WorkerSpans.SPAN_ID, SPAN)));
        Assert.assertNull(WorkerSpans.senderOfStart(instance));
        Assert.assertNull(WorkerSpans.senderOfStart(null));
    }

    @Test
    public void testTheCallersFlagsAreReadFromItsTraceparent() {
        Assert.assertTrue(ClientSpans.flagsOfTraceparent("00-" + TRACE + "-" + SPAN + "-01").isSampled());
        Assert.assertFalse(ClientSpans.flagsOfTraceparent("00-" + TRACE + "-" + SPAN + "-00").isSampled());
        Assert.assertTrue(ClientSpans.flagsOfTraceparent(null).isSampled());
        Assert.assertTrue(ClientSpans.flagsOfTraceparent("garbage").isSampled());
    }

    @DataProvider
    public Object[][] invalidSenderIds() {
        return new Object[][]{
                {"zz", SPAN},
                {TRACE, "short"},
                {"0".repeat(32), SPAN},
                {TRACE, "0".repeat(16)},
                {TRACE.toUpperCase(java.util.Locale.ROOT) + "XX", SPAN}
        };
    }

    // An invalid id keeps the turn in the run's own trace rather than opening spans under a parent that cannot exist.
    @Test(dataProvider = "invalidSenderIds")
    public void testAnInvalidSenderKeepsTheTurnInTheRunsTrace(String traceId, String spanId) {
        Assert.assertNull(WorkerSpans.turnContext(
                Map.of(WorkerSpans.TRACE_ID, traceId, WorkerSpans.SPAN_ID, spanId), null));
    }

    @Test
    public void testNoSenderKeepsTheTurnInTheRunsTrace() {
        Assert.assertNull(WorkerSpans.turnContext(null, null));
        Assert.assertNull(WorkerSpans.turnContext(Map.of(WorkerSpans.SPAN_ID, SPAN), null));
    }
}
