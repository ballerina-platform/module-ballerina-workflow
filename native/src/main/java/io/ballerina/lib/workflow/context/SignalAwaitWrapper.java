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

import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper for managing signal futures in Temporal workflows.
 *
 * <p>Purpose:
 * <ul>
 *   <li>Listen for signals in workflows coming from Temporal</li>
 *   <li>Create future values that can be passed to workflow functions as arguments</li>
 *   <li>During replay, create temporary futures for completed signals and mark them as completed
 *       with the signal data</li>
 * </ul>
 *
 * <p>All signal data is expected to be a map with a mandatory "id" field (String) for correlation.
 * The "id" field is used internally by the workflow engine to identify signals and workflow instances.
 *
 * <p>Other semantics (like awaiting signals) are handled from the Ballerina side using
 * Ballerina's native {@code wait} action on futures.
 *
 * @since 0.1.0
 */
public final class SignalAwaitWrapper {

    /**
     * The key for the mandatory id field in signal/workflow data.
     */
    public static final String ID_FIELD = "id";
    private static final Logger LOGGER = Workflow.getLogger(SignalAwaitWrapper.class);
    // Map of signal name to its promise (future)
    // Each signal gets a CompletablePromise that will be completed when the signal arrives
    private final Map<String, CompletablePromise<SignalData>> signalPromises = new ConcurrentHashMap<>();

    // Map of signal name to its data (for completed signals, used during replay)
    private final Map<String, SignalData> completedSignals = new ConcurrentHashMap<>();

    // FIFO consume-once channel per signal name (used by durable agents' repeatable event
    // waits). Signals not yet consumed queue up in pendingSignals; waiters registered before
    // a signal arrives queue up in signalWaiters. recordSignal feeds this channel in addition
    // to the legacy one-shot path above, which stays unchanged for events-record futures and
    // the built-in human/retry task waits. Both recording (Temporal event-history order) and
    // taking (workflow-thread program order) are deterministic, so this is replay-safe.
    private final Map<String, Deque<SignalData>> pendingSignals = new ConcurrentHashMap<>();
    private final Map<String, Deque<CompletablePromise<SignalData>>> signalWaiters =
            new ConcurrentHashMap<>();

    /**
     * Default constructor for per-workflow instance.
     */
    public SignalAwaitWrapper() {
        // Default constructor
    }

    /**
     * Creates or gets a future for a signal. If the signal has already been received (during replay), returns a
     * completed future. Otherwise, creates a new promise that will be completed when the signal arrives.
     *
     * @param signalName the name of the signal
     * @return a CompletablePromise that will contain the signal data
     */
    public CompletablePromise<SignalData> getSignalFuture(String signalName) {
        // Check if signal was already completed (during replay)
        if (completedSignals.containsKey(signalName)) {
            LOGGER.debug("[SignalAwaitWrapper] Signal '{}' already completed (replay), returning completed future",
                         signalName);
            CompletablePromise<SignalData> completedPromise = Workflow.newPromise();
            completedPromise.complete(completedSignals.get(signalName));
            return completedPromise;
        }

        // Get or create a promise for this signal
        return signalPromises.computeIfAbsent(signalName, k -> {
            LOGGER.debug("[SignalAwaitWrapper] Creating new promise for signal '{}'", signalName);
            return Workflow.newPromise();
        });
    }

    /**
     * Records a signal that has been received. This completes the promise for the signal if one exists, or stores the
     * data for replay. The signal data is expected to contain an "id" field for correlation.
     *
     * @param signalName the name of the signal
     * @param data       the signal data (expected to be a Map with "id" field)
     */
    public void recordSignal(String signalName, Object data) {
        recordSignalData(new SignalData(signalName, extractId(data), data, null));
    }

    /**
     * Records an update (request-response) delivery: like {@link #recordSignal}, but the stored signal carries a
     * responder promise that the consumer completes with the response for this request (durable agents complete it
     * with the answer of the turn that consumed the message).
     *
     * @param signalName the event name the update targets
     * @param data       the update payload
     * @param responder  the promise the update handler is blocked on
     */
    public void recordUpdate(String signalName, Object data, CompletablePromise<Object> responder) {
        recordSignalData(new SignalData(signalName, extractId(data), data, responder));
    }

    private void recordSignalData(SignalData signalData) {
        String signalName = signalData.signalName();

        // Store in completed signals (for replay scenarios)
        completedSignals.put(signalName, signalData);
        LOGGER.debug("[SignalAwaitWrapper] Signal '{}' (id={}) recorded in completed signals",
                signalName, signalData.id());

        // Complete the promise if one exists
        CompletablePromise<SignalData> promise = signalPromises.get(signalName);
        if (promise != null && !promise.isCompleted()) {
            promise.complete(signalData);
            LOGGER.debug("[SignalAwaitWrapper] Promise for signal '{}' completed", signalName);
        }

        // Feed the FIFO consume-once channel: hand the signal to the oldest live
        // waiter, or queue it until someone takes it.
        Deque<CompletablePromise<SignalData>> waiters = signalWaiters.get(signalName);
        if (waiters != null) {
            CompletablePromise<SignalData> waiter;
            while ((waiter = waiters.pollFirst()) != null) {
                if (!waiter.isCompleted()) {
                    waiter.complete(signalData);
                    LOGGER.debug("[SignalAwaitWrapper] FIFO waiter for signal '{}' completed", signalName);
                    return;
                }
            }
        }
        pendingSignals.computeIfAbsent(signalName, k -> new ArrayDeque<>()).addLast(signalData);
        LOGGER.debug("[SignalAwaitWrapper] Signal '{}' queued for FIFO consumption", signalName);
    }

