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

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.internal.scheduling.Scheduler;
import io.ballerina.runtime.internal.scheduling.Strand;
import io.ballerina.runtime.internal.values.FutureValue;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A FutureValue implementation for Temporal workflow signals.
 *
 * <p>This class extends Ballerina's {@link FutureValue} to bridge Temporal's
 * {@link CompletablePromise} to Ballerina's wait mechanism.
 *
 * <h3>Problem</h3>
 * Ballerina's {@code AsyncUtils} handles wait expressions by:
 * <ol>
 *   <li>Calling {@code future.getAndSetWaited()} on each future</li>
 *   <li>Calling {@code completableFuture.get()} (single wait) or
 *       {@code CompletableFuture.anyOf(...).get()} (alternate wait)</li>
 * </ol>
 * {@code CompletableFuture.get()} blocks the Java thread, which triggers Temporal's
 * deadlock detection because Temporal expects workflow threads to yield via
 * {@code Workflow.await()}.
 *
 * <h3>Solution</h3>
 * <ul>
 *   <li>Each {@code TemporalFutureValue} knows about its <em>sibling group</em> — all
 *       event futures for the same workflow execution.</li>
 *   <li>{@code getAndSetWaited()} uses {@code Workflow.await()} to cooperatively block
 *       until <em>any</em> sibling's {@code completableFuture} is done.</li>
 *   <li>The inherited {@code completableFuture} is replaced (via reflection) with a
 *       {@link TemporalCompletableFuture} whose {@code get()} methods use
 *       {@code Workflow.await()} as a fallback, ensuring no Temporal thread is blocked.</li>
 * </ul>
 *
 * <p>This design supports:
 * <ul>
 *   <li><b>Single wait</b> ({@code wait f1}) — blocks until this signal arrives.</li>
 *   <li><b>Alternate wait</b> ({@code wait f1|f2}) — blocks until <em>any</em> signal
 *       arrives; AsyncUtils' {@code anyOf().get()} returns immediately because at least
 *       one CF is already complete.</li>
 *   <li><b>Sequential wait</b> ({@code wait f1; wait f2;}) — each handled independently.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class TemporalFutureValue extends FutureValue {

    private static final Logger LOGGER = Workflow.getLogger(TemporalFutureValue.class);

    /** The Temporal promise that receives signal data. */
    private final CompletablePromise<SignalAwaitWrapper.SignalData> promise;

    /** The signal name this future is waiting for. */
    private final String signalName;

    /** The expected Ballerina type for the signal data. */
    private final Type constraintType;

    /**
     * Sibling group — all event futures belonging to the same workflow execution.
     * Set after construction via {@link #setSiblingGroup(List)}.
     * Used by {@link #getAndSetWaited()} to wait for <em>any</em> sibling signal.
     */
    private List<TemporalFutureValue> siblingGroup;

    /**
     * Creates a new TemporalFutureValue wrapping a Temporal CompletablePromise.
     *
     * <p>After construction, call {@link #setSiblingGroup(List)} to register all
     * event futures for this workflow so that alternate wait can work.
     *
     * @param promise        the Temporal CompletablePromise to wrap
     * @param signalName     the name of the signal this future is waiting for
     * @param constraintType the Ballerina type that the signal data should be converted to
     * @param scheduler      the Ballerina scheduler (from runtime)
     */
    public TemporalFutureValue(CompletablePromise<SignalAwaitWrapper.SignalData> promise,
                               String signalName, Type constraintType, Scheduler scheduler) {
        super(createStrand(scheduler, signalName), constraintType);
        this.promise = promise;
        this.signalName = signalName;
        this.constraintType = constraintType;
        // Default group: just this future (single-event workflows)
        this.siblingGroup = new ArrayList<>(Collections.singletonList(this));

        setupPromiseCallback();
        replaceCompletableFuture();
    }

    /**
     * Sets the sibling group for this future. Called by {@code EventFutureCreator}
     * after all event futures for a workflow execution have been created.
     *
     * @param group all event futures (including this one) for the workflow
     */
    public void setSiblingGroup(List<TemporalFutureValue> group) {
        this.siblingGroup = group;
    }

    // ---- Internal helpers ----

    private static Strand createStrand(Scheduler scheduler, String signalName) {
        if (scheduler == null) {
            scheduler = new Scheduler(null);
        }
        return new Strand(scheduler, "signal-" + signalName, null, true,
                Collections.emptyMap(), null);
    }

    /**
     * Replaces the inherited {@code completableFuture} field with a
     * {@link TemporalCompletableFuture} that uses {@code Workflow.await()} in its
     * {@code get()} methods, providing a safety net against thread-blocking.
     */
    private void replaceCompletableFuture() {
        try {
            TemporalCompletableFuture temporalCF = new TemporalCompletableFuture(signalName);
            java.lang.reflect.Field cfField = FutureValue.class.getDeclaredField("completableFuture");
            cfField.setAccessible(true);

            // Use sun.misc.Unsafe or VarHandle to write to the final field
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            long offset = unsafe.objectFieldOffset(cfField);
            unsafe.putObject(this, offset, temporalCF);
        } catch (Exception e) {
            // If reflection fails, fall back to the default CompletableFuture.
            // Single wait and sequential waits will still work; only alternate wait
            // may hang if signals arrive out of order.
            LOGGER.warn("[TemporalFutureValue] Could not replace completableFuture for '{}': {}",
                    signalName, e.getMessage());
        }
    }

    private void setupPromiseCallback() {
        promise.thenApply(signalData -> {
            try {
                LOGGER.debug("[TemporalFutureValue] Signal '{}' received, processing callback", signalName);
                Object rawData = signalData.data();
                Object ballerinaData = TypesUtil.convertJavaToBallerinaType(rawData);
                Object result = TypesUtil.cloneWithType(ballerinaData, constraintType);
                this.completableFuture.complete(result);
                LOGGER.debug("[TemporalFutureValue] CompletableFuture completed for signal '{}'", signalName);
                return result;
            } catch (Exception e) {
                LOGGER.error("[TemporalFutureValue] Error processing signal '{}': {}",
                        signalName, e.getMessage(), e);
                this.completableFuture.completeExceptionally(e);
                throw e;
            }
        });

        promise.exceptionally(ex -> {
            LOGGER.error("[TemporalFutureValue] Signal '{}' promise failed: {}", signalName, ex.getMessage(), ex);
            this.completableFuture.completeExceptionally(ex);
            return null;
        });

        LOGGER.debug("[TemporalFutureValue] Promise callback registered for signal '{}'", signalName);
    }

    // ---- FutureValue overrides ----

    @Override
    public Object get() {
        try {
            ensureAnySiblingReady();
            ensureThisReady();
            return this.completableFuture.get();
        } catch (Exception e) {
            throw new RuntimeException("Error getting signal value for '" + signalName + "'", e);
        }
    }

    /**
     * Called by Ballerina's AsyncUtils before accessing {@code completableFuture}.
     *
     * <p>For <b>single wait</b> and <b>sequential waits</b>, this cooperatively blocks
     * until <em>any</em> sibling signal arrives (which for a single-field events record
     * means this signal). The subsequent {@code completableFuture.get()} returns
     * immediately because the CF is already complete.
     *
     * <p>For <b>alternate wait</b> ({@code wait f1|f2}), AsyncUtils calls this method
     * on each future in a loop before calling {@code anyOf().get()}. By waiting for
     * <em>any</em> sibling, the first call blocks until some signal arrives, pre-completing
     * at least one CF. Subsequent calls return immediately. When AsyncUtils then calls
     * {@code anyOf().get()}, it also returns immediately.
     *
     * @return always {@code false} (allow waiting)
     */
    @Override
    public boolean getAndSetWaited() {
        ensureAnySiblingReady();
        return false;
    }

    /**
     * Cooperatively blocks until <em>any</em> sibling's completableFuture is done.
     * Uses {@code Workflow.await()} so that Temporal can deliver signals while this
     * coroutine is suspended.
     */
    private void ensureAnySiblingReady() {
        if (anySiblingDone()) {
            return;
        }
        LOGGER.debug("[TemporalFutureValue] Waiting for any sibling signal (this='{}')", signalName);
        Workflow.await(this::anySiblingDone);
        LOGGER.debug("[TemporalFutureValue] A sibling signal is ready (this='{}')", signalName);
    }

    /**
     * Cooperatively blocks until <em>this</em> future's completableFuture is done.
     * Only called from {@link #get()} as a safety net.
     */
    private void ensureThisReady() {
        if (!this.completableFuture.isDone()) {
            LOGGER.debug("[TemporalFutureValue] Waiting for signal '{}' using Temporal await", signalName);
            Workflow.await(this.completableFuture::isDone);
            LOGGER.debug("[TemporalFutureValue] Signal '{}' is ready", signalName);
        }
    }

    private boolean anySiblingDone() {
        for (TemporalFutureValue sibling : siblingGroup) {
            if (sibling.completableFuture.isDone()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isDone() {
        return completableFuture.isDone();
    }

    @Override
    public boolean isPanic() {
        return completableFuture.isCompletedExceptionally();
    }

    @Override
    public void cancel() {
        LOGGER.warn("[TemporalFutureValue] cancel() called on signal future '{}' - not supported", signalName);
    }

    // ---- Temporal-safe CompletableFuture ----

    /**
     * A CompletableFuture subclass whose {@code get()} methods use
     * {@code Workflow.await()} as a fallback to avoid blocking the Temporal
     * workflow thread. This provides a safety net: if something bypasses
     * {@link #getAndSetWaited()} and calls {@code get()} directly, we
     * cooperatively yield instead of causing a deadlock.
     */
    static final class TemporalCompletableFuture extends CompletableFuture<Object> {

        private final String signalName;

        TemporalCompletableFuture(String signalName) {
            this.signalName = signalName;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            if (!isDone()) {
                Workflow.await(this::isDone);
            }
            return super.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!isDone()) {
                Workflow.await(this::isDone);
            }
            return super.get(timeout, unit);
        }

        @Override
        public Object join() {
            if (!isDone()) {
                Workflow.await(this::isDone);
            }
            return super.join();
        }

        @Override
        public String toString() {
            return "TemporalCompletableFuture{signal='" + signalName + "', done=" + isDone() + "}";
        }
    }
}
