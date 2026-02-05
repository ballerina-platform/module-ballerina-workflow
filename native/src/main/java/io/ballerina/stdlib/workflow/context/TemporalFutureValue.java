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

package io.ballerina.stdlib.workflow.context;

import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.internal.scheduling.Scheduler;
import io.ballerina.runtime.internal.scheduling.Strand;
import io.ballerina.runtime.internal.values.FutureValue;
import io.ballerina.stdlib.workflow.utils.TypesUtil;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.Collections;

/**
 * A FutureValue implementation for Temporal workflow signals.
 * 
 * <p>This class extends Ballerina's {@link FutureValue} to bridge Temporal's 
 * {@link CompletablePromise} to Ballerina's wait mechanism.
 * <p>
 * Ballerina's wait action uses AsyncUtils.handleWait() which:
 * 1. Calls future.getAndSetWaited() - we intercept here
 * 2. Directly calls future.completableFuture.get() - which would block!
 * 
 * <p>The problem is that CompletableFuture.get() blocks the Java thread, which
 * triggers Temporal's deadlock detection (PotentialDeadlockException) because
 * Temporal expects workflow threads to yield control using Workflow.await().
 * 
 * <p>Our solution is to override getAndSetWaited() to:
 * 1. Call Workflow.await() to wait for the signal in a Temporal-safe way
 * 2. Once await() returns, completableFuture is guaranteed complete
 * 3. When Ballerina then calls completableFuture.get(), it returns immediately
 * 
 * <p>This ensures that:
 * - Temporal's workflow thread can yield during the wait (proper replay behavior)
 * - No deadlock detection is triggered
 * - Ballerina's standard wait mechanism works without modification
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
     * Creates a new TemporalFutureValue wrapping a Temporal CompletablePromise.
     * 
     * <p>This constructor:
     * 1. Creates a FutureValue with a minimal Strand
     * 2. Sets up a Temporal callback to complete the Java CompletableFuture when signal arrives
     *
     * @param promise the Temporal CompletablePromise to wrap
     * @param signalName the name of the signal this future is waiting for
     * @param constraintType the Ballerina type that the signal data should be converted to
     * @param scheduler the Ballerina scheduler (from runtime)
     */
    public TemporalFutureValue(CompletablePromise<SignalAwaitWrapper.SignalData> promise, 
                               String signalName, Type constraintType, Scheduler scheduler) {
        // Create FutureValue with a valid strand that won't throw on checkStrandCancelled()
        super(createStrand(scheduler, signalName), constraintType);
        this.promise = promise;
        this.signalName = signalName;
        this.constraintType = constraintType;
        
        // Set up callback: when Temporal's promise is completed, complete the Java CompletableFuture
        setupPromiseCallback();
    }
    
    /**
     * Creates a minimal Strand for this future.
     * The strand is isolated (no global lock needed) and not cancelled.
     */
    private static Strand createStrand(Scheduler scheduler, String signalName) {
        if (scheduler == null) {
            scheduler = new Scheduler(null);
        }
        return new Strand(scheduler, "signal-" + signalName, null, true, 
                Collections.emptyMap(), null);
    }
    
    /**
     * Sets up a callback on the Temporal promise to complete the Java CompletableFuture.
     * 
     * <p>When the signal arrives:
     * 1. Temporal's signal handler completes the promise
     * 2. This callback is triggered
     * 3. We convert the signal data to Ballerina type
     * 4. We complete the inherited {@code completableFuture}
     * 5. Ballerina's wait (which is blocking on completableFuture.get()) returns
     */
    private void setupPromiseCallback() {
        // Use Temporal's thenApply to set up a callback
        // This callback runs when promise.complete() is called by the signal handler
        promise.thenApply(signalData -> {
            try {
                LOGGER.debug("[TemporalFutureValue] Signal '{}' received, processing callback", signalName);
                
                // Convert the signal data to Ballerina type
                Object rawData = signalData.data();
                Object ballerinaData = TypesUtil.convertJavaToBallerinaType(rawData);
                
                // Clone with type to ensure proper type matching
                Object result = TypesUtil.cloneWithType(ballerinaData, constraintType);
                
                // Complete the inherited Java CompletableFuture
                // This will unblock Ballerina's wait action
                this.completableFuture.complete(result);
                
                LOGGER.debug("[TemporalFutureValue] CompletableFuture completed for signal '{}'", signalName);
                
                return result;
            } catch (Exception e) {
                LOGGER.error("[TemporalFutureValue] Error processing signal '{}': {}", 
                        signalName, e.getMessage(), e);
                // Complete exceptionally
                this.completableFuture.completeExceptionally(e);
                throw e;
            }
        });
        
        // Also handle failure case
        promise.exceptionally(ex -> {
            LOGGER.error("[TemporalFutureValue] Signal '{}' promise failed: {}", signalName, ex.getMessage(), ex);
            this.completableFuture.completeExceptionally(ex);
            // Return null since the exception is already handled via completeExceptionally
            return null;
        });
        
        LOGGER.debug("[TemporalFutureValue] Promise callback registered for signal '{}'", signalName);
    }

    /**
     * Returns the result value of the future.
     * <p>
     * Note: This method may not be called directly by Ballerina's wait action
     * (which uses AsyncUtils.handleWait), but we override it for completeness.
     *
     * @return the signal data converted to Ballerina type
     */
    @Override
    public Object get() {
        try {
            // Ensure signal is ready using Temporal's await
            ensureSignalReady();
            return this.completableFuture.get();
        } catch (Exception e) {
            throw new RuntimeException("Error getting signal value for '" + signalName + "'", e);
        }
    }
    
    /**
     * Called by Ballerina's AsyncUtils.handleWait before accessing completableFuture.
     * <p>
     * CRITICAL: This method is called BEFORE Ballerina calls completableFuture.get().
     * We use this as an interception point to block using Temporal's Workflow.await()
     * until the signal arrives. This ensures that when completableFuture.get() is called,
     * it will return immediately without blocking the Temporal workflow thread.
     * <p>
     * The flow is:
     * 1. Ballerina's wait action calls AsyncUtils.handleWait(strand, future)
     * 2. AsyncUtils calls future.getAndSetWaited()
     * 3. We intercept here and use Workflow.await() to wait for the signal
     * 4. Once Workflow.await() returns, completableFuture is guaranteed complete
     * 5. AsyncUtils then calls completableFuture.get() which returns immediately
     *
     * @return false to allow waiting (we handle the actual blocking in ensureSignalReady)
     */
    @Override
    public boolean getAndSetWaited() {
        // First, ensure the signal is ready using Temporal's await mechanism
        // This blocks the workflow thread in a Temporal-safe way
        ensureSignalReady();
        
        // Return false to indicate this future hasn't been waited on yet
        // (we allow multiple waits on signal futures since the value is cached)
        return false;
    }
    
    /**
     * Ensures the signal is ready by using Temporal's Workflow.await().
     * <p>
     * This method blocks using Temporal's cooperative scheduling mechanism
     * until the signal arrives and the completableFuture is complete.
     * Unlike Java's CompletableFuture.get(), Workflow.await() properly
     * yields control to Temporal's event loop, avoiding deadlock detection.
     */
    private void ensureSignalReady() {
        if (!this.completableFuture.isDone()) {
            LOGGER.debug("[TemporalFutureValue] Waiting for signal '{}' using Temporal await", signalName);
            // Use Temporal's await - this yields control properly during replay
            Workflow.await(this.completableFuture::isDone);
            LOGGER.debug("[TemporalFutureValue] Signal '{}' is ready", signalName);
        }
    }

    /**
     * Checks if this future is done (signal has been received).
     *
     * @return true if the signal has been received
     */
    @Override
    public boolean isDone() {
        return completableFuture.isDone();
    }

    /**
     * Checks if this future resulted in a panic.
     *
     * @return true if the completableFuture completed exceptionally
     */
    @Override
    public boolean isPanic() {
        return completableFuture.isCompletedExceptionally();
    }

    /**
     * Cancels this future. Not supported for Temporal signal futures.
     */
    @Override
    public void cancel() {
        LOGGER.warn("[TemporalFutureValue] cancel() called on signal future '{}' - not supported", signalName);
    }
}