    /**
     * Takes the next undelivered signal of the given name: returns a completed promise when one is queued, otherwise
     * registers and returns a waiter promise that the next {@link #recordSignal} completes. Unlike
     * {@link #getSignalFuture}, each returned promise consumes exactly one signal, so repeated waits observe
     * successive signals (FIFO) — the basis of durable agents' multi-turn event waits.
     *
     * @param signalName the name of the signal
     * @return a CompletablePromise that will contain the next signal data
     */
    public CompletablePromise<SignalData> takeSignalFuture(String signalName) {
        Deque<SignalData> pending = pendingSignals.get(signalName);
        if (pending != null) {
            SignalData next = pending.pollFirst();
            if (next != null) {
                CompletablePromise<SignalData> completed = Workflow.newPromise();
                completed.complete(next);
                LOGGER.debug("[SignalAwaitWrapper] FIFO take of signal '{}' served from queue", signalName);
                return completed;
            }
        }
        CompletablePromise<SignalData> waiter = Workflow.newPromise();
        signalWaiters.computeIfAbsent(signalName, k -> new ArrayDeque<>()).addLast(waiter);
        LOGGER.debug("[SignalAwaitWrapper] FIFO waiter registered for signal '{}'", signalName);
        return waiter;
    }

    /**
     * Cancels a FIFO waiter previously returned by {@link #takeSignalFuture} (e.g. on wait timeout), so a later
     * signal is not silently consumed by an abandoned promise.
     *
     * @param signalName the signal name the waiter was registered for
     * @param waiter     the waiter promise to remove
     */
    public void cancelWaiter(String signalName, CompletablePromise<SignalData> waiter) {
        Deque<CompletablePromise<SignalData>> waiters = signalWaiters.get(signalName);
        if (waiters != null) {
            waiters.remove(waiter);
        }
    }

    /**
     * Removes and returns the responder promises of all queued update deliveries that were never consumed. Called
     * when a durable agent finishes, so accepted-but-unconsumed updates are answered (with the agent's final
     * response, or its failure) instead of failing with "workflow completed before the update completed".
     *
     * @return the responders of unconsumed updates, oldest first
     */
    public List<CompletablePromise<Object>> drainPendingResponders() {
        List<CompletablePromise<Object>> responders = new ArrayList<>();
        for (Deque<SignalData> queue : pendingSignals.values()) {
            Iterator<SignalData> iterator = queue.iterator();
            while (iterator.hasNext()) {
                SignalData signalData = iterator.next();
                if (signalData.responder() != null) {
                    responders.add(signalData.responder());
                    iterator.remove();
                }
            }
        }
        return responders;
    }

    /**
     * Extracts the "id" field from the data object.
     *
     * @param data the data object (expected to be a Map)
     * @return the id value, or null if not found
     */
    @SuppressWarnings("unchecked")
    private String extractId(Object data) {
        if (data instanceof Map) {
            Object idValue = ((Map<String, Object>) data).get(ID_FIELD);
            if (idValue != null) {
                return idValue.toString();
            }
        }
        return null;
    }

    /**
     * Container for signal data that wraps the signal name, id, and data together. The "id" field is extracted from the
     * data for easy correlation access.
     *
     * @param signalName the signal name
     * @param id         the correlation id (from "id" field in data)
     * @param data       the full signal data
     * @param responder  non-null when this delivery is a request-response update: the promise the update handler is
     *                   blocked on, to be completed with the response for this request
     */
    public record SignalData(String signalName, String id, Object data, CompletablePromise<Object> responder) {
        /**
         * Creates a new SignalData.
         */
        public SignalData {
            Objects.requireNonNull(signalName, "signalName must not be null");
        }

        /**
         * Creates a plain (one-way) signal data without a responder.
         *
         * @param signalName the signal name
         * @param id         the correlation id
         * @param data       the full signal data
         */
        public SignalData(String signalName, String id, Object data) {
            this(signalName, id, data, null);
        }

        /**
         * Gets the signal data.
         *
         * @return the full signal data
         */
        @Override
        public Object data() {
            return data;
        }

        @Override
        public String toString() {
            return "SignalData{signalName='" + signalName + "', id='" + id + "', data=" + data
                    + "', hasResponder=" + (responder != null) + "}";
        }
    }
}
